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

use std::sync::Arc;

use crate::event::{CoreEvent, EventSink};
use crate::turns::{now_ms, TurnBook, TurnInput, TurnStep};

use std::collections::HashMap;
use std::sync::Mutex;

use matrix_sdk::event_handler::{Ctx, EventHandlerHandle};
use matrix_sdk::ruma::events::macros::EventContent;
use matrix_sdk::Client;
use serde::{Deserialize, Serialize};

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

/// One delta of a turn's *reasoning*, as AgentPod sends it.
///
/// Byte-for-byte the same body as a stream delta, and a different type. The
/// shape is reused so everything below — `supersedes`, `starts_new_turn`,
/// `LiveState`, and the webview's reveal pacing — works on it unchanged. The
/// type differs because a reader must be able to tell an agent's thinking from
/// its answer, and because the two arrive interleaved.
///
/// Reasoning never reaches a room at all, on either side of the bridge. It is
/// watchable while it happens and then gone; the console's transcript is where
/// it is kept.
#[derive(Clone, Debug, Deserialize, Serialize, EventContent)]
#[ruma_event(type = "dev.agentpod.thought.delta", kind = ToDevice)]
pub struct ThoughtDeltaToDeviceEventContent {
    pub room_id: String,
    pub session_id: String,
    pub seq: u64,
    /// Everything the agent has thought this turn, not the increment.
    pub text: String,
    pub done: bool,
}

/// One tool call's state, as AgentPod sends it.
#[derive(Clone, Debug, Deserialize, Serialize, EventContent)]
#[ruma_event(type = "dev.agentpod.tool.update", kind = ToDevice)]
pub struct ToolUpdateToDeviceEventContent {
    pub room_id: String,
    pub session_id: String,
    /// Monotonic within a turn. Carried for the webview to order by; see
    /// `listen` for why this channel is not de-duplicated here.
    pub seq: u64,
    /// The identity later updates merge onto.
    pub tool_call_id: String,
    pub title: String,
    /// ACP's tool kind. Absent when the harness did not say, and opaque display
    /// text when it did — never switched on.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    /// `pending` | `in_progress` | `completed` | `failed`, passed through as a
    /// string so a harness inventing a fifth lands as unknown rather than as a
    /// deserialization failure that would drop the whole event.
    pub status: String,
    #[serde(default)]
    pub locations: Vec<String>,
    /// What the call was given, as text a reader can look at.
    ///
    /// Optional because AgentPod does not send it yet — a harness that starts
    /// to will land here without a schema change, and one that never does
    /// leaves the row exactly as it is today. Never parsed or interpreted:
    /// it is a sender-controlled string, bounded before it is shown.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub input: Option<String>,
    /// What the call produced, under the same rules as [`Self::input`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
}

/// Where one tool call is, in the reader's terms.
///
/// ACP's `status` is wire vocabulary (`in_progress`), and every host used to
/// print it as it arrived or rewrite it by hand: iOS showed `in_progress`,
/// Android the same, and the desktop `IN PROGRESS`. The phase is decided
/// here, once, and a host picks a glyph and a colour from it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum ToolPhase {
    /// `pending`: announced, not started.
    Queued,
    /// `in_progress`.
    Running,
    /// `completed`.
    Done,
    /// `failed`. The one phase a host colours.
    Failed,
    /// A status this build was not taught. Shown as neutral, never guessed at.
    Unknown,
}

impl ToolPhase {
    pub fn from_status(status: &str) -> Self {
        match status {
            "pending" => Self::Queued,
            "in_progress" => Self::Running,
            "completed" => Self::Done,
            "failed" => Self::Failed,
            _ => Self::Unknown,
        }
    }

    /// The word a host shows beside the call. User-visible copy.
    pub fn label(&self) -> &'static str {
        match self {
            Self::Queued => "Queued",
            Self::Running => "Running",
            Self::Done => "Done",
            Self::Failed => "Failed",
            Self::Unknown => "Working",
        }
    }
}

/// What the webview is told when a tool call moves.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolPayload {
    pub room_id: String,
    pub seq: u64,
    pub tool_call_id: String,
    pub title: String,
    pub kind: Option<String>,
    /// ACP's raw status, kept for ordering logic that predates [`ToolPhase`].
    /// Not for display — see [`Self::phase`] and [`Self::status_label`].
    pub status: String,
    pub phase: ToolPhase,
    /// [`ToolPhase::label`], carried so a host renders it rather than
    /// spelling its own.
    pub status_label: String,
    pub locations: Vec<String>,
    /// Bounded for display — see [`bound_tool_text`].
    pub input: Option<String>,
    pub output: Option<String>,
}

/// How much of a tool's input or output crosses to a host.
///
/// A tool can return a whole file. The row that shows it is a disclosure
/// inside a turn card, and past a few thousand characters nobody is reading
/// it there — they are looking for whether it worked and roughly what it
/// touched. Bounding here rather than in a host is the same rule every other
/// sender-controlled string in this crate follows: the boundary is where the
/// guarantee is made.
const TOOL_TEXT_MAX_CHARS: usize = 4_000;

/// Trim `value` to [`TOOL_TEXT_MAX_CHARS`], appending an ellipsis when it
/// bites. `None` and empty both come back as `None` — a disclosure that opens
/// on nothing is worse than no disclosure.
pub fn bound_tool_text(value: Option<String>) -> Option<String> {
    let value = value?;
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.chars().count() <= TOOL_TEXT_MAX_CHARS {
        return Some(trimmed.to_string());
    }
    let head: String = trimmed.chars().take(TOOL_TEXT_MAX_CHARS).collect();
    Some(format!("{head}…"))
}

/// Tauri event channel carrying an agent's reasoning to the webview.
pub const THOUGHT_EVENT: &str = "sm://thought";

/// Tauri event channel carrying tool-call state to the webview.
pub const TOOL_EVENT: &str = "sm://tool";

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

/// The reasoning channel's own [`LiveState`].
///
/// A newtype rather than a second `Arc<LiveState>`, because
/// `add_event_handler_context` keys contexts **by type**: registering the same
/// type twice would hand both handlers the same map, and an answer delta and a
/// thought delta for one room would evict each other's sequence number — each
/// stream dropping roughly half of the other's deltas as stale.
#[derive(Debug, Default)]
pub struct ThoughtState(LiveState);

impl ThoughtState {
    /// Whether this reasoning delta is worth showing. Same rules as the answer's.
    pub fn accept(&self, room_id: &str, session_id: &str, seq: u64, done: bool) -> bool {
        self.0.accept(room_id, session_id, seq, done)
    }
}

/// The turn book as one `listen` call sees it: the book, and the sign-in it
/// was created for.
///
/// The book is session-owned and outlives the client, so a handler of an old
/// client can still be running when the account signs out and in again. It
/// carries the generation it was registered under, and the book refuses it
/// once `clear` has moved on (see [`TurnBook::apply_in`]).
#[derive(Clone)]
struct TurnFeed {
    book: Arc<TurnBook>,
    generation: u64,
}

/// Records `input` in the turn book and, when the book accepts it, tells the
/// host: the turn update first, then `raw`.
///
/// The update goes **first** so a host that starts afresh on a new `turn_id`
/// does so before the text that began the turn arrives, rather than wiping it.
/// An input the book refuses (a tool report the network delivered late, or
/// anything from a sign-in that has ended) reaches no host at all.
fn deliver(
    sink: &Arc<dyn EventSink>,
    turns: &TurnBook,
    generation: u64,
    room_id: &str,
    input: TurnInput,
    raw: CoreEvent,
) {
    let outcome = turns.apply_in(generation, room_id, input);
    if !outcome.accepted {
        return;
    }
    if let Some(update) = outcome.update {
        sink.emit(CoreEvent::Turn(update));
    }
    sink.emit(raw);
}

/// Listen for live turn text and forward it to the webview.
///
/// Registered against the client, so it dies with the session — a logout
/// rebuilds the client and takes this with it. Every delta also goes into
/// `turns`, which does not die with the client: its handlers are bound to the
/// book's generation at this call and stop writing when it is cleared.
pub fn listen(
    client: &Client,
    sink: Arc<dyn EventSink>,
    turns: Arc<TurnBook>,
) -> EventHandlerHandle {
    let generation = turns.generation();
    client.add_event_handler_context(sink);
    client.add_event_handler_context(TurnFeed {
        book: turns,
        generation,
    });
    client.add_event_handler_context(std::sync::Arc::new(LiveState::default()));
    client.add_event_handler_context(std::sync::Arc::new(ThoughtState::default()));

    client.add_event_handler(
        |ev: StreamDeltaToDeviceEvent,
         sink: Ctx<Arc<dyn EventSink>>,
         feed: Ctx<TurnFeed>,
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

            let input = TurnInput::Answer {
                seq: content.seq,
                done: content.done,
                at_ms: now_ms(),
            };
            let payload = LivePayload {
                room_id: content.room_id,
                seq: content.seq,
                text: content.text,
                done: content.done,
            };
            let room_id = payload.room_id.clone();
            deliver(
                &sink,
                &feed.book,
                feed.generation,
                &room_id,
                input,
                CoreEvent::Live(payload),
            );
        },
    );

    client.add_event_handler(
        |ev: ThoughtDeltaToDeviceEvent,
         sink: Ctx<Arc<dyn EventSink>>,
         feed: Ctx<TurnFeed>,
         state: Ctx<std::sync::Arc<ThoughtState>>| async move {
            let content = ev.content;
            if !state.accept(
                &content.room_id,
                &content.session_id,
                content.seq,
                content.done,
            ) {
                return;
            }

            let input = TurnInput::Thought {
                seq: content.seq,
                text: content.text.clone(),
                done: content.done,
                at_ms: now_ms(),
            };
            let payload = LivePayload {
                room_id: content.room_id,
                seq: content.seq,
                text: content.text,
                done: content.done,
            };
            let room_id = payload.room_id.clone();
            deliver(
                &sink,
                &feed.book,
                feed.generation,
                &room_id,
                input,
                CoreEvent::Thought(payload),
            );
        },
    );

    // Not de-duplicated by `LiveState`, unlike the two text channels.
    //
    // `LiveState` forgets a room when a turn ends, and it learns that from a
    // delta's `done`. A tool update has no `done`: the turn ends elsewhere. So a
    // room's last tool sequence would outlive its turn, and the next turn —
    // whose counter restarts at 1 — would have every update dropped as stale.
    //
    // The turn book decides instead (`core::turns`): it knows where a turn ends
    // (a `done`, the agent's reply landing, or the time limit), so a report is
    // judged per tool call id against the turn it belongs to, and one it
    // refuses as stale is not forwarded at all.
    client.add_event_handler(
        |ev: ToolUpdateToDeviceEvent,
         sink: Ctx<Arc<dyn EventSink>>,
         feed: Ctx<TurnFeed>| async move {
            let content = ev.content;
            let payload = ToolPayload {
                room_id: content.room_id,
                seq: content.seq,
                tool_call_id: content.tool_call_id,
                title: content.title,
                kind: content.kind,
                phase: ToolPhase::from_status(&content.status),
                status_label: ToolPhase::from_status(&content.status).label().to_string(),
                status: content.status,
                locations: content.locations,
                input: bound_tool_text(content.input),
                output: bound_tool_text(content.output),
            };
            let input = TurnInput::Tool {
                step: TurnStep::from_payload(&payload),
                at_ms: now_ms(),
            };
            let room_id = payload.room_id.clone();
            deliver(
                &sink,
                &feed.book,
                feed.generation,
                &room_id,
                input,
                CoreEvent::Tool(payload),
            );
        },
    )
}

#[cfg(test)]
mod tool_phase_tests {
    use super::*;

    #[test]
    fn acp_statuses_become_reader_words() {
        assert_eq!(ToolPhase::from_status("pending"), ToolPhase::Queued);
        assert_eq!(ToolPhase::from_status("in_progress"), ToolPhase::Running);
        assert_eq!(ToolPhase::from_status("completed"), ToolPhase::Done);
        assert_eq!(ToolPhase::from_status("failed"), ToolPhase::Failed);
        assert_eq!(ToolPhase::from_status("in_progress").label(), "Running");
        assert_eq!(ToolPhase::from_status("completed").label(), "Done");
    }

    #[test]
    fn an_unknown_status_is_neutral_and_never_shown_raw() {
        let phase = ToolPhase::from_status("paused_for_budget");
        assert_eq!(phase, ToolPhase::Unknown);
        assert!(!phase.label().contains('_'));
    }
}

#[cfg(test)]
mod activity_tests {
    use super::*;

    /// The reasoning channel keeps its own record of what a room has shown.
    ///
    /// `add_event_handler_context` keys contexts by type, so a second
    /// `Arc<LiveState>` would have been *the same* map: an agent answering and
    /// thinking at once writes both channels for one room, and each delta would
    /// have evicted the other's sequence number. Roughly half of both streams
    /// would vanish, and the half that survived would look like a network
    /// problem rather than a bug.
    #[test]
    fn the_two_text_channels_do_not_evict_each_other() {
        let answer = LiveState::default();
        let thought = ThoughtState::default();

        assert!(answer.accept("!r", "s1", 1, false));
        assert!(answer.accept("!r", "s1", 2, false));

        // The reasoning channel has seen nothing for this room, so its own
        // first delta is news even though the answer is already at seq 2.
        assert!(thought.accept("!r", "s1", 1, false));
        assert!(thought.accept("!r", "s1", 2, false));

        // And neither has been set back by the other.
        assert!(!answer.accept("!r", "s1", 2, false));
        assert!(!thought.accept("!r", "s1", 2, false));
    }

    #[test]
    fn reasoning_follows_the_same_staleness_rules_as_an_answer() {
        let thought = ThoughtState::default();
        assert!(thought.accept("!r", "s1", 5, false));
        // Older: a duplicate the network delivered twice.
        assert!(!thought.accept("!r", "s1", 4, false));
        // A new turn restarts at 1, and is not stale.
        assert!(thought.accept("!r", "s2", 1, false));
    }

    #[test]
    fn a_finished_turn_is_forgotten_on_the_reasoning_channel_too() {
        let thought = ThoughtState::default();
        assert!(thought.accept("!r", "s1", 1, false));
        assert!(thought.accept("!r", "s1", 2, true));
        // Forgotten, so the same session's next turn starts clean rather than
        // being judged against a sequence that no longer means anything.
        assert!(thought.accept("!r", "s1", 1, false));
    }
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

#[cfg(test)]
mod tool_text_tests {
    use super::{bound_tool_text, TOOL_TEXT_MAX_CHARS};

    #[test]
    fn nothing_stays_nothing() {
        assert_eq!(bound_tool_text(None), None);
    }

    #[test]
    fn whitespace_is_nothing_too() {
        // A disclosure that opens on an empty box is worse than no
        // disclosure: it says there is something to see.
        assert_eq!(bound_tool_text(Some("   \n  ".into())), None);
    }

    #[test]
    fn ordinary_output_crosses_intact() {
        assert_eq!(
            bound_tool_text(Some("  exit 0\n2 files changed  ".into())),
            Some("exit 0\n2 files changed".to_string())
        );
    }

    #[test]
    fn a_whole_file_is_cut_and_says_so() {
        let huge = "x".repeat(TOOL_TEXT_MAX_CHARS + 500);
        let bounded = bound_tool_text(Some(huge)).expect("bounded text");
        assert_eq!(bounded.chars().count(), TOOL_TEXT_MAX_CHARS + 1);
        assert!(bounded.ends_with('…'), "the cut was silent");
    }

    #[test]
    fn text_exactly_at_the_bound_is_left_alone() {
        let exact = "y".repeat(TOOL_TEXT_MAX_CHARS);
        assert_eq!(bound_tool_text(Some(exact.clone())), Some(exact));
    }
}

#[cfg(test)]
mod deliver_tests {
    use std::sync::Mutex as StdMutex;

    use super::*;
    use crate::live::ToolPhase;

    #[derive(Default)]
    struct Recorder(StdMutex<Vec<&'static str>>);

    impl EventSink for Recorder {
        fn emit(&self, event: CoreEvent) {
            let name = match event {
                CoreEvent::Turn(_) => "turn",
                CoreEvent::Live(_) => "live",
                CoreEvent::Thought(_) => "thought",
                CoreEvent::Tool(_) => "tool",
                _ => "other",
            };
            self.0.lock().unwrap().push(name);
        }
    }

    fn recorder() -> (Arc<Recorder>, Arc<dyn EventSink>) {
        let rec = Arc::new(Recorder::default());
        let sink: Arc<dyn EventSink> = rec.clone();
        (rec, sink)
    }

    fn payload(seq: u64) -> LivePayload {
        LivePayload {
            room_id: "!r:x".into(),
            seq,
            text: "hi".into(),
            done: false,
        }
    }

    fn tool_payload(seq: u64, phase: ToolPhase) -> ToolPayload {
        ToolPayload {
            room_id: "!r:x".into(),
            seq,
            tool_call_id: "t1".into(),
            title: "Read".into(),
            kind: None,
            status: String::new(),
            phase,
            status_label: String::new(),
            locations: Vec::new(),
            input: None,
            output: None,
        }
    }

    fn tool_input(p: &ToolPayload) -> TurnInput {
        TurnInput::Tool {
            step: TurnStep::from_payload(p),
            at_ms: 1,
        }
    }

    /// Mutation seen failing: emitting `raw` before the update fails the
    /// order assertion (`["live", "turn"]`).
    #[test]
    fn the_turn_update_goes_out_before_the_delta_that_caused_it() {
        let (rec, sink) = recorder();
        let book = TurnBook::default();
        deliver(
            &sink,
            &book,
            book.generation(),
            "!r:x",
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: 1,
            },
            CoreEvent::Live(payload(1)),
        );
        assert_eq!(*rec.0.lock().unwrap(), ["turn", "live"]);
        // A second delta changes nothing in the summary: the text only.
        deliver(
            &sink,
            &book,
            book.generation(),
            "!r:x",
            TurnInput::Answer {
                seq: 2,
                done: false,
                at_ms: 2,
            },
            CoreEvent::Live(payload(2)),
        );
        assert_eq!(*rec.0.lock().unwrap(), ["turn", "live", "live"]);
    }

    /// Mutation seen failing: emitting `raw` whatever the book said fails the
    /// assertion that nothing more was emitted.
    #[test]
    fn a_late_tool_report_reaches_no_host() {
        let (rec, sink) = recorder();
        let book = TurnBook::default();
        let generation = book.generation();
        let done = tool_payload(5, ToolPhase::Done);
        deliver(
            &sink,
            &book,
            generation,
            "!r:x",
            tool_input(&done),
            CoreEvent::Tool(done),
        );
        let seen = rec.0.lock().unwrap().len();
        let late = tool_payload(4, ToolPhase::Running);
        deliver(
            &sink,
            &book,
            generation,
            "!r:x",
            tool_input(&late),
            CoreEvent::Tool(late),
        );
        assert_eq!(rec.0.lock().unwrap().len(), seen, "a stale report went out");
    }

    /// New: the race the sent-this-session state had. A handler of the old
    /// client still running after sign-out and sign-in must reach neither the
    /// new book nor the host.
    ///
    /// Mutation seen failing: `deliver` calling `apply` (ignoring the
    /// generation) fails both assertions.
    #[test]
    fn a_late_event_from_the_old_sign_in_reaches_neither_book_nor_host() {
        let (rec, sink) = recorder();
        let book = TurnBook::default();
        let old = book.generation();
        book.clear();
        deliver(
            &sink,
            &book,
            old,
            "!r:x",
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: 1,
            },
            CoreEvent::Live(payload(1)),
        );
        assert!(rec.0.lock().unwrap().is_empty(), "the old session spoke");
        assert_eq!(book.snapshot("!r:x"), None, "the old session fed the book");
    }
}
