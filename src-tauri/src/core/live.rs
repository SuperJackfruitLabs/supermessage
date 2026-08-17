//! The live view of an agent's turn, while it is still being written.
//!
//! AgentPod's bridge pushes a turn's text to the reader's own devices as it
//! arrives, and sends the finished answer to the room once — see that project's
//! `matrix-as/live.ts` for why the stream and the durable record are separate
//! channels rather than a message edited repeatedly in place.
//!
//! What arrives here is therefore **not history**. It is never stored, never
//! paginated, never searched, and a device that was asleep simply misses it and
//! sees the real message instead. Everything in this module is a hint about a
//! message that has not landed yet; the timeline remains the only source of
//! truth about what was said.
//!
//! ## Why the deltas are cumulative
//!
//! To-device delivery is at-least-once and carries no ordering guarantee. Each
//! delta therefore carries the whole answer so far, and a sequence number: apply
//! the highest `seq` seen and the result is correct however the deltas arrived,
//! including not at all. An increment would let one dropped or reordered
//! message corrupt the text with nothing able to notice.

use std::collections::HashMap;
use std::sync::Mutex;

use matrix_sdk::event_handler::{Ctx, EventHandlerHandle};
use matrix_sdk::ruma::events::macros::EventContent;
use matrix_sdk::Client;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter};

/// One delta of a turn in progress, as AgentPod sends it.
///
/// The type is `dev.agentpod.stream.delta`: an AgentPod-specific event, not a
/// Matrix one, and deliberately namespaced so no future spec event can collide
/// with it. A homeserver without AgentPod on it simply never sends this.
#[derive(Clone, Debug, Deserialize, Serialize, EventContent)]
#[ruma_event(type = "dev.agentpod.stream.delta", kind = ToDevice)]
pub struct StreamDeltaToDeviceEventContent {
    /// The room whose agent is writing. A reader watching another room ignores
    /// it — the delta is addressed to a *device*, not to a room.
    pub room_id: String,
    /// The ACP session this turn belongs to. A new turn restarts at `seq: 1`,
    /// so this is what distinguishes "a fresh answer" from "more of the last".
    pub session_id: String,
    /// Monotonic within a turn; see the module comment for why it is what makes
    /// unordered delivery safe.
    pub seq: u64,
    /// Everything the agent has said this turn, not the increment.
    pub text: String,
    /// The last delta of a turn: the room now holds the real message, so the
    /// live view has served its purpose and should give way to it.
    pub done: bool,
}

/// What the webview is told when a turn moves.
///
/// Mirrors the wire event rather than wrapping it, minus the session id, which
/// the UI has no use for: it renders per room, and a room has one agent
/// writing at a time.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LivePayload {
    pub room_id: String,
    pub seq: u64,
    pub text: String,
    pub done: bool,
}

/// Tauri event channel carrying live turn text to the webview.
pub const LIVE_EVENT: &str = "sm://live";

/// Whether a delta is newer than what the receiver already has for this turn.
///
/// Pure, and the only rule worth testing here: `seq` decides, never arrival
/// order. Equal sequences are rejected too — a duplicate carries no new text by
/// construction, and re-rendering on it would be work for nothing.
pub fn supersedes(incoming_seq: u64, current_seq: Option<u64>) -> bool {
    match current_seq {
        None => true,
        Some(current) => incoming_seq > current,
    }
}

/// Whether a delta belongs to a different turn than the one being shown.
///
/// A turn restarts at `seq: 1`, so a lower sequence is not a stale delta but
/// the beginning of a new answer — the one case where "older" must be accepted
/// rather than dropped. Keyed on the session, because that is what actually
/// changes between turns.
pub fn starts_new_turn(incoming_session: &str, current_session: Option<&str>) -> bool {
    match current_session {
        None => true,
        Some(current) => incoming_session != current,
    }
}

/// What has been shown per room, so a stale or repeated delta can be dropped
/// before it crosses IPC.
///
/// Held in the core rather than the webview deliberately: the rules are two
/// pure functions with tests, and keeping them here means the webview receives
/// only deltas that are actually news. A room is forgotten when its turn ends,
/// so this cannot grow with the number of rooms a session has ever seen.
#[derive(Debug, Default)]
pub struct LiveState {
    seen: Mutex<HashMap<String, (String, u64)>>,
}

impl LiveState {
    /// Whether this delta is worth showing, recording it if so.
    pub fn accept(&self, room_id: &str, session_id: &str, seq: u64, done: bool) -> bool {
        let mut seen = self
            .seen
            .lock()
            .expect("live-stream state lock poisoned by an earlier panic");

        let current = seen.get(room_id);
        let fresh = match current {
            Some((session, _)) if starts_new_turn(session_id, Some(session.as_str())) => true,
            Some((_, last_seq)) => supersedes(seq, Some(*last_seq)),
            None => true,
        };
        if !fresh {
            return false;
        }

        if done {
            // The turn is over and the room holds the real message. Forgetting
            // the room now is what keeps this map the size of "turns in
            // flight" rather than "rooms ever streamed into".
            seen.remove(room_id);
        } else {
            seen.insert(room_id.to_string(), (session_id.to_string(), seq));
        }
        true
    }
}

/// Listen for live turn text and forward it to the webview.
///
/// Registered against the client, so it dies with the session — a logout
/// rebuilds the client and takes this with it.
pub fn listen(client: &Client, app: AppHandle) -> EventHandlerHandle {
    client.add_event_handler_context(app);
    client.add_event_handler_context(std::sync::Arc::new(LiveState::default()));

    client.add_event_handler(
        |ev: StreamDeltaToDeviceEvent,
         app: Ctx<AppHandle>,
         state: Ctx<std::sync::Arc<LiveState>>| async move {
            let content = ev.content;
            if !state.accept(
                &content.room_id,
                &content.session_id,
                content.seq,
                content.done,
            ) {
                return;
            }

            let payload = LivePayload {
                room_id: content.room_id,
                seq: content.seq,
                text: content.text,
                done: content.done,
            };
            if let Err(err) = app.emit(LIVE_EVENT, &payload) {
                tracing::warn!(error = %err, "failed to emit {LIVE_EVENT}");
            }
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_sequence_decides_not_the_arrival_order() {
        // The property that makes unordered to-device delivery safe.
        assert!(supersedes(2, Some(1)));
        assert!(!supersedes(1, Some(2)));
        assert!(supersedes(7, None));
    }

    #[test]
    fn a_repeated_delta_is_not_worth_re_rendering() {
        // At-least-once delivery means the same delta can arrive twice; it
        // carries identical text, so applying it again is work for nothing.
        assert!(!supersedes(3, Some(3)));
    }

    #[test]
    fn state_drops_a_stale_delta_and_keeps_a_newer_one() {
        let state = LiveState::default();

        assert!(state.accept("!r", "s1", 1, false));
        assert!(state.accept("!r", "s1", 2, false));
        assert!(!state.accept("!r", "s1", 2, false), "a repeat is not news");
        assert!(
            !state.accept("!r", "s1", 1, false),
            "an out-of-order delta is not news"
        );
    }

    #[test]
    fn state_accepts_the_first_delta_of_the_next_turn_despite_its_lower_sequence() {
        // Every turn restarts at 1, so without the session check the whole of a
        // second answer would be dropped as stale.
        let state = LiveState::default();

        assert!(state.accept("!r", "s1", 4, false));
        assert!(state.accept("!r", "s2", 1, false));
    }

    #[test]
    fn state_forgets_a_room_once_its_turn_is_done() {
        // Otherwise the map grows with every room ever streamed into, and a
        // later turn in the same session would be measured against a sequence
        // from minutes ago.
        let state = LiveState::default();

        assert!(state.accept("!r", "s1", 3, true));
        assert!(
            state.accept("!r", "s1", 1, false),
            "the next turn starts clean"
        );
    }

    #[test]
    fn state_tracks_rooms_independently() {
        let state = LiveState::default();

        assert!(state.accept("!a", "s1", 5, false));
        assert!(
            state.accept("!b", "s2", 1, false),
            "another room is not behind"
        );
    }

    #[test]
    fn a_new_turn_is_recognised_by_its_session_not_its_sequence() {
        // Every turn restarts at 1. Without this, the first delta of a new
        // answer would look like a stale delta of the old one and be dropped —
        // and the reader would watch nothing happen while the agent wrote.
        assert!(starts_new_turn("session-2", Some("session-1")));
        assert!(!starts_new_turn("session-1", Some("session-1")));
        assert!(starts_new_turn("session-1", None));
    }
}
