//! The core's record of each room's agent turn, for every room.
//!
//! A host used to rebuild this from `live`, `thought` and `tool` events for
//! the one room it had focused (iOS `LiveStore`). The book keeps one turn per
//! room, in the core, so the rules are written once and a room that is not on
//! screen still knows whether its agent is working.
//!
//! Every input carries an explicit `at_ms`, so the rules are pure and
//! testable; the wiring supplies [`now_ms`].
//!
//! Text is not kept here beyond the thought (for a snapshot): answer and
//! thought text keep travelling on the `Live` and `Thought` events, and an
//! update carries a summary plus at most one step.

use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, PoisonError};

use serde::{Deserialize, Serialize};

use crate::live::{ToolPayload, ToolPhase};
use crate::turn_label::turn_record_label;

/// Tauri event channel carrying a turn update to the webview.
pub const TURN_EVENT: &str = "sm://turn";

/// How many rooms' finished records are kept. Past this, the room whose turn
/// finished longest ago is forgotten. In-progress turns are never evicted by
/// count; [`GIVE_UP_AFTER_MS`] bounds them.
pub const KEPT_RECORDS: usize = 20;

/// How long a turn may say nothing before it is called over.
///
/// The owner's decision (O1): 30 minutes. It has no iOS counterpart. A turn
/// whose agent crashed, or whose `done` the hub skipped on a tool-only turn,
/// would otherwise say "Working" forever.
pub const GIVE_UP_AFTER_MS: u64 = 30 * 60 * 1000;

/// How far before a turn started an agent message may be stamped and still be
/// that turn's answer. Clocks differ between the bridge and the reader.
pub const REPLY_SKEW_MS: u64 = 60_000;

/// One tool call, as a turn's step.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnStep {
    pub id: String,
    pub title: String,
    pub kind: Option<String>,
    /// ACP's raw status. Not for display; see `phase` and `status_label`.
    pub status: String,
    pub phase: ToolPhase,
    pub status_label: String,
    pub locations: Vec<String>,
    pub input: Option<String>,
    pub output: Option<String>,
    /// The call's sequence; a report at or below the last one is dropped.
    pub seq: u64,
    /// Whether there is anything to open the row onto.
    pub has_detail: bool,
}

impl TurnStep {
    pub fn from_payload(p: &ToolPayload) -> Self {
        Self {
            id: p.tool_call_id.clone(),
            title: p.title.clone(),
            kind: p.kind.clone(),
            status: p.status.clone(),
            phase: p.phase,
            status_label: p.status_label.clone(),
            locations: p.locations.clone(),
            input: p.input.clone(),
            output: p.output.clone(),
            seq: p.seq,
            has_detail: p.input.is_some() || p.output.is_some() || !p.locations.is_empty(),
        }
    }
}

/// Something that happened to a turn, and when (the reader's clock).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Enum)]
#[serde(
    tag = "kind",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum TurnInput {
    Answer {
        seq: u64,
        done: bool,
        at_ms: u64,
    },
    Thought {
        seq: u64,
        text: String,
        done: bool,
        at_ms: u64,
    },
    Tool {
        step: TurnStep,
        at_ms: u64,
    },
    /// An agent's message landed in the room. `event_ms` is its own timestamp.
    Reply {
        event_ms: Option<u64>,
        at_ms: u64,
    },
}

/// What ended a turn.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum TurnEnding {
    /// The agent said it was done.
    Done,
    /// The agent's message landed in the room.
    Reply,
    /// Nothing was heard for [`GIVE_UP_AFTER_MS`].
    TimedOut,
}

/// A turn in few words: everything a row or header needs, no text.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnSummary {
    pub room_id: String,
    /// New for each turn in the book.
    pub turn_id: u64,
    /// Moves forward on every update the book emits; a host discards anything
    /// older than what it holds.
    pub revision: u64,
    pub in_progress: bool,
    pub ended_by: Option<TurnEnding>,
    pub started_at_ms: u64,
    pub ended_at_ms: Option<u64>,
    /// Not sent on its own: a growing thought moves this and emits nothing.
    pub last_activity_ms: u64,
    pub answering: bool,
    pub has_thought: bool,
    pub total_steps: u32,
    pub completed_steps: u32,
    pub failed_step: Option<String>,
    pub current_step_title: String,
    pub step_is_tool: bool,
    /// The collapsed record's label, once the turn has ended and has a thought
    /// or a step.
    pub record_label: Option<String>,
}

/// One step that changed. `index == len` of the steps before it means append.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnStepChange {
    pub index: u32,
    pub step: TurnStep,
}

/// What a host is told when a turn moves.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnUpdate {
    pub summary: TurnSummary,
    pub step: Option<TurnStepChange>,
}

/// The whole record, for a host opening a room.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnSnapshot {
    pub summary: TurnSummary,
    pub steps: Vec<TurnStep>,
    pub thought: Option<String>,
}

/// The result of [`TurnBook::apply`]. Core-internal.
#[derive(Debug, Clone, PartialEq)]
pub struct TurnOutcome {
    /// False when the input was dropped as stale; the caller then does not
    /// forward the raw event either.
    pub accepted: bool,
    /// Present only when the summary changed or a step changed.
    pub update: Option<TurnUpdate>,
}

impl TurnOutcome {
    fn accepted(update: Option<TurnUpdate>) -> Self {
        Self {
            accepted: true,
            update,
        }
    }
}

#[derive(Debug)]
struct TurnState {
    turn_id: u64,
    revision: u64,
    started_at_ms: u64,
    ended_at_ms: Option<u64>,
    last_activity_ms: u64,
    ended_by: Option<TurnEnding>,
    answering: bool,
    thought: Option<String>,
    thought_seq: u64,
    steps: Vec<TurnStep>,
}

impl TurnState {
    fn in_progress(&self) -> bool {
        self.ended_by.is_none()
    }

    fn summary(&self, room_id: &str) -> TurnSummary {
        let has_thought = self.thought.is_some();
        let running = self
            .steps
            .iter()
            .rev()
            .find(|s| s.phase == ToolPhase::Running);
        let queued = self
            .steps
            .iter()
            .rev()
            .find(|s| s.phase == ToolPhase::Queued);
        let (current_step_title, step_is_tool) = match running.or(queued) {
            Some(step) => (step.title.clone(), true),
            None if self.answering => ("Writing the answer".to_string(), false),
            None if has_thought => ("Thinking".to_string(), false),
            None => ("Starting".to_string(), false),
        };
        let total_steps = self.steps.len() as u32;
        let record_label = match self.ended_at_ms {
            Some(ended) if has_thought || total_steps > 0 => Some(turn_record_label(
                has_thought,
                total_steps,
                Some(ended.saturating_sub(self.started_at_ms)),
            )),
            _ => None,
        };
        TurnSummary {
            room_id: room_id.to_string(),
            turn_id: self.turn_id,
            revision: self.revision,
            in_progress: self.in_progress(),
            ended_by: self.ended_by,
            started_at_ms: self.started_at_ms,
            ended_at_ms: self.ended_at_ms,
            last_activity_ms: self.last_activity_ms,
            answering: self.answering,
            has_thought,
            total_steps,
            completed_steps: self
                .steps
                .iter()
                .filter(|s| s.phase == ToolPhase::Done)
                .count() as u32,
            failed_step: self
                .steps
                .iter()
                .rev()
                .find(|s| s.phase == ToolPhase::Failed)
                .map(|s| s.title.clone()),
            current_step_title,
            step_is_tool,
            record_label,
        }
    }
}

#[derive(Default)]
struct Inner {
    rooms: HashMap<String, TurnState>,
    /// Rooms whose turn has finished, oldest first, for retention.
    finished_order: VecDeque<String>,
    next_turn_id: u64,
    revision: u64,
}

impl Inner {
    /// The summary without the parts that move on every delta, for deciding
    /// whether anything is news.
    fn shape(&self, room_id: &str) -> Option<TurnSummary> {
        self.rooms.get(room_id).map(|state| {
            let mut summary = state.summary(room_id);
            summary.revision = 0;
            summary.last_activity_ms = 0;
            summary
        })
    }

    fn is_in_progress(&self, room_id: &str) -> bool {
        self.rooms.get(room_id).is_some_and(TurnState::in_progress)
    }

    /// A non-done input for a room with no turn, or a finished one, starts a
    /// new turn and drops the old record.
    fn begin_if_needed(&mut self, room_id: &str, at_ms: u64) {
        if self.is_in_progress(room_id) {
            return;
        }
        self.finished_order.retain(|r| r != room_id);
        self.next_turn_id += 1;
        self.rooms.insert(
            room_id.to_string(),
            TurnState {
                turn_id: self.next_turn_id,
                revision: 0,
                started_at_ms: at_ms,
                ended_at_ms: None,
                last_activity_ms: at_ms,
                ended_by: None,
                answering: false,
                thought: None,
                thought_seq: 0,
                steps: Vec::new(),
            },
        );
    }

    /// Ends the room's turn, the first time only, and applies retention.
    fn end(&mut self, room_id: &str, ending: TurnEnding, at_ms: u64) {
        let Some(state) = self.rooms.get_mut(room_id) else {
            return;
        };
        if !state.in_progress() {
            return;
        }
        state.ended_by = Some(ending);
        state.ended_at_ms = Some(at_ms);
        self.finished_order.push_back(room_id.to_string());
        while self.finished_order.len() > KEPT_RECORDS {
            if let Some(oldest) = self.finished_order.pop_front() {
                self.rooms.remove(&oldest);
            }
        }
    }

    /// Stamps a new revision and builds the update, when there is news.
    fn update_if_news(
        &mut self,
        room_id: &str,
        before: Option<TurnSummary>,
        step: Option<TurnStepChange>,
    ) -> Option<TurnUpdate> {
        let after = self.shape(room_id);
        if before == after && step.is_none() {
            return None;
        }
        self.revision += 1;
        let state = self.rooms.get_mut(room_id)?;
        state.revision = self.revision;
        Some(TurnUpdate {
            summary: state.summary(room_id),
            step,
        })
    }

    fn apply(&mut self, room_id: &str, input: TurnInput) -> TurnOutcome {
        let before = self.shape(room_id);
        let mut step_change = None;
        match input {
            TurnInput::Reply { event_ms, at_ms } => {
                let Some(state) = self.rooms.get(room_id).filter(|s| s.in_progress()) else {
                    return TurnOutcome::accepted(None);
                };
                if event_ms.is_some_and(|e| e.saturating_add(REPLY_SKEW_MS) < state.started_at_ms) {
                    return TurnOutcome::accepted(None);
                }
                self.end(room_id, TurnEnding::Reply, at_ms);
            }
            TurnInput::Answer {
                done: true, at_ms, ..
            } => {
                if !self.is_in_progress(room_id) {
                    return TurnOutcome::accepted(None);
                }
                if let Some(state) = self.rooms.get_mut(room_id) {
                    state.answering = false;
                }
                self.end(room_id, TurnEnding::Done, at_ms);
            }
            TurnInput::Thought {
                done: true, at_ms, ..
            } => {
                if !self.is_in_progress(room_id) {
                    return TurnOutcome::accepted(None);
                }
                // The text stays: reasoning that vanishes on `done` is
                // reasoning nobody has had time to read.
                self.end(room_id, TurnEnding::Done, at_ms);
            }
            TurnInput::Answer { at_ms, .. } => {
                self.begin_if_needed(room_id, at_ms);
                if let Some(state) = self.rooms.get_mut(room_id) {
                    state.last_activity_ms = at_ms;
                    state.answering = true;
                }
            }
            TurnInput::Thought {
                seq, text, at_ms, ..
            } => {
                self.begin_if_needed(room_id, at_ms);
                if let Some(state) = self.rooms.get_mut(room_id) {
                    state.last_activity_ms = at_ms;
                    if seq >= state.thought_seq {
                        state.thought_seq = seq;
                        state.thought = Some(text);
                    }
                }
            }
            TurnInput::Tool { step, at_ms } => {
                // Network order is not send order: a report at or below the
                // last one for this call is older news.
                let stale = self
                    .rooms
                    .get(room_id)
                    .filter(|s| s.in_progress())
                    .and_then(|s| s.steps.iter().find(|x| x.id == step.id))
                    .is_some_and(|known| step.seq <= known.seq);
                if stale {
                    return TurnOutcome {
                        accepted: false,
                        update: None,
                    };
                }
                self.begin_if_needed(room_id, at_ms);
                if let Some(state) = self.rooms.get_mut(room_id) {
                    state.last_activity_ms = at_ms;
                    let index = match state.steps.iter().position(|x| x.id == step.id) {
                        Some(index) => {
                            state.steps[index] = step.clone();
                            index
                        }
                        None => {
                            state.steps.push(step.clone());
                            state.steps.len() - 1
                        }
                    };
                    step_change = Some(TurnStepChange {
                        index: index as u32,
                        step,
                    });
                }
            }
        }
        TurnOutcome::accepted(self.update_if_news(room_id, before, step_change))
    }
}

/// One turn per room, for every room. Session-owned: it survives client
/// rebuilds, so sign-out must [`clear`](Self::clear) it.
#[derive(Default)]
pub struct TurnBook {
    inner: Mutex<Inner>,
}

impl TurnBook {
    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub fn apply(&self, room_id: &str, input: TurnInput) -> TurnOutcome {
        self.lock().apply(room_id, input)
    }

    /// Ends every in-progress turn that has been silent for
    /// [`GIVE_UP_AFTER_MS`], at its last activity.
    pub fn expire(&self, now_ms: u64) -> Vec<TurnUpdate> {
        let mut inner = self.lock();
        let mut due: Vec<(String, u64)> = inner
            .rooms
            .iter()
            .filter(|(_, s)| {
                s.in_progress() && now_ms.saturating_sub(s.last_activity_ms) >= GIVE_UP_AFTER_MS
            })
            .map(|(room, s)| (room.clone(), s.last_activity_ms))
            .collect();
        due.sort();
        let mut updates = Vec::new();
        for (room, last) in due {
            let before = inner.shape(&room);
            inner.end(&room, TurnEnding::TimedOut, last);
            updates.extend(inner.update_if_news(&room, before, None));
        }
        updates
    }

    pub fn snapshot(&self, room_id: &str) -> Option<TurnSnapshot> {
        let inner = self.lock();
        let state = inner.rooms.get(room_id)?;
        Some(TurnSnapshot {
            summary: state.summary(room_id),
            steps: state.steps.clone(),
            thought: state.thought.clone(),
        })
    }

    /// Whether the room's agent is mid-turn (for the roster's room status).
    pub fn in_progress(&self, room_id: &str) -> bool {
        self.lock().is_in_progress(room_id)
    }

    /// Forgets every room. Counters keep counting, so a host holding a
    /// pre-clear snapshot can still tell it is older.
    pub fn clear(&self) {
        let mut inner = self.lock();
        inner.rooms.clear();
        inner.finished_order.clear();
    }
}

/// Folds `inputs` through a fresh book. For fixtures and previews.
pub fn fold_turn(room_id: &str, inputs: Vec<TurnInput>) -> Option<TurnSnapshot> {
    let book = TurnBook::default();
    for input in inputs {
        book.apply(room_id, input);
    }
    book.snapshot(room_id)
}

/// Milliseconds since the Unix epoch on the reader's clock.
pub fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROOM: &str = "!r:x.org";
    const T: u64 = 1_000_000;

    fn answer(at_ms: u64) -> TurnInput {
        TurnInput::Answer {
            seq: 1,
            done: false,
            at_ms,
        }
    }

    fn answer_done(at_ms: u64) -> TurnInput {
        TurnInput::Answer {
            seq: 2,
            done: true,
            at_ms,
        }
    }

    fn thought(seq: u64, text: &str, at_ms: u64) -> TurnInput {
        TurnInput::Thought {
            seq,
            text: text.into(),
            done: false,
            at_ms,
        }
    }

    fn thought_done(at_ms: u64) -> TurnInput {
        TurnInput::Thought {
            seq: 99,
            text: String::new(),
            done: true,
            at_ms,
        }
    }

    fn step(id: &str, title: &str, phase: ToolPhase, seq: u64) -> TurnStep {
        let status = match phase {
            ToolPhase::Queued => "pending",
            ToolPhase::Running => "in_progress",
            ToolPhase::Done => "completed",
            ToolPhase::Failed => "failed",
            ToolPhase::Unknown => "mystery",
        };
        TurnStep {
            id: id.into(),
            title: title.into(),
            kind: None,
            status: status.into(),
            phase,
            status_label: phase.label().into(),
            locations: vec![],
            input: None,
            output: None,
            seq,
            has_detail: false,
        }
    }

    fn tool(id: &str, title: &str, phase: ToolPhase, seq: u64, at_ms: u64) -> TurnInput {
        TurnInput::Tool {
            step: step(id, title, phase, seq),
            at_ms,
        }
    }

    fn reply(event_ms: Option<u64>, at_ms: u64) -> TurnInput {
        TurnInput::Reply { event_ms, at_ms }
    }

    fn book_with(room: &str, inputs: Vec<TurnInput>) -> TurnBook {
        let book = TurnBook::default();
        for input in inputs {
            book.apply(room, input);
        }
        book
    }

    fn summary(book: &TurnBook) -> TurnSummary {
        book.snapshot(ROOM).expect("a turn").summary
    }

    /// Ported from `ComposerRevampTests.swift` (elapsed runs from the first
    /// delta and stops at the end).
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn elapsed_runs_from_the_first_delta_and_stops_at_the_end() {
        let book = book_with(ROOM, vec![thought(1, "hm", T), answer(T + 30_000)]);
        let s = summary(&book);
        assert_eq!(s.started_at_ms, T);
        assert_eq!(s.last_activity_ms, T + 30_000);
        assert!(s.in_progress);
        assert_eq!(s.record_label, None);

        book.apply(ROOM, answer_done(T + 42_000));
        let s = summary(&book);
        assert_eq!(s.ended_at_ms, Some(T + 42_000));
        assert_eq!(s.ended_by, Some(TurnEnding::Done));
        assert_eq!(s.record_label.as_deref(), Some("Thought for 42s"));
    }

    /// Ported from `ComposerRevampTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn the_next_turn_starts_its_own_clock() {
        let book = book_with(ROOM, vec![answer(T), answer_done(T + 5_000)]);
        let first = summary(&book).turn_id;
        book.apply(ROOM, answer(T + 100_000));
        let s = summary(&book);
        assert_ne!(s.turn_id, first);
        assert_eq!(s.started_at_ms, T + 100_000);
        assert!(s.in_progress);
    }

    /// Ported from `ComposerRevampTests.swift`.
    ///
    /// Mutation seen failing: `completed_steps` counting `Failed` as well
    /// failed it (3 not 2).
    #[test]
    fn steps_the_running_one_how_many_are_done_the_failed_one() {
        let book = book_with(
            ROOM,
            vec![
                tool("1", "1", ToolPhase::Done, 1, T),
                tool("2", "2", ToolPhase::Running, 1, T + 1),
                tool("3", "3", ToolPhase::Failed, 1, T + 2),
                tool("4", "4", ToolPhase::Done, 1, T + 3),
            ],
        );
        let s = summary(&book);
        assert_eq!(s.current_step_title, "2");
        assert!(s.step_is_tool);
        assert_eq!(s.completed_steps, 2);
        assert_eq!(s.total_steps, 4);
        assert_eq!(s.failed_step.as_deref(), Some("3"));
        assert!(s.in_progress);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn reasoning_outlives_the_turn_that_produced_it() {
        let book = book_with(
            ROOM,
            vec![
                thought(1, "weighing it", T),
                answer(T + 1),
                answer_done(T + 2),
            ],
        );
        let snap = book.snapshot(ROOM).unwrap();
        assert_eq!(snap.thought.as_deref(), Some("weighing it"));
        assert!(snap.summary.has_thought);
        assert!(!snap.summary.in_progress);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: removing the `answering = false` on `Answer {
    /// done }` failed it.
    #[test]
    fn the_streamed_answer_goes_because_the_real_message_says_it_better() {
        let book = book_with(ROOM, vec![answer(T)]);
        assert!(summary(&book).answering);
        book.apply(ROOM, answer_done(T + 1));
        assert!(!summary(&book).answering);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn a_thoughts_own_done_does_not_hide_it_either() {
        let book = book_with(ROOM, vec![thought(1, "still here", T), thought_done(T + 1)]);
        let snap = book.snapshot(ROOM).unwrap();
        assert_eq!(snap.thought.as_deref(), Some("still here"));
        assert_eq!(snap.summary.ended_by, Some(TurnEnding::Done));
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn tool_calls_outlive_the_turn_too() {
        let book = book_with(
            ROOM,
            vec![tool("a", "Read", ToolPhase::Done, 1, T), answer_done(T + 1)],
        );
        let snap = book.snapshot(ROOM).unwrap();
        assert_eq!(snap.steps.len(), 1);
        assert_eq!(
            snap.summary.record_label.as_deref(),
            Some("1 step \u{b7} 1s")
        );
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn the_next_turn_replaces_the_last_ones_record() {
        let book = book_with(
            ROOM,
            vec![
                thought(1, "old", T),
                tool("a", "Read", ToolPhase::Done, 1, T + 1),
                answer_done(T + 2),
            ],
        );
        let old = summary(&book).turn_id;
        book.apply(ROOM, answer(T + 10));
        let snap = book.snapshot(ROOM).unwrap();
        assert_ne!(snap.summary.turn_id, old);
        assert!(snap.steps.is_empty());
        assert_eq!(snap.thought, None);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input). `from_payload` is not part of the stub, so this one
    /// passes there; no mutant run.
    #[test]
    fn a_tool_row_with_nothing_behind_it_does_not_pretend_otherwise() {
        let payload = |locations: Vec<String>| ToolPayload {
            room_id: ROOM.into(),
            seq: 1,
            tool_call_id: "a".into(),
            title: "Read".into(),
            kind: None,
            status: "completed".into(),
            phase: ToolPhase::Done,
            status_label: "Done".into(),
            locations,
            input: None,
            output: None,
        };
        assert!(!TurnStep::from_payload(&payload(vec![])).has_detail);
        assert!(TurnStep::from_payload(&payload(vec!["src/a.rs".into()])).has_detail);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: never matching an existing step id (always
    /// append) failed it.
    #[test]
    fn a_later_report_on_the_same_call_replaces_it_rather_than_stacking() {
        let book = TurnBook::default();
        let first = book
            .apply(ROOM, tool("a", "Test", ToolPhase::Running, 1, T))
            .update
            .unwrap();
        let mut done = step("a", "Test", ToolPhase::Done, 2);
        done.output = Some("3 passed".into());
        let second = book
            .apply(
                ROOM,
                TurnInput::Tool {
                    step: done,
                    at_ms: T + 1,
                },
            )
            .update
            .unwrap();
        assert_eq!(first.step.unwrap().index, 0);
        assert_eq!(second.step.unwrap().index, 0);
        let snap = book.snapshot(ROOM).unwrap();
        assert_eq!(snap.steps.len(), 1);
        assert_eq!(snap.steps[0].status, "completed");
        assert_eq!(snap.steps[0].output.as_deref(), Some("3 passed"));
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn a_finished_turns_record_survives_other_rooms_turns() {
        let book = book_with("!a:x.org", vec![answer(T), answer_done(T + 1)]);
        book.apply("!b:x.org", answer(T + 2));
        let a = book.snapshot("!a:x.org").unwrap();
        assert!(!a.summary.in_progress);
        assert!(book.snapshot("!b:x.org").unwrap().summary.in_progress);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn the_next_turn_replaces_the_kept_record() {
        let book = book_with(ROOM, vec![answer(T), answer_done(T + 1)]);
        let old = summary(&book);
        book.apply(ROOM, thought(1, "again", T + 50));
        let new = summary(&book);
        assert!(new.turn_id > old.turn_id);
        assert!(new.in_progress);
        assert_eq!(new.ended_by, None);
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: `clear` not emptying `rooms` failed it.
    #[test]
    fn signing_out_forgets_every_kept_record() {
        let book = book_with(ROOM, vec![answer(T), answer_done(T + 1)]);
        book.apply("!other:x.org", answer(T + 2));
        book.clear();
        assert_eq!(book.snapshot(ROOM), None);
        assert_eq!(book.snapshot("!other:x.org"), None);
        assert!(!book.in_progress("!other:x.org"));
    }

    /// Ported from `LiveStoreTests.swift`.
    ///
    /// Mutation seen failing: `>` to `>=` on the retention length failed it
    /// (room 2 gone).
    #[test]
    fn only_so_many_rooms_are_remembered() {
        let book = TurnBook::default();
        for i in 1..=21u64 {
            let room = format!("!r{i}:x.org");
            book.apply(&room, answer(T + i * 10));
            book.apply(&room, answer_done(T + i * 10 + 1));
        }
        assert_eq!(book.snapshot("!r1:x.org"), None);
        assert!(book.snapshot("!r2:x.org").is_some());
        assert!(book.snapshot("!r21:x.org").is_some());
        let kept = (1..=21)
            .filter(|i| book.snapshot(&format!("!r{i}:x.org")).is_some())
            .count();
        assert_eq!(kept, KEPT_RECORDS);
    }

    /// Inverted from iOS on purpose: `LiveStore.focus` threw an unfinished
    /// turn away when the reader left the room.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn a_turn_left_mid_way_is_kept_and_keeps_counting() {
        let book = book_with(ROOM, vec![answer(T)]);
        book.apply("!elsewhere:x.org", answer(T + 1));
        let s = summary(&book);
        assert!(s.in_progress);
        assert!(book.in_progress(ROOM));
    }

    /// Ported from `NotificationComposerTests.swift` (`LiveTurnSummary`), with
    /// the values of ruling 2.
    ///
    /// Mutation seen failing: `completed_steps` counting `Failed` as well
    /// failed it.
    #[test]
    fn the_running_tool_is_the_current_step_and_only_done_tools_are_counted() {
        let book = book_with(
            ROOM,
            vec![
                tool("1", "Read", ToolPhase::Done, 1, T),
                tool("2", "Run tests", ToolPhase::Running, 1, T + 1),
                tool("3", "Lint", ToolPhase::Failed, 1, T + 2),
                answer(T + 3),
                thought(1, "hm", T + 4),
            ],
        );
        let s = summary(&book);
        assert_eq!(s.current_step_title, "Run tests");
        assert_eq!(s.completed_steps, 1);
        assert_eq!(s.total_steps, 3);
    }

    /// Ported from `NotificationComposerTests.swift`, ruling 2's chain.
    ///
    /// Mutation seen failing: swapping the "Thinking" and "Writing the answer"
    /// arms failed it.
    #[test]
    fn with_no_tool_running_the_phase_of_the_turn_is_the_step() {
        let book = book_with(ROOM, vec![thought(1, "hm", T), answer(T + 1)]);
        assert_eq!(summary(&book).current_step_title, "Writing the answer");
        assert!(!summary(&book).step_is_tool);

        let book = book_with(ROOM, vec![thought(1, "hm", T)]);
        assert_eq!(summary(&book).current_step_title, "Thinking");

        let book = book_with(
            ROOM,
            vec![
                tool("q", "Queued call", ToolPhase::Queued, 1, T),
                answer(T + 1),
            ],
        );
        assert_eq!(summary(&book).current_step_title, "Queued call");
        assert!(summary(&book).step_is_tool);

        let book = book_with(ROOM, vec![tool("d", "Done call", ToolPhase::Done, 1, T)]);
        assert_eq!(summary(&book).current_step_title, "Starting");
    }

    /// New. The desktop's rule (`live.svelte.ts`), which also fixes iOS's
    /// "completed overtaken by in_progress".
    ///
    /// Mutation seen failing: `<=` to `<` on the staleness check failed it (the
    /// equal-seq case).
    #[test]
    fn a_tool_report_the_network_delivered_late_is_dropped() {
        let book = TurnBook::default();
        book.apply(ROOM, tool("a", "Test", ToolPhase::Done, 5, T));
        let late = book.apply(ROOM, tool("a", "Test", ToolPhase::Running, 4, T + 1));
        assert!(!late.accepted);
        assert_eq!(late.update, None);
        let same = book.apply(ROOM, tool("a", "Test", ToolPhase::Running, 5, T + 2));
        assert!(!same.accepted);
        assert_eq!(book.snapshot(ROOM).unwrap().steps[0].phase, ToolPhase::Done);
    }

    /// New. Text applies when `seq >= thought_seq`: a repeat at the same seq
    /// still lands, an older one does not.
    ///
    /// Mutation seen failing: `>=` to `>` on the thought seq failed it.
    #[test]
    fn a_thought_at_the_same_seq_lands_and_an_older_one_does_not() {
        let book = book_with(
            ROOM,
            vec![thought(3, "first", T), thought(3, "same", T + 1)],
        );
        assert_eq!(
            book.snapshot(ROOM).unwrap().thought.as_deref(),
            Some("same")
        );
        book.apply(ROOM, thought(2, "older", T + 2));
        assert_eq!(
            book.snapshot(ROOM).unwrap().thought.as_deref(),
            Some("same")
        );
    }

    /// New.
    ///
    /// Mutation seen failing: making `Answer { done }` call `begin_if_needed`
    /// failed it.
    #[test]
    fn a_done_with_no_turn_starts_nothing() {
        let book = TurnBook::default();
        let outcome = book.apply(ROOM, answer_done(T));
        assert!(outcome.accepted);
        assert_eq!(outcome.update, None);
        assert_eq!(book.snapshot(ROOM), None);
        assert_eq!(book.apply(ROOM, thought_done(T)).update, None);
        assert_eq!(book.snapshot(ROOM), None);
    }

    /// New. Text travels on `Live`/`Thought`, so a growing thought is not news.
    ///
    /// Mutation seen failing: leaving `last_activity_ms` in the compared shape
    /// failed it.
    #[test]
    fn a_growing_thought_sends_no_update() {
        let book = TurnBook::default();
        assert!(book.apply(ROOM, thought(1, "a", T)).update.is_some());
        assert_eq!(book.apply(ROOM, thought(2, "ab", T + 100)).update, None);
        assert_eq!(book.snapshot(ROOM).unwrap().thought.as_deref(), Some("ab"));
    }

    /// New (Review Focus 1). A bridge that answers by editing its progress
    /// message sends no live deltas: nothing starts and nothing hangs.
    ///
    /// Mutation seen failing: making `Reply` call `begin_if_needed` failed it.
    #[test]
    fn a_bridge_that_only_edits_its_progress_message_starts_no_turn() {
        let book = TurnBook::default();
        let outcome = book.apply(ROOM, reply(Some(T), T));
        assert_eq!(outcome.update, None);
        assert_eq!(book.snapshot(ROOM), None);
    }

    /// New (Review Focus 1).
    ///
    /// Mutation seen failing: ignoring `Reply` (no `end`) failed it.
    #[test]
    fn an_agent_reply_ends_a_turn_that_never_sent_done() {
        let book = TurnBook::default();
        book.apply(ROOM, tool("a", "Read", ToolPhase::Running, 1, T));
        let update = book.apply(ROOM, reply(Some(T), T + 5_000)).update.unwrap();
        assert_eq!(update.summary.ended_by, Some(TurnEnding::Reply));
        assert_eq!(update.summary.ended_at_ms, Some(T + 5_000));
        assert!(!update.summary.in_progress);
    }

    /// New. A message stamped more than the skew before the turn began is an
    /// earlier turn's.
    ///
    /// Mutation seen failing: `<` to `<=`, skew 59_999 and skew 60_001 each
    /// failed it.
    #[test]
    fn a_reply_from_before_the_turn_does_not_end_it() {
        let book = book_with(ROOM, vec![answer(T)]);
        assert_eq!(
            book.apply(ROOM, reply(Some(T - 60_001), T + 1)).update,
            None
        );
        assert!(book.in_progress(ROOM));
        let ended = book
            .apply(ROOM, reply(Some(T - 60_000), T + 2))
            .update
            .unwrap();
        assert_eq!(ended.summary.ended_by, Some(TurnEnding::Reply));
    }

    /// New (Review Focus 2). O1: thirty minutes.
    ///
    /// Mutation seen failing: `>=` to `>` in `expire` failed it, and so did
    /// ending at `now` instead of `last`.
    #[test]
    fn a_turn_silent_for_thirty_minutes_ends_at_its_last_activity() {
        let book = book_with(ROOM, vec![answer(T)]);
        let last = T;
        assert!(book.expire(last + GIVE_UP_AFTER_MS - 1).is_empty());
        assert!(book.in_progress(ROOM));
        let updates = book.expire(last + GIVE_UP_AFTER_MS);
        assert_eq!(updates.len(), 1);
        assert_eq!(updates[0].summary.ended_by, Some(TurnEnding::TimedOut));
        assert_eq!(updates[0].summary.ended_at_ms, Some(last));
        assert!(!book.in_progress(ROOM));
        assert!(book.expire(last + GIVE_UP_AFTER_MS * 2).is_empty());
    }

    /// New.
    ///
    /// Mutation seen failing: `fold_turn` folding no inputs failed it. (It
    /// passes in the RED run, where the book and the fold agree on nothing.)
    #[test]
    fn fold_turn_matches_the_book() {
        let inputs = vec![
            thought(1, "hm", T),
            tool("a", "Read", ToolPhase::Done, 1, T + 1),
            answer_done(T + 2),
        ];
        let book = book_with(ROOM, inputs.clone());
        assert_eq!(fold_turn(ROOM, inputs), book.snapshot(ROOM));
        assert_eq!(fold_turn(ROOM, vec![]), None);
    }

    /// New.
    ///
    /// Mutation seen failing: Seen failing in the RED run (book stubbed to
    /// ignore every input).
    #[test]
    fn revisions_only_move_forward() {
        let book = TurnBook::default();
        let mut seen = Vec::new();
        for (room, input) in [
            ("!a:x.org", answer(T)),
            ("!b:x.org", answer(T + 1)),
            ("!a:x.org", tool("t", "Read", ToolPhase::Running, 1, T + 2)),
            ("!a:x.org", answer_done(T + 3)),
        ] {
            seen.push(book.apply(room, input).update.unwrap().summary.revision);
        }
        assert!(seen.windows(2).all(|w| w[0] < w[1]), "{seen:?}");
        assert_eq!(book.snapshot("!a:x.org").unwrap().summary.revision, seen[3]);
        book.clear();
        let after = book
            .apply("!a:x.org", answer(T + 9))
            .update
            .unwrap()
            .summary
            .revision;
        assert!(after > seen[3]);
    }
}
