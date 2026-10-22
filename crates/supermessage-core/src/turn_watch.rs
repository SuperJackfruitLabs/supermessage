//! Feeding the turn book from what the focused timeline shows, and a clock.
//!
//! `core::live` feeds the book from the agent's own stream. A turn also ends
//! when the agent's message lands in the room, and that is only visible on the
//! timeline: an agent that answers by editing a progress message sends no
//! `done` at all. [`TurnSink`] watches the focused timeline's diffs for that.
//! A turn that never ends in either way is ended by [`spawn_sweeper`].

use std::sync::Arc;
use std::time::Duration;

use crate::custom_events::TURN_ACTIVITY_EVENT_TYPE;
use crate::dto::{DiffOp, TimelineRow};
use crate::event::{CoreEvent, EventSink};
use crate::item_view::agent_name_from_id;
use crate::turns::{now_ms, TurnBook, TurnInput};

/// How often the sweeper looks for turns past [`crate::turns::GIVE_UP_AFTER_MS`].
pub const SWEEP_EVERY: Duration = Duration::from_secs(30);

/// Whether this row is an agent saying its turn is over: a message from
/// someone else that is a turn card, or comes from an agent.
///
/// An agent is recognised by its id, or by a display name that differs from
/// its short name (the bridge's `(harness on host)` suffix). A person who is
/// merely speaking in the room does not end the agent's turn.
pub fn answers_a_turn(row: &TimelineRow) -> bool {
    let item = &row.item;
    if item.is_own {
        return false;
    }
    if item.detail.as_deref() == Some(TURN_ACTIVITY_EVENT_TYPE) {
        return true;
    }
    item.kind == "message"
        && (item
            .sender
            .as_deref()
            .and_then(agent_name_from_id)
            .is_some()
            || row.sender_name != row.sender_short)
}

/// A row that answers a turn, found in a timeline diff.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundReply {
    /// The row's own timestamp.
    pub event_ms: Option<u64>,
    /// The Matrix user who sent it.
    pub sender: Option<String>,
    /// Whether it came from loaded history (`Reset`) rather than arriving
    /// live. History is judged more strictly by the book.
    pub from_history: bool,
}

/// Every row in `ops` that answers a turn.
///
/// Rows that arrive (`PushBack`, `Append`, `Insert`) count, and so does a
/// `Set` of an edited row: a bridge that answers by editing its progress
/// message has no other signal. Older history (`PushFront`) never does.
///
/// A `Reset` is the room being opened (or the timeline starting over), so it
/// carries the agent's reply if the agent answered while the reader was
/// elsewhere. Its rows count only when they have a timestamp, and they are
/// marked `from_history`: the book then wants them stamped at or after the
/// turn's start (no backward skew) and from the turn's own writer, so the
/// previous turn's answer does not end a newer turn.
///
/// iOS has no counterpart: it discards an in-progress turn on leaving its room
/// and reopens clean. The core keeps the turn for every room, so on reopen it
/// has to learn what happened meanwhile.
pub fn replies_in(ops: &[DiffOp<TimelineRow>]) -> Vec<FoundReply> {
    let mut found = Vec::new();
    let mut take = |row: &TimelineRow, from_history: bool| {
        if answers_a_turn(row) {
            found.push(FoundReply {
                event_ms: row.item.timestamp_ms,
                sender: row.item.sender.clone(),
                from_history,
            });
        }
    };
    for op in ops {
        match op {
            DiffOp::Append { values } => values.iter().for_each(|r| take(r, false)),
            DiffOp::PushBack { value } | DiffOp::Insert { value, .. } => take(value, false),
            DiffOp::Set { value, .. } if value.item.edited => take(value, false),
            DiffOp::Reset { values } => values
                .iter()
                .filter(|row| row.item.timestamp_ms.is_some())
                .for_each(|r| take(r, true)),
            _ => {}
        }
    }
    found
}

/// Wraps the focused timeline's sink: forwards every event, then tells the
/// turn book about the agent replies in a timeline diff and emits the turn
/// updates that follow, so a host sees the row before the turn ending.
pub struct TurnSink {
    inner: Arc<dyn EventSink>,
    room_id: String,
    turns: Arc<TurnBook>,
    /// The book's generation when this sink was made; a sink that outlives a
    /// sign-out writes nothing into the next one.
    generation: u64,
}

impl TurnSink {
    pub fn wrap(
        inner: Arc<dyn EventSink>,
        room_id: &str,
        turns: Arc<TurnBook>,
    ) -> Arc<dyn EventSink> {
        let generation = turns.generation();
        Arc::new(Self {
            inner,
            room_id: room_id.to_owned(),
            turns,
            generation,
        })
    }
}

impl EventSink for TurnSink {
    fn emit(&self, event: CoreEvent) {
        let replies = match &event {
            CoreEvent::TimelineDiff(envelope) => replies_in(&envelope.ops),
            _ => Vec::new(),
        };
        self.inner.emit(event);
        for reply in replies {
            let at_ms = now_ms();
            let outcome = match (reply.from_history, reply.event_ms) {
                (true, Some(event_ms)) => self.turns.apply_history_reply_in(
                    self.generation,
                    &self.room_id,
                    reply.sender.as_deref(),
                    event_ms,
                    at_ms,
                ),
                _ => self.turns.apply_in(
                    self.generation,
                    &self.room_id,
                    TurnInput::Reply {
                        event_ms: reply.event_ms,
                        at_ms,
                    },
                ),
            };
            if let Some(update) = outcome.update {
                self.inner.emit(CoreEvent::Turn(update));
            }
        }
    }
}

/// Every [`SWEEP_EVERY`], ends the turns that have been silent too long and
/// tells the host. Abort the handle to stop it.
pub fn spawn_sweeper(
    turns: Arc<TurnBook>,
    sink: Arc<dyn EventSink>,
) -> tokio::task::JoinHandle<()> {
    sweep_every(SWEEP_EVERY, turns, sink)
}

fn sweep_every(
    period: Duration,
    turns: Arc<TurnBook>,
    sink: Arc<dyn EventSink>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(period);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        // The first tick is immediate; nothing can have expired yet.
        tick.tick().await;
        loop {
            tick.tick().await;
            for update in turns.expire(now_ms()) {
                sink.emit(CoreEvent::Turn(update));
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;
    use crate::dto::DiffEnvelope;
    use crate::timeline::project_item_parts;
    use crate::turns::TurnEnding;

    const ROOM: &str = "!r:x";
    const T: u64 = 1_000_000;

    #[derive(Default)]
    struct Recorded(Mutex<Vec<&'static str>>);

    impl EventSink for Recorded {
        fn emit(&self, event: CoreEvent) {
            self.0.lock().unwrap().push(match event {
                CoreEvent::TimelineDiff(_) => "diff",
                CoreEvent::Turn(_) => "turn",
                _ => "other",
            });
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn row(
        kind: &str,
        detail: Option<&str>,
        sender: &str,
        name: &str,
        own: bool,
        edited: bool,
    ) -> TimelineRow {
        TimelineRow::new(project_item_parts(
            "$e",
            Some("$e"),
            kind,
            (kind == "message").then_some("m.text"),
            detail,
            Some(sender),
            Some(name),
            None,
            false,
            Some("hi"),
            None,
            None,
            None,
            Some(T + 1_000),
            own,
            None,
            None,
            edited,
            Vec::new(),
            Vec::new(),
        ))
    }

    fn agent() -> TimelineRow {
        row("message", None, "@agent_atlas:x", "Atlas", false, false)
    }

    fn diff(ops: Vec<DiffOp<TimelineRow>>) -> CoreEvent {
        CoreEvent::TimelineDiff(DiffEnvelope {
            channel: "sm://timeline/diff".into(),
            subject: ROOM.into(),
            seq: 1,
            ops,
        })
    }

    fn running_turn() -> Arc<TurnBook> {
        let book = Arc::new(TurnBook::default());
        book.apply(
            ROOM,
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: T,
            },
        );
        book
    }

    fn wrapped(book: &Arc<TurnBook>) -> (Arc<Recorded>, Arc<dyn EventSink>) {
        let rec = Arc::new(Recorded::default());
        let inner: Arc<dyn EventSink> = rec.clone();
        (rec, TurnSink::wrap(inner, ROOM, Arc::clone(book)))
    }

    fn ended_by(book: &TurnBook) -> Option<TurnEnding> {
        book.snapshot(ROOM).and_then(|s| s.summary.ended_by)
    }

    /// Mutation seen failing: dropping the `Reply` application in `emit`
    /// leaves `ended_by` None; emitting the update before the diff reverses
    /// the recorded order.
    #[test]
    fn an_agent_message_appended_ends_the_rooms_turn() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::PushBack { value: agent() }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff", "turn"]);
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// Review Focus 1.
    ///
    /// Mutation seen failing: dropping the `edited` condition on `Set` in
    /// `replies_in` fails `not_every_set_is_a_reply` below; removing the `Set`
    /// arm fails this test.
    #[test]
    fn an_edited_agent_message_ends_the_rooms_turn() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        let mut edited = agent();
        edited.item.edited = true;
        sink.emit(diff(vec![DiffOp::Set {
            index: 0,
            value: edited,
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff", "turn"]);
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// Mutation seen failing: dropping the `edited` condition ends the turn
    /// here (the brief's second mutation).
    #[test]
    fn not_every_set_is_a_reply() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Set {
            index: 0,
            value: agent(),
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert_eq!(ended_by(&book), None);
    }

    /// Review Focus 1.
    ///
    /// Mutation seen failing: dropping the turn-card branch of `answers_a_turn`
    /// leaves the turn running (a custom message is not a `message`).
    #[test]
    fn a_tool_only_turn_ends_when_its_turn_card_lands() {
        let book = running_turn();
        let (_rec, sink) = wrapped(&book);
        let card = row(
            "customMessage",
            Some(TURN_ACTIVITY_EVENT_TYPE),
            "@agent_atlas:x",
            "Atlas",
            false,
            false,
        );
        sink.emit(diff(vec![DiffOp::PushBack { value: card }]));
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// Review Focus 4.
    ///
    /// Mutation seen failing: answering for every non-own message ends the
    /// turn here.
    #[test]
    fn a_human_speaking_mid_turn_does_not_end_the_agents_turn() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        let human = row("message", None, "@rakesh:x", "Rakesh", false, false);
        sink.emit(diff(vec![DiffOp::PushBack { value: human }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM));
    }

    /// Mutation seen failing: dropping the `sender_name != sender_short`
    /// branch leaves the turn running (a bridge user without the agent_ id).
    #[test]
    fn a_bridge_suffix_marks_an_agent_without_an_agent_id() {
        let mut bridged = row(
            "message",
            None,
            "@bridge:x",
            "Atlas (claude on host)",
            false,
            false,
        );
        bridged.sender_short = "Atlas".into();
        assert!(answers_a_turn(&bridged));
        let mut own = bridged.clone();
        own.item.is_own = true;
        assert!(!answers_a_turn(&own), "the reader's own message");
    }

    /// Mutation seen failing: treating `PushFront` like `PushBack` ends the
    /// turn.
    #[test]
    fn older_history_does_not_end_a_turn() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::PushFront { value: agent() }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM));
    }

    fn agent_at(ms: u64) -> TimelineRow {
        let mut r = agent();
        r.item.timestamp_ms = Some(ms);
        r
    }

    /// C3 review minor 1: the agent answered (by an edit, no `done`) while
    /// the reader was in another room; opening the room loads the reply.
    ///
    /// Mutation seen failing: dropping the `Reset` arm of `replies_in` leaves
    /// the turn running.
    #[test]
    fn opening_a_room_whose_agent_answered_meanwhile_ends_the_turn() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![agent_at(T + 5_000)],
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff", "turn"]);
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// Mutation seen failing (run): dropping the `started_at_ms` check on a
    /// history reply fails this with `left: ["diff", "turn"], right:
    /// ["diff"]`. (The 60 s skew on a *live* reply is pinned by
    /// `turns::a_reply_from_before_the_turn_does_not_end_it`, whose mutant was
    /// run too.)
    #[test]
    fn opening_a_room_with_only_an_older_answer_leaves_the_turn_running() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![agent_at(T - 10 * 60_000)],
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM));
    }

    /// The previous turn's answer, stamped 30 s before this turn began, is
    /// inside the book's 60 s skew for a reply that *arrives*. In loaded
    /// history it is the old turn's answer and must not end this one.
    ///
    /// Mutation seen failing (run): judging `Reset` rows by the book's 60 s skew
    /// (or by nothing) fails this with `left: ["diff", "turn"], right:
    /// ["diff"]`; so does routing `Reset` rows through the live `Reply` path.
    #[test]
    fn a_previous_turns_reply_30s_before_this_one_started_does_not_end_it() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![agent_at(T - 30_000)],
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM), "the old answer ended the new turn");
    }

    /// The same row arriving live (not history) is inside the skew and does
    /// end the turn: the skew is for replies that arrive during a turn.
    ///
    /// Mutation seen failing (run): marking every row `from_history` fails this
    /// with `left: None, right: Some(Reply)`.
    #[test]
    fn a_live_reply_stamped_30s_early_still_ends_the_turn() {
        let book = running_turn();
        let (_rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::PushBack {
            value: agent_at(T - 30_000),
        }]));
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// One second after the turn began is this turn's answer.
    ///
    /// Mutation seen failing (run): requiring the reply to be 10 s after the
    /// start fails this with `left: ["diff"], right: ["diff", "turn"]`.
    #[test]
    fn a_reset_reply_1s_after_the_turn_started_ends_it() {
        let book = running_turn();
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![agent_at(T + 1_000)],
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff", "turn"]);
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    fn from(sender: &str, ms: u64) -> TimelineRow {
        let mut r = row("message", None, sender, "Atlas", false, false);
        r.item.timestamp_ms = Some(ms);
        r
    }

    fn running_turn_by(writer: &str) -> Arc<TurnBook> {
        let book = Arc::new(TurnBook::default());
        book.apply_from(
            ROOM,
            writer,
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: T,
            },
        );
        book
    }

    /// Another agent's message in the reopened room is not this agent's
    /// answer, even when it is dated after the turn began.
    ///
    /// Mutation seen failing (run): dropping the writer check fails this with
    /// `left: ["diff", "turn"], right: ["diff"]`.
    #[test]
    fn a_reset_row_from_another_agent_does_not_end_the_turn() {
        let book = running_turn_by("@agent_atlas:x");
        let (rec, sink) = wrapped(&book);
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![from("@agent_quill:x", T + 1_000)],
        }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM));
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![from("@agent_atlas:x", T + 1_000)],
        }]));
        assert_eq!(ended_by(&book), Some(TurnEnding::Reply));
    }

    /// Mutation seen failing: dropping the timestamp filter on `Reset` rows
    /// ends the turn on a row with no time.
    #[test]
    fn a_reset_row_with_no_time_does_not_end_a_turn() {
        let book = running_turn();
        let (_rec, sink) = wrapped(&book);
        let mut undated = agent();
        undated.item.timestamp_ms = None;
        sink.emit(diff(vec![DiffOp::Reset {
            values: vec![undated],
        }]));
        assert!(book.in_progress(ROOM));
    }

    /// New: the late-writer race. A sink made for the previous sign-in must
    /// not write into the cleared book.
    ///
    /// Mutation seen failing: `emit` passing a generation other than the one
    /// it was made with fails this test (and the three that end a turn).
    #[test]
    fn a_timeline_sink_from_the_old_sign_in_writes_nothing() {
        let book = running_turn();
        let (rec, old_sink) = wrapped(&book);
        book.clear();
        book.apply(
            ROOM,
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: T + 5,
            },
        );
        old_sink.emit(diff(vec![DiffOp::PushBack { value: agent() }]));
        assert_eq!(*rec.0.lock().unwrap(), ["diff"]);
        assert!(book.in_progress(ROOM), "the old sink ended the new turn");
    }

    /// Runs on tokio's paused clock, so no real time passes and a loaded
    /// machine cannot make it flake.
    ///
    /// Mutation seen failing: sweeping with `now_ms` of zero (never due)
    /// leaves the turn running; a loop that never ticks leaves the sink
    /// empty.
    #[tokio::test(start_paused = true)]
    async fn the_sweeper_ends_a_turn_and_tells_the_host() {
        let book = Arc::new(TurnBook::default());
        // Activity far in the past, so the first sweep finds it due.
        book.apply(
            ROOM,
            TurnInput::Answer {
                seq: 1,
                done: false,
                at_ms: 1,
            },
        );
        let rec = Arc::new(Recorded::default());
        let sink: Arc<dyn EventSink> = rec.clone();
        let period = Duration::from_secs(30);
        let task = sweep_every(period, Arc::clone(&book), sink);
        // Let the task reach its first (immediate) tick, then its wait.
        tokio::task::yield_now().await;
        assert!(rec.0.lock().unwrap().is_empty(), "swept before the period");
        tokio::time::advance(period).await;
        tokio::task::yield_now().await;
        assert_eq!(*rec.0.lock().unwrap(), ["turn"]);
        assert_eq!(ended_by(&book), Some(TurnEnding::TimedOut));
        task.abort();
    }
}
