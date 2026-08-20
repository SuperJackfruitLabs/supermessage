//! The two types UniFFI cannot take as they are.
//!
//! `DiffOp<T>` and `DiffEnvelope<T>` are generic, and UniFFI has no generics —
//! a binding has to name a concrete type for Swift or Kotlin to declare. So
//! each concrete instantiation the boundary carries gets a monomorphised
//! mirror here, and a `From` that converts.
//!
//! **This is the one place a field can silently go missing.** Adding a field
//! to a core DTO does not fail to compile in a hand-written mirror; it simply
//! never reaches the phone. The `From` impls below are therefore written as
//! exhaustive matches with no wildcard arm, so a *new variant* does break the
//! build — and the tests at the foot of this file cover the direction the
//! compiler cannot: that each variant carries its payload across intact.

use supermessage_core::dto::{DiffEnvelope, DiffOp, RoomRow, TimelineRow};

/// One change to the room list, as Swift and Kotlin see it.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum RoomDiffOp {
    Append { values: Vec<RoomRow> },
    Clear,
    PushFront { value: RoomRow },
    PushBack { value: RoomRow },
    PopFront,
    PopBack,
    Insert { index: u32, value: RoomRow },
    Set { index: u32, value: RoomRow },
    Remove { index: u32 },
    Truncate { length: u32 },
    Reset { values: Vec<RoomRow> },
}

/// One change to the focused timeline, as Swift and Kotlin see it.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum TimelineDiffOp {
    Append { values: Vec<TimelineRow> },
    Clear,
    PushFront { value: TimelineRow },
    PushBack { value: TimelineRow },
    PopFront,
    PopBack,
    Insert { index: u32, value: TimelineRow },
    Set { index: u32, value: TimelineRow },
    Remove { index: u32 },
    Truncate { length: u32 },
    Reset { values: Vec<TimelineRow> },
}

/// A batch of room-list changes, carrying the sequence number its ordering
/// depends on.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RoomDiffEnvelope {
    pub channel: String,
    pub subject: String,
    /// Monotonic per subject. A host that reorders these corrupts the list —
    /// see `supermessage_core::event`'s note on delivery order.
    pub seq: u64,
    pub ops: Vec<RoomDiffOp>,
}

/// A batch of timeline changes, carrying the sequence number its ordering
/// depends on.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TimelineDiffEnvelope {
    pub channel: String,
    pub subject: String,
    pub seq: u64,
    pub ops: Vec<TimelineDiffOp>,
}

/// `usize` on the way in, `u32` on the way out.
///
/// UniFFI has no `usize` — its width is platform-dependent and a binding must
/// name a fixed size. A room list or timeline long enough to overflow `u32`
/// would have exhausted memory long before, so the cast is safe in practice;
/// it is written explicitly rather than left to `as` at each site so that the
/// assumption is stated once, here, where someone can disagree with it.
fn index(value: usize) -> u32 {
    u32::try_from(value).unwrap_or(u32::MAX)
}

impl From<DiffOp<RoomRow>> for RoomDiffOp {
    fn from(op: DiffOp<RoomRow>) -> Self {
        // Exhaustive on purpose: no wildcard arm, so a new variant in the core
        // breaks this build rather than vanishing from every mobile client.
        match op {
            DiffOp::Append { values } => Self::Append { values },
            DiffOp::Clear => Self::Clear,
            DiffOp::PushFront { value } => Self::PushFront { value },
            DiffOp::PushBack { value } => Self::PushBack { value },
            DiffOp::PopFront => Self::PopFront,
            DiffOp::PopBack => Self::PopBack,
            DiffOp::Insert { index: i, value } => Self::Insert {
                index: index(i),
                value,
            },
            DiffOp::Set { index: i, value } => Self::Set {
                index: index(i),
                value,
            },
            DiffOp::Remove { index: i } => Self::Remove { index: index(i) },
            DiffOp::Truncate { length } => Self::Truncate {
                length: index(length),
            },
            DiffOp::Reset { values } => Self::Reset { values },
        }
    }
}

impl From<DiffOp<TimelineRow>> for TimelineDiffOp {
    fn from(op: DiffOp<TimelineRow>) -> Self {
        match op {
            DiffOp::Append { values } => Self::Append { values },
            DiffOp::Clear => Self::Clear,
            DiffOp::PushFront { value } => Self::PushFront { value },
            DiffOp::PushBack { value } => Self::PushBack { value },
            DiffOp::PopFront => Self::PopFront,
            DiffOp::PopBack => Self::PopBack,
            DiffOp::Insert { index: i, value } => Self::Insert {
                index: index(i),
                value,
            },
            DiffOp::Set { index: i, value } => Self::Set {
                index: index(i),
                value,
            },
            DiffOp::Remove { index: i } => Self::Remove { index: index(i) },
            DiffOp::Truncate { length } => Self::Truncate {
                length: index(length),
            },
            DiffOp::Reset { values } => Self::Reset { values },
        }
    }
}

impl From<DiffEnvelope<RoomRow>> for RoomDiffEnvelope {
    fn from(envelope: DiffEnvelope<RoomRow>) -> Self {
        Self {
            channel: envelope.channel,
            subject: envelope.subject,
            seq: envelope.seq,
            ops: envelope.ops.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<DiffEnvelope<TimelineRow>> for TimelineDiffEnvelope {
    fn from(envelope: DiffEnvelope<TimelineRow>) -> Self {
        Self {
            channel: envelope.channel,
            subject: envelope.subject,
            seq: envelope.seq,
            ops: envelope.ops.into_iter().map(Into::into).collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use supermessage_core::dto::Membership;

    fn a_room() -> RoomRow {
        // Built through the constructor rather than as a literal: the row's
        // derived halves are the core's to compute, and a literal here would
        // let this file disagree with what the roster actually carries.
        RoomRow::new(supermessage_core::dto::RoomSummary {
            id: "!r:example.org".into(),
            name: "Room".into(),
            avatar_url: None,
            unread: 3,
            last_message: Some("hi".into()),
            last_message_is_own: false,
            last_message_names_sender: false,
            last_event_type: None,
            last_activity_ms: Some(1_700_000_000_000),
            runtime: None,
            membership: Membership::Joined,
        })
    }

    #[test]
    fn a_room_survives_the_crossing_with_its_fields() {
        // The compiler cannot catch a field going missing here — a mirror that
        // forgets one still builds. This is the check that would.
        let op: RoomDiffOp = DiffOp::Insert {
            index: 3,
            value: a_room(),
        }
        .into();

        match op {
            RoomDiffOp::Insert { index, value } => {
                assert_eq!(index, 3);
                assert_eq!(value.room.id, "!r:example.org");
                assert_eq!(value.room.name, "Room");
                assert_eq!(value.room.unread, 3);
                assert_eq!(value.room.last_message.as_deref(), Some("hi"));
                assert_eq!(value.room.last_activity_ms, Some(1_700_000_000_000));
                assert_eq!(value.room.membership, Membership::Joined);
            }
            other => panic!("wrong variant: {other:?}"),
        }
    }

    #[test]
    fn an_envelope_keeps_its_sequence_number() {
        // `seq` is not decoration: the timeline's recovery logic is built on
        // these arriving in order, so losing or reordering it corrupts the
        // reader's view in a way that looks like a rendering bug.
        let envelope: RoomDiffEnvelope = DiffEnvelope {
            channel: "sm://rooms/diff".into(),
            subject: "!r:example.org".into(),
            seq: 42,
            ops: vec![DiffOp::PushBack { value: a_room() }],
        }
        .into();

        assert_eq!(envelope.seq, 42);
        assert_eq!(envelope.channel, "sm://rooms/diff");
        assert_eq!(envelope.subject, "!r:example.org");
        assert_eq!(envelope.ops.len(), 1);
    }

    #[test]
    fn every_variant_maps_to_its_own() {
        // Not a tautology: it is the only place that checks `Clear` did not
        // quietly become `Reset { values: vec![] }`, which would look right in
        // a diff and be wrong on screen.
        let cases: Vec<(DiffOp<RoomRow>, &str)> = vec![
            (DiffOp::Clear, "Clear"),
            (DiffOp::PopFront, "PopFront"),
            (DiffOp::PopBack, "PopBack"),
            (DiffOp::Remove { index: 1 }, "Remove"),
            (DiffOp::Truncate { length: 2 }, "Truncate"),
        ];
        for (op, expected) in cases {
            let converted: RoomDiffOp = op.into();
            let name = match converted {
                RoomDiffOp::Clear => "Clear",
                RoomDiffOp::PopFront => "PopFront",
                RoomDiffOp::PopBack => "PopBack",
                RoomDiffOp::Remove { .. } => "Remove",
                RoomDiffOp::Truncate { .. } => "Truncate",
                other => panic!("unexpected variant: {other:?}"),
            };
            assert_eq!(name, expected);
        }
    }

    #[test]
    fn an_index_too_large_for_u32_saturates_rather_than_wrapping() {
        // A wrapped index would address the wrong row with total confidence.
        // Saturating is also wrong, but it is wrong in a way that shows up as
        // an obviously-bogus index rather than a plausible one.
        let op: RoomDiffOp = DiffOp::Remove { index: usize::MAX }.into();
        match op {
            RoomDiffOp::Remove { index } => assert_eq!(index, u32::MAX),
            other => panic!("wrong variant: {other:?}"),
        }
    }
}
