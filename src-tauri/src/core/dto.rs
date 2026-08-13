//! IPC DTOs and the single translation point from SDK diffs to wire format.
//!
//! No SDK type crosses the IPC boundary. `matrix_sdk`/`eyeball_im` types stay
//! on the core side of this module; the webview only ever sees these structs.
//! `project_diff` is the exhaustive match that guarantees that boundary holds
//! even as the SDK evolves.

use eyeball_im::VectorDiff;
use serde::Serialize;

/// A single room as summarized for the room list.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomSummary {
    pub id: String,
    pub name: String,
    pub avatar_url: Option<String>,
    pub unread: u64,
    pub last_message: Option<String>,
    pub last_activity_ms: Option<u64>,
}

/// A single timeline item (message, state event, etc.) as rendered.
///
/// `kind` is the semantic discriminant projected from the SDK's
/// `TimelineItemContent` (see `core::timeline::classify_content`) — never a
/// raw Matrix event-type string. `msgtype` and `detail` carry the two kinds
/// of extra context a `kind` sometimes needs to be rendered correctly:
/// `msgtype` is only populated for `kind: "message"` (`m.text`, `m.notice`,
/// …); `detail` carries kind-specific context such as a membership change's
/// change kind, a state event's event type, or a custom event's event type.
/// Both are `None` when the `kind` doesn't need them — see the table in
/// `docs/matrix-events.md` for the full mapping.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TimelineItemDto {
    pub id: String,
    pub kind: String,
    pub msgtype: Option<String>,
    pub detail: Option<String>,
    pub sender: Option<String>,
    pub sender_display_name: Option<String>,
    pub body: Option<String>,
    /// The message's HTML formatted body, present only when the SDK reports
    /// `format: "org.matrix.custom.html"` (see
    /// `core::timeline::formatted_html_body`). Already sanitised — first by
    /// `matrix_sdk_ui::timeline::Message::from_event`'s
    /// `HtmlSanitizerMode::Compat` pass, then by this project's own
    /// `img`/link hardening on top of it (see `core::timeline`'s doc
    /// comments for exactly what each pass does) — because the webview
    /// renders this directly with `{@html}`. `body` stays the untouched
    /// plain-text fallback; never derive one from the other.
    pub formatted_body: Option<String>,
    pub timestamp_ms: Option<u64>,
    pub is_own: bool,
    pub send_state: Option<String>,
}

/// The wire projection of an `eyeball_im::VectorDiff<T>`.
///
/// Tagged with `op` in camelCase so the webview can switch on an exact
/// string (`"pushFront"`, `"popBack"`, ...) without parsing prose.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "op", rename_all = "camelCase")]
pub enum DiffOp<T> {
    Append { values: Vec<T> },
    Clear,
    PushFront { value: T },
    PushBack { value: T },
    PopFront,
    PopBack,
    Insert { index: usize, value: T },
    Set { index: usize, value: T },
    Remove { index: usize },
    Truncate { length: usize },
    Reset { values: Vec<T> },
}

/// The variant name of an op, for diagnostics. Kept next to the enum so a new
/// variant is an obvious thing to add here too.
pub fn op_name<T>(op: &DiffOp<T>) -> &'static str {
    match op {
        DiffOp::Append { .. } => "append",
        DiffOp::Clear => "clear",
        DiffOp::PushFront { .. } => "pushFront",
        DiffOp::PushBack { .. } => "pushBack",
        DiffOp::PopFront => "popFront",
        DiffOp::PopBack => "popBack",
        DiffOp::Insert { .. } => "insert",
        DiffOp::Set { .. } => "set",
        DiffOp::Remove { .. } => "remove",
        DiffOp::Truncate { .. } => "truncate",
        DiffOp::Reset { .. } => "reset",
    }
}

/// A batch of ops for one subject (room list, or a specific room's
/// timeline), stamped with a sequence number the webview uses to detect a
/// dropped event and force a resync.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffEnvelope<T> {
    pub channel: String,
    pub subject: String,
    pub seq: u64,
    pub ops: Vec<DiffOp<T>>,
}

/// Translate one SDK `VectorDiff<S>` into a `DiffOp<T>`, mapping contained
/// items through `f`.
///
/// This match is exhaustive with **no wildcard arm** on purpose: if a future
/// eyeball-im version adds a `VectorDiff` variant, this must fail to compile
/// rather than silently drop the update.
pub fn project_diff<S, T, F>(diff: VectorDiff<S>, f: F) -> DiffOp<T>
where
    S: Clone,
    F: Fn(S) -> T,
{
    match diff {
        VectorDiff::Append { values } => DiffOp::Append {
            values: values.into_iter().map(f).collect(),
        },
        VectorDiff::Clear => DiffOp::Clear,
        VectorDiff::PushFront { value } => DiffOp::PushFront { value: f(value) },
        VectorDiff::PushBack { value } => DiffOp::PushBack { value: f(value) },
        VectorDiff::PopFront => DiffOp::PopFront,
        VectorDiff::PopBack => DiffOp::PopBack,
        VectorDiff::Insert { index, value } => DiffOp::Insert {
            index,
            value: f(value),
        },
        VectorDiff::Set { index, value } => DiffOp::Set {
            index,
            value: f(value),
        },
        VectorDiff::Remove { index } => DiffOp::Remove { index },
        VectorDiff::Truncate { length } => DiffOp::Truncate { length },
        VectorDiff::Reset { values } => DiffOp::Reset {
            values: values.into_iter().map(f).collect(),
        },
    }
}

/// Applies a batch of ops to a materialized `Vec<T>` in place, mirroring
/// exactly what the webview's `DiffTracker`/`applyOps`
/// (`src/lib/stores/diff.ts`) does to its own copy of the same list.
///
/// For a channel that keeps a server-side materialized view in sync with
/// what it emits — so a resync can be served from that view instead of a
/// second, uncoordinated subscription (see `core::rooms::RoomListHandle`) —
/// this is the one place that folds a `DiffOp` batch into it. Exhaustive
/// with no wildcard arm, like `project_diff`: if `DiffOp` ever grows a
/// variant, this must fail to compile rather than silently leave the
/// materialized view out of sync with what was already emitted, which would
/// corrupt every resync served from it.
pub fn apply_ops<T: Clone>(items: &mut Vec<T>, ops: &[DiffOp<T>]) {
    for op in ops {
        match op {
            DiffOp::Append { values } => items.extend(values.iter().cloned()),
            DiffOp::Clear => items.clear(),
            DiffOp::PushFront { value } => items.insert(0, value.clone()),
            DiffOp::PushBack { value } => items.push(value.clone()),
            DiffOp::PopFront => {
                if !items.is_empty() {
                    items.remove(0);
                }
            }
            DiffOp::PopBack => {
                items.pop();
            }
            DiffOp::Insert { index, value } => {
                if *index <= items.len() {
                    items.insert(*index, value.clone());
                }
            }
            DiffOp::Set { index, value } => {
                if let Some(slot) = items.get_mut(*index) {
                    *slot = value.clone();
                }
            }
            DiffOp::Remove { index } => {
                if *index < items.len() {
                    items.remove(*index);
                }
            }
            DiffOp::Truncate { length } => items.truncate(*length),
            DiffOp::Reset { values } => *items = values.clone(),
        }
    }
}

/// Monotonic sequence number generator, starting at 1. The webview uses gaps
/// in this sequence to detect a dropped event and force a resync.
#[derive(Debug, Default)]
pub struct SeqCounter(u64);

impl SeqCounter {
    pub fn next(&mut self) -> u64 {
        self.0 += 1;
        self.0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use eyeball_im::VectorDiff;
    use imbl::vector;

    // Projection must be exhaustive: if eyeball-im adds a variant, this file
    // must fail to compile rather than silently drop updates.
    #[test]
    fn projects_every_variant() {
        let id = |n: i32| n.to_string();

        assert!(matches!(
            project_diff(VectorDiff::Append { values: vector![1, 2] }, id),
            DiffOp::Append { ref values } if values == &["1".to_string(), "2".to_string()]
        ));
        assert!(matches!(
            project_diff::<i32, String, _>(VectorDiff::Clear, id),
            DiffOp::Clear
        ));
        assert!(matches!(
            project_diff(VectorDiff::PushFront { value: 1 }, id),
            DiffOp::PushFront { ref value } if value == "1"
        ));
        assert!(matches!(
            project_diff(VectorDiff::PushBack { value: 1 }, id),
            DiffOp::PushBack { ref value } if value == "1"
        ));
        assert!(matches!(
            project_diff::<i32, String, _>(VectorDiff::PopFront, id),
            DiffOp::PopFront
        ));
        assert!(matches!(
            project_diff::<i32, String, _>(VectorDiff::PopBack, id),
            DiffOp::PopBack
        ));
        assert!(matches!(
            project_diff(VectorDiff::Insert { index: 3, value: 1 }, id),
            DiffOp::Insert { index: 3, ref value } if value == "1"
        ));
        assert!(matches!(
            project_diff(VectorDiff::Set { index: 2, value: 1 }, id),
            DiffOp::Set { index: 2, ref value } if value == "1"
        ));
        assert!(matches!(
            project_diff::<i32, String, _>(VectorDiff::Remove { index: 4 }, id),
            DiffOp::Remove { index: 4 }
        ));
        assert!(matches!(
            project_diff::<i32, String, _>(VectorDiff::Truncate { length: 5 }, id),
            DiffOp::Truncate { length: 5 }
        ));
        assert!(matches!(
            project_diff(VectorDiff::Reset { values: vector![1] }, id),
            DiffOp::Reset { ref values } if values == &["1".to_string()]
        ));
    }

    #[test]
    fn ops_serialize_with_a_discriminant_the_webview_can_switch_on() {
        let json = serde_json::to_value(DiffOp::Insert {
            index: 2,
            value: "x",
        })
        .unwrap();
        assert_eq!(json["op"], "insert");
        assert_eq!(json["index"], 2);
        assert_eq!(json["value"], "x");

        assert_eq!(
            serde_json::to_value(DiffOp::<String>::Clear).unwrap()["op"],
            "clear"
        );
        assert_eq!(
            serde_json::to_value(DiffOp::<String>::PopBack).unwrap()["op"],
            "popBack"
        );
    }

    #[test]
    fn sequence_numbers_start_at_one_and_increment() {
        let mut seq = SeqCounter::default();
        assert_eq!(seq.next(), 1);
        assert_eq!(seq.next(), 2);
        assert_eq!(seq.next(), 3);
    }

    #[test]
    fn envelope_serializes_camel_case() {
        let env = DiffEnvelope {
            channel: "timeline".into(),
            subject: "!room:example.org".into(),
            seq: 7,
            ops: vec![DiffOp::<String>::PopFront],
        };
        let json = serde_json::to_value(&env).unwrap();
        assert_eq!(json["seq"], 7);
        assert_eq!(json["subject"], "!room:example.org");
        assert_eq!(json["ops"][0]["op"], "popFront");
    }

    // apply_ops: every DiffOp variant, mirroring the applyOps coverage in
    // src/lib/stores/diff.test.ts (Task 11) one-for-one, since a divergence
    // between the two would silently corrupt every resync served from the
    // Rust-side materialized state.
    #[test]
    fn apply_ops_appends() {
        let mut items = vec![1];
        apply_ops(&mut items, &[DiffOp::Append { values: vec![2, 3] }]);
        assert_eq!(items, vec![1, 2, 3]);
    }

    #[test]
    fn apply_ops_clears() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::Clear]);
        assert_eq!(items, Vec::<i32>::new());
    }

    #[test]
    fn apply_ops_pushes_front() {
        let mut items = vec![2];
        apply_ops(&mut items, &[DiffOp::PushFront { value: 1 }]);
        assert_eq!(items, vec![1, 2]);
    }

    #[test]
    fn apply_ops_pushes_back() {
        let mut items = vec![1];
        apply_ops(&mut items, &[DiffOp::PushBack { value: 2 }]);
        assert_eq!(items, vec![1, 2]);
    }

    #[test]
    fn apply_ops_pops_front() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::PopFront]);
        assert_eq!(items, vec![2]);
    }

    #[test]
    fn apply_ops_pops_back() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::PopBack]);
        assert_eq!(items, vec![1]);
    }

    #[test]
    fn apply_ops_inserts() {
        let mut items = vec![1, 3];
        apply_ops(&mut items, &[DiffOp::Insert { index: 1, value: 2 }]);
        assert_eq!(items, vec![1, 2, 3]);
    }

    #[test]
    fn apply_ops_sets() {
        let mut items = vec![1, 9];
        apply_ops(&mut items, &[DiffOp::Set { index: 1, value: 2 }]);
        assert_eq!(items, vec![1, 2]);
    }

    #[test]
    fn apply_ops_removes() {
        let mut items = vec![1, 2, 3];
        apply_ops(&mut items, &[DiffOp::Remove { index: 1 }]);
        assert_eq!(items, vec![1, 3]);
    }

    #[test]
    fn apply_ops_truncates() {
        let mut items = vec![1, 2, 3];
        apply_ops(&mut items, &[DiffOp::Truncate { length: 2 }]);
        assert_eq!(items, vec![1, 2]);
    }

    #[test]
    fn apply_ops_resets() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::Reset { values: vec![9] }]);
        assert_eq!(items, vec![9]);
    }

    #[test]
    fn apply_ops_applies_a_batch_in_order() {
        let mut items = vec![1];
        apply_ops(
            &mut items,
            &[DiffOp::PushBack { value: 2 }, DiffOp::PopFront],
        );
        assert_eq!(items, vec![2]);
    }

    // Defensive: an out-of-bounds op should never happen against a
    // consistent SDK-driven stream, but silently skipping rather than
    // panicking keeps one malformed batch from permanently killing the
    // background streaming task.
    #[test]
    fn apply_ops_ignores_out_of_bounds_indices_instead_of_panicking() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::Remove { index: 5 }]);
        assert_eq!(items, vec![1, 2]);

        apply_ops(&mut items, &[DiffOp::Set { index: 5, value: 9 }]);
        assert_eq!(items, vec![1, 2]);
    }

    // `Vec::insert` panics when `index > len` — unlike `Set`/`Remove`/
    // `PopFront`/`PopBack`, which are all guarded above, an unguarded
    // `Insert` would crash the streaming task (and silently freeze the
    // affected list) on one malformed batch instead of just skipping it.
    #[test]
    fn apply_ops_ignores_an_out_of_range_insert_instead_of_panicking() {
        let mut items = vec![1, 2];
        apply_ops(&mut items, &[DiffOp::Insert { index: 5, value: 9 }]);
        assert_eq!(items, vec![1, 2]);

        // The boundary case `index == len` is a valid append-via-insert and
        // must still work.
        apply_ops(&mut items, &[DiffOp::Insert { index: 2, value: 3 }]);
        assert_eq!(items, vec![1, 2, 3]);
    }

    #[test]
    fn apply_ops_ignores_pop_on_an_empty_list_instead_of_panicking() {
        let mut items = Vec::<i32>::new();
        apply_ops(&mut items, &[DiffOp::PopFront]);
        apply_ops(&mut items, &[DiffOp::PopBack]);
        assert_eq!(items, Vec::<i32>::new());
    }
}
