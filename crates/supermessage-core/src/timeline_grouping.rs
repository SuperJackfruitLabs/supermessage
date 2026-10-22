//! The timeline grouping pass: the rules that depend on a row's neighbours.
//!
//! Ported from iOS's `TimelineGrouping.swift`, which the Android and desktop
//! hosts each carried their own drifting copy of. One walk over the list
//! decides, for every drawn row:
//!
//! - **sender runs** (`continues_run`, `ends_run`): a bubble continues the
//!   bubble above it when the sender and side match and the gap is inside
//!   [`RUN_WINDOW_MS`]. The window is one-directional: a message dated before
//!   its predecessor starts a run.
//! - **reply quotes** (`hides_quote`): a reply drawn directly under its parent
//!   does not repeat it.
//! - **runtime suffixes** (`shows_runtime`): the bridge's `(harness on host)`
//!   suffix is worth showing only where two or more peers speak.
//! - **membership** ([`MembershipPlacement`]): consecutive membership changes
//!   collapse into runs, and a stretch of three or more lines into one summary.
//!
//! A row whose view is [`ItemView::None`], or that the reader hid
//! ([`TimelineRow::hidden`]), is not drawn, and so neither breaks nor joins
//! anything. A host that draws the list reads these fields
//! and applies no rule of its own; the one thing it keeps is which stretches
//! the reader has opened.

use std::collections::HashMap;

use serde::Serialize;

use crate::dto::{op_values, DiffOp, TimelineRow};
use crate::item_view::ItemView;

/// How close two messages from one sender must be to read as one turn.
pub const RUN_WINDOW_MS: u64 = 5 * 60 * 1000;

/// How many people a grouped membership line names before it counts the rest.
pub const MAX_NAMED: usize = 2;

/// A stretch that still has this many lines after runs are formed collapses
/// into one summary line, unless the reader opens it.
pub const STRETCH_COLLAPSE_THRESHOLD: usize = 3;

/// The verb for a membership row the core gave none for.
const DEFAULT_VERB: &str = "updated their membership";

/// Where a drawn membership row sits: in a run of one sentence, inside a
/// stretch that may collapse.
///
/// Expansion is not here. The placement carries both the run lines and the
/// stretch line, and the host picks between them by which stretches the reader
/// has opened.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct MembershipPlacement {
    /// `group:<id of the run's first row>`, the same on every row of the run.
    pub run_id: String,
    /// The run's sentence. `Some` only on the run's first row.
    #[uniffi(default = None)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub run_text: Option<String>,
    /// `stretch:<id of the stretch's first row>`, on every row of a stretch
    /// that has [`STRETCH_COLLAPSE_THRESHOLD`] or more runs; otherwise `None`.
    #[uniffi(default = None)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stretch_id: Option<String>,
    /// The stretch's summary line. `Some` only on its first row, with
    /// [`Self::stretch_id`].
    #[uniffi(default = None)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stretch_text: Option<String>,
}

/// [`settle`] over a copy, for fixtures and previews. Never the live path.
pub fn group_rows(mut rows: Vec<TimelineRow>) -> Vec<TimelineRow> {
    settle(&mut rows);
    rows
}

/// Set the grouping fields of every row in place and return the indices
/// (ascending, once each) whose fields changed.
///
/// One forward walk for runs, quotes, speakers and membership, then one
/// backward walk for `ends_run`. Nothing is cloned for a row that did not
/// change, and the walk allocates only for membership stretches.
pub fn settle(rows: &mut [TimelineRow]) -> Vec<usize> {
    let mut changed = Vec::new();
    let several = has_several_speakers(rows);

    let mut previous_drawn: Option<usize> = None;
    let mut previous_event: Option<usize> = None;
    let mut stretch: Vec<usize> = Vec::new();

    for i in 0..rows.len() {
        if undrawn(&rows[i]) {
            let mut dirty = set_flags(rows, i, false, false, false);
            dirty |= std::mem::take(&mut rows[i].ends_run);
            dirty |= rows[i].membership.take().is_some();
            if dirty {
                changed.push(i);
            }
            continue;
        }
        let shows_runtime = several && rows[i].sender_name != rows[i].sender_short;

        if rows[i].item.kind == "membership" {
            stretch.push(i);
            if set_flags(rows, i, false, false, shows_runtime) {
                changed.push(i);
            }
            // A collapsed line sits above whatever follows, so there is
            // nothing to continue or to be a parent.
            previous_drawn = None;
            previous_event = None;
            continue;
        }
        place_stretch(rows, &mut stretch, &mut changed);

        let continues = previous_drawn.is_some_and(|p| continues_run(&rows[i], &rows[p]));
        let hides_quote = match (&rows[i].item.reply_to, previous_event) {
            (Some(reply), Some(p)) => rows[p].item.event_id.as_deref() == Some(&reply.event_id),
            _ => false,
        };
        let mut dirty = set_flags(rows, i, continues, hides_quote, shows_runtime);
        dirty |= rows[i].membership.take().is_some();
        if dirty {
            changed.push(i);
        }
        previous_drawn = Some(i);
        if rows[i].item.event_id.is_some() {
            previous_event = Some(i);
        }
    }
    place_stretch(rows, &mut stretch, &mut changed);

    // `ends_run` is the mirror of the next drawn row's `continues_run`.
    let mut next_continues = false;
    for i in (0..rows.len()).rev() {
        if undrawn(&rows[i]) {
            continue;
        }
        let ends = !next_continues;
        if rows[i].ends_run != ends {
            rows[i].ends_run = ends;
            changed.push(i);
        }
        next_continues = rows[i].continues_run;
    }

    changed.sort_unstable();
    changed.dedup();
    changed
}

/// A row the host does not draw: silent, or hidden by the reader. Neither
/// breaks nor joins anything.
fn undrawn(row: &TimelineRow) -> bool {
    row.hidden || matches!(row.view, ItemView::None)
}

/// Write the three forward flags, reporting whether any differed. `ends_run`
/// is the backward walk's.
fn set_flags(
    rows: &mut [TimelineRow],
    i: usize,
    continues_run: bool,
    hides_quote: bool,
    shows_runtime: bool,
) -> bool {
    let row = &mut rows[i];
    let dirty = row.continues_run != continues_run
        || row.hides_quote != hides_quote
        || row.shows_runtime != shows_runtime;
    row.continues_run = continues_run;
    row.hides_quote = hides_quote;
    row.shows_runtime = shows_runtime;
    dirty
}

/// Copy the settled grouping fields onto every row the ops carry, by item id.
///
/// The ops a batch sends are the SDK's own, and their values were built before
/// the pass ran. Stamping the final fields onto them means a row that arrives
/// in a batch (a `PushBack`, an `Append` of a back-paginated page, a `Reset`)
/// carries its grouping with it, so the pass needs no `Set` for it afterwards.
/// A value whose id is no longer in `rows` was removed later in the batch and
/// is left alone.
///
/// One pass over `rows`, looking each id up in the batch's own ids; nothing
/// is cloned but the five fields.
pub(crate) fn stamp(ops: &mut [DiffOp<TimelineRow>], rows: &[TimelineRow]) {
    let mut carried: HashMap<String, Option<&TimelineRow>> = HashMap::new();
    for op in ops.iter() {
        for value in op_values(op) {
            carried.insert(value.item.id.clone(), None);
        }
    }
    if carried.is_empty() {
        return;
    }
    for row in rows {
        if let Some(slot) = carried.get_mut(&row.item.id) {
            *slot = Some(row);
        }
    }
    for op in ops.iter_mut() {
        for value in op_values_mut(op) {
            if let Some(Some(settled)) = carried.get(&value.item.id) {
                value.continues_run = settled.continues_run;
                value.ends_run = settled.ends_run;
                value.hides_quote = settled.hides_quote;
                value.shows_runtime = settled.shows_runtime;
                value.membership = settled.membership.clone();
                value.hidden = settled.hidden;
            }
        }
    }
}

fn op_values_mut(op: &mut DiffOp<TimelineRow>) -> Vec<&mut TimelineRow> {
    match op {
        DiffOp::Append { values } | DiffOp::Reset { values } => values.iter_mut().collect(),
        DiffOp::PushFront { value }
        | DiffOp::PushBack { value }
        | DiffOp::Insert { value, .. }
        | DiffOp::Set { value, .. } => vec![value],
        DiffOp::Clear
        | DiffOp::PopFront
        | DiffOp::PopBack
        | DiffOp::Remove { .. }
        | DiffOp::Truncate { .. } => Vec::new(),
    }
}

/// Whether two or more distinct peers have spoken. Own messages say nothing:
/// they are attributed by position, not by name. Stops at the second sender.
fn has_several_speakers(rows: &[TimelineRow]) -> bool {
    let mut first: Option<&str> = None;
    for row in rows.iter().filter(|row| !row.item.is_own && !row.hidden) {
        let Some(sender) = row.item.sender.as_deref() else {
            continue;
        };
        match first {
            None => first = Some(sender),
            Some(seen) if seen != sender => return true,
            Some(_) => {}
        }
    }
    false
}

/// Whether `row` drops its header because `previous` already carries one.
fn continues_run(row: &TimelineRow, previous: &TimelineRow) -> bool {
    // Only ordinary messages group: a card or an image carries its own header,
    // and a message after one must not read as that card's author speaking.
    matches!(row.view, ItemView::Bubble { .. })
        && matches!(previous.view, ItemView::Bubble { .. })
        // Nobody is not the same somebody.
        && row.item.sender.is_some()
        && row.item.sender == previous.item.sender
        && row.item.is_own == previous.item.is_own
        && match (row.item.timestamp_ms, previous.item.timestamp_ms) {
            (Some(now), Some(then)) => now >= then && now - then <= RUN_WINDOW_MS,
            _ => false,
        }
}

/// Settle one unbroken stretch of drawn membership rows, then empty it.
fn place_stretch(rows: &mut [TimelineRow], stretch: &mut Vec<usize>, changed: &mut Vec<usize>) {
    if stretch.is_empty() {
        return;
    }
    let placements = {
        let members: Vec<&TimelineRow> = stretch.iter().map(|&i| &rows[i]).collect();
        placements_for(&members)
    };
    for (&i, placement) in stretch.iter().zip(placements) {
        if rows[i].membership.as_ref() != Some(&placement) {
            rows[i].membership = Some(placement);
            changed.push(i);
        }
    }
    stretch.clear();
}

/// The placement of each row of a stretch, in order.
fn placements_for(members: &[&TimelineRow]) -> Vec<MembershipPlacement> {
    // Rule 1: consecutive changes about one person are one segment.
    let mut segments: Vec<(usize, usize)> = Vec::new();
    for pos in 0..members.len() {
        match segments.last_mut() {
            Some(last) if name(members[last.1 - 1]) == name(members[pos]) => last.1 = pos + 1,
            _ => segments.push((pos, pos + 1)),
        }
    }

    // Rule 2: neighbours that each made one kind of change share a sentence;
    // a person who made several kinds gets a sentence of their own.
    let mut entries: Vec<(usize, usize, String)> = Vec::new();
    let mut run: Option<(usize, usize)> = None;
    let flush = |run: &mut Option<(usize, usize)>, entries: &mut Vec<(usize, usize, String)>| {
        if let Some((start, end)) = run.take() {
            entries.push((start, end, run_text(&members[start..end])));
        }
    };
    for (start, end) in segments {
        let verb0 = verb(members[start]);
        if members[start..end].iter().all(|m| verb(m) == verb0) {
            match run {
                Some((run_start, _)) if verb(members[run_start]) != verb0 => {
                    flush(&mut run, &mut entries);
                    run = Some((start, end));
                }
                Some((run_start, _)) => run = Some((run_start, end)),
                None => run = Some((start, end)),
            }
        } else {
            flush(&mut run, &mut entries);
            entries.push((start, end, churn_text(&members[start..end])));
        }
    }
    flush(&mut run, &mut entries);

    // Rule 3: still a screenful, so one summary line unless opened.
    let collapsed = entries.len() >= STRETCH_COLLAPSE_THRESHOLD;
    let stretch_id = format!("stretch:{}", members[0].item.id);
    let stretch_text = collapsed.then(|| stretch_text(members));

    let mut out = Vec::with_capacity(members.len());
    for (start, end, text) in entries {
        let run_id = format!("group:{}", members[start].item.id);
        for pos in start..end {
            out.push(MembershipPlacement {
                run_id: run_id.clone(),
                run_text: (pos == start).then(|| text.clone()),
                stretch_id: collapsed.then(|| stretch_id.clone()),
                stretch_text: if pos == 0 { stretch_text.clone() } else { None },
            });
        }
    }
    out
}

/// The name a membership line opens with: the person it is about, not who
/// made the change, so "Rakesh invited Sam" and "Sam joined" are both Sam's.
fn name(row: &TimelineRow) -> &str {
    row.item
        .membership_subject
        .as_deref()
        .unwrap_or(&row.sender_short)
}

fn verb(row: &TimelineRow) -> &str {
    row.membership_verb.as_deref().unwrap_or(DEFAULT_VERB)
}

/// Distinct names in order of first appearance.
fn distinct_names<'a>(rows: &[&'a TimelineRow]) -> Vec<&'a str> {
    let mut seen: Vec<&str> = Vec::new();
    for row in rows {
        let who = name(row);
        if !seen.contains(&who) {
            seen.push(who);
        }
    }
    seen
}

fn others(count: usize) -> &'static str {
    if count == 1 {
        "other"
    } else {
        "others"
    }
}

/// "Ganesha, Krishna and 2 others joined the room": one run of a single verb.
fn run_text(run: &[&TimelineRow]) -> String {
    let verb = run.first().map_or(DEFAULT_VERB, |row| verb(row));
    let names = distinct_names(run);
    if names.len() <= MAX_NAMED {
        let who = match names.as_slice() {
            [] => "Someone".to_string(),
            [only] => (*only).to_string(),
            [init @ .., last] => format!("{} and {last}", init.join(", ")),
        };
        return format!("{who} {verb}");
    }
    let remaining = names.len() - MAX_NAMED;
    format!(
        "{} and {remaining} {} {verb}",
        names[..MAX_NAMED].join(", "),
        others(remaining)
    )
}

/// One person's churn: two changes read in order, more as a count.
fn churn_text(run: &[&TimelineRow]) -> String {
    let who = run.first().map_or("Someone", |row| name(row));
    if run.len() == 2 {
        format!("{who} {}, then {}", verb(run[0]), verb(run[1]))
    } else {
        format!("{who} made {} membership changes", run.len())
    }
}

/// "7 membership changes · Strategy Sam, Rakesh and 1 other".
fn stretch_text(stretch: &[&TimelineRow]) -> String {
    let people = distinct_names(stretch);
    let named = match people.as_slice() {
        [] => "Someone".to_string(),
        [only] => (*only).to_string(),
        [a, b] => format!("{a} and {b}"),
        [a, b, rest @ ..] => format!("{a}, {b} and {} {}", rest.len(), others(rest.len())),
    };
    format!("{} membership changes · {named}", stretch.len())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dto::ReplyToDto;
    use crate::timeline::project_item_parts;

    fn item(
        id: &str,
        kind: &str,
        sender: Option<&str>,
        at_ms: Option<u64>,
        own: bool,
    ) -> crate::dto::TimelineItemDto {
        project_item_parts(
            id,
            Some(id),
            kind,
            (kind == "message").then_some("m.text"),
            None,
            sender,
            None,
            None,
            false,
            Some("hi"),
            None,
            None,
            None,
            at_ms,
            own,
            None,
            None,
            false,
            Vec::new(),
            Vec::new(),
        )
    }

    /// An ordinary message, attributed by its sender id.
    fn msg(id: &str, sender: &str, at_ms: u64, own: bool) -> TimelineRow {
        let mut row = TimelineRow::new(item(id, "message", Some(sender), Some(at_ms), own));
        row.sender_name = sender.into();
        row.sender_short = sender.into();
        row
    }

    /// A membership change by `sender` with `verb`, about `subject` when given.
    fn membership(id: &str, sender: &str, verb: &str, subject: Option<&str>) -> TimelineRow {
        let mut row = TimelineRow::new(item(id, "membership", Some(sender), Some(1), false));
        row.view = ItemView::System {
            kind: crate::item_view::SystemKind::MembershipChanged {
                who: sender.into(),
                detail: None,
            },
            text: format!("{sender} {verb}"),
        };
        row.sender_short = sender.into();
        row.membership_verb = Some(verb.into());
        row.item.membership_subject = subject.map(str::to_string);
        row
    }

    fn change(id: &str, who: &str, verb: &str) -> TimelineRow {
        membership(id, who, verb, None)
    }

    /// A row the core said to draw nothing for.
    fn silent(id: &str) -> TimelineRow {
        let mut row = TimelineRow::new(item(id, "state", Some("@a:x"), Some(1), false));
        row.view = ItemView::None;
        row
    }

    fn marker() -> TimelineRow {
        let mut row = TimelineRow::new(item("marker", "readMarker", None, None, false));
        row.item.event_id = None;
        row.view = ItemView::UnreadMarker;
        row
    }

    fn divider() -> TimelineRow {
        let mut row = TimelineRow::new(item("day", "dateDivider", None, None, false));
        row.item.event_id = None;
        row.view = ItemView::DateDivider;
        row
    }

    fn reply(id: &str, to: &str, at_ms: u64) -> TimelineRow {
        let mut row = msg(id, "@b:x", at_ms, false);
        row.item.reply_to = Some(ReplyToDto {
            event_id: to.into(),
            available: true,
            sender: Some("@a:x".into()),
            sender_display_name: None,
            excerpt: Some("hi".into()),
            label: None,
        });
        row
    }

    /// `row` hidden by the reader.
    fn hid(mut row: TimelineRow) -> TimelineRow {
        row.hidden = true;
        row
    }

    /// Hiding the head of a run gives the next row the header.
    ///
    /// Mutation seen failing: `undrawn` ignoring `hidden` (only the `None`
    /// view) leaves the second row continuing a run whose head is not drawn.
    #[test]
    fn hiding_a_run_head_gives_the_next_row_a_header() {
        let rows = group_rows(vec![
            hid(msg("$1", "@a:x", 1_000, false)),
            msg("$2", "@a:x", 2_000, false),
            msg("$3", "@a:x", 3_000, false),
        ]);
        assert!(!rows[1].continues_run, "it is now the first drawn row");
        assert!(rows[2].continues_run, "and still heads its run");
        assert!(
            !rows[0].continues_run && !rows[0].ends_run,
            "hidden: no flags"
        );
    }

    /// Hiding the last row of a run ends the run on the row before.
    ///
    /// Mutation seen failing: `undrawn` ignoring `hidden` leaves the middle
    /// row's `ends_run` false (the hidden row is taken for the end). The
    /// backward walk's own `undrawn` skip is equivalent here: a hidden row's
    /// `continues_run` is already reset, so it reads the same either way.
    #[test]
    fn hiding_the_last_row_of_a_run_ends_it_on_the_previous_row() {
        let rows = group_rows(vec![
            msg("$1", "@a:x", 1_000, false),
            msg("$2", "@a:x", 2_000, false),
            hid(msg("$3", "@a:x", 3_000, false)),
        ]);
        assert!(rows[1].ends_run, "the last drawn row ends the run");
        assert!(!rows[0].ends_run);
    }

    /// A row separated from its run mate only by a hidden row still continues.
    ///
    /// Mutation seen failing: `undrawn` ignoring `hidden` makes the hidden
    /// peer break the run (the last row gets a header).
    #[test]
    fn a_hidden_row_between_two_of_a_run_breaks_nothing() {
        let rows = group_rows(vec![
            msg("$1", "@a:x", 1_000, false),
            hid(msg("$2", "@b:x", 2_000, false)),
            msg("$3", "@a:x", 3_000, false),
        ]);
        assert!(rows[2].continues_run);
        assert!(!rows[0].ends_run, "the run goes on past the hidden row");
    }

    /// Hiding the parent a reply sits under un-hides the reply's quote.
    ///
    /// Mutation seen failing: `undrawn` ignoring `hidden` keeps `hides_quote`
    /// true although the parent is not drawn.
    #[test]
    fn hiding_a_quoted_parent_shows_the_quote() {
        let parent = msg("$p", "@a:x", 1_000, false);
        let child = reply("$r", "$p", 2_000);
        assert!(group_rows(vec![parent.clone(), child.clone()])[1].hides_quote);
        let rows = group_rows(vec![hid(parent), child]);
        assert!(!rows[1].hides_quote, "the parent is not drawn above it");
    }

    /// Unhiding restores exactly what grouping the whole list gave.
    ///
    /// Mutation seen failing: `undrawn` ignoring `hidden` leaves the row after
    /// the hidden head continuing a run (the first assertion).
    #[test]
    fn unhiding_restores_the_flags() {
        let plain = group_rows(vec![
            msg("$1", "@a:x", 1_000, false),
            msg("$2", "@a:x", 2_000, false),
            msg("$3", "@a:x", 3_000, false),
        ]);
        let mut rows = plain.clone();
        rows[0].hidden = true;
        settle(&mut rows);
        assert!(!rows[1].continues_run);
        rows[0].hidden = false;
        let changed = settle(&mut rows);
        assert_eq!(rows, plain);
        assert_eq!(changed, vec![1], "only the row it headed moved");
    }

    /// A hidden peer does not make the room a multi-speaker room.
    ///
    /// Mutation seen failing: counting hidden rows in `has_several_speakers`
    /// leaves `shows_runtime` true.
    #[test]
    fn a_hidden_speaker_does_not_count() {
        let mut a = msg("$1", "@a:x", 1_000, false);
        a.sender_name = "A (h on box)".into();
        let b = msg("$2", "@b:x", 2_000, false);
        assert!(group_rows(vec![a.clone(), b.clone()])[0].shows_runtime);
        assert!(!group_rows(vec![a, hid(b)])[0].shows_runtime);
    }

    fn continues(rows: Vec<TimelineRow>) -> Vec<bool> {
        group_rows(rows).iter().map(|r| r.continues_run).collect()
    }

    /// Each run's sentence, in order, from the rows that carry one.
    fn run_texts(rows: Vec<TimelineRow>) -> Vec<String> {
        group_rows(rows)
            .into_iter()
            .filter_map(|r| r.membership?.run_text)
            .collect()
    }

    // --- runs ------------------------------------------------------------

    /// Mutation seen failing: `false &&` in front of the bubble check of `continues_run`.
    #[test]
    fn a_second_message_from_the_same_sender_moments_later_continues_the_run() {
        let out = continues(vec![
            msg("1", "@a:x", 1_000, false),
            msg("2", "@a:x", 60_000, false),
        ]);
        assert_eq!(out, [false, true]);
    }

    /// Mutation seen failing: dropping the `sender ==` comparison.
    #[test]
    fn a_different_sender_starts_a_new_run() {
        let out = continues(vec![
            msg("1", "@a:x", 1_000, false),
            msg("2", "@b:x", 2_000, false),
        ]);
        assert_eq!(out, [false, false]);
    }

    /// Mutation seen failing: `<=` to `<` on the window, and dropping its upper bound (each fails this test).
    #[test]
    fn a_long_gap_starts_a_new_run_even_from_the_same_sender() {
        let out = continues(vec![
            msg("1", "@a:x", 0, false),
            msg("2", "@a:x", RUN_WINDOW_MS + 1, false),
        ]);
        assert_eq!(out, [false, false]);
        let out = continues(vec![
            msg("1", "@a:x", 0, false),
            msg("2", "@a:x", RUN_WINDOW_MS, false),
        ]);
        assert_eq!(out, [false, true]);
    }

    /// Mutation seen failing: replacing the previous row's bubble check with `true`.
    #[test]
    fn anything_that_is_not_an_ordinary_message_ends_the_run() {
        let mut card = msg("1", "@a:x", 1_000, false);
        card.view = ItemView::Emote;
        let message = msg("2", "@a:x", 2_000, false);
        assert_eq!(
            continues(vec![card.clone(), message.clone()]),
            [false, false]
        );
        assert_eq!(continues(vec![message, card]), [false, false]);
    }

    /// Mutation seen failing: `previous_drawn.map_or(true, ..)`, which lets the first row continue.
    #[test]
    fn the_first_row_never_continues_anything() {
        assert_eq!(continues(vec![msg("1", "@a:x", 1, false)]), [false]);
    }

    /// Mutation seen failing: dropping the `is_own ==` comparison.
    #[test]
    fn an_own_message_does_not_join_a_peers_run() {
        let out = continues(vec![
            msg("1", "@a:x", 1_000, false),
            msg("2", "@a:x", 2_000, true),
        ]);
        assert_eq!(out, [false, false]);
    }

    /// Mutation seen failing: `ends_run = next_continues` (not inverted).
    #[test]
    fn ends_run_mirrors_the_next_rows_continues_run() {
        let rows = group_rows(vec![
            msg("1", "@a:x", 1_000, false),
            msg("2", "@a:x", 2_000, false),
            msg("3", "@b:x", 3_000, false),
        ]);
        let ends: Vec<bool> = rows.iter().map(|r| r.ends_run).collect();
        assert_eq!(ends, [false, true, true]);
        let continues: Vec<bool> = rows.iter().map(|r| r.continues_run).collect();
        for i in 0..2 {
            assert_eq!(rows[i].ends_run, !rows[i + 1].continues_run);
        }
        assert_eq!(continues, [false, true, false]);
    }

    /// Mutation seen failing: removing the hidden-row skip in the backward walk.
    #[test]
    fn a_silent_row_between_two_messages_does_not_break_their_run() {
        let rows = group_rows(vec![
            msg("1", "@a:x", 1_000, false),
            silent("s"),
            msg("2", "@a:x", 2_000, false),
        ]);
        assert!(rows[2].continues_run);
        assert!(!rows[0].ends_run, "the run goes on past the hidden row");
        assert!(!rows[1].ends_run, "a hidden row ends nothing");
    }

    /// Mutation seen failing: `abs_diff` in place of the one-directional window.
    #[test]
    fn a_message_dated_before_its_predecessor_starts_a_new_run() {
        assert_eq!(
            continues(vec![
                msg("1", "@a:x", 10_000, false),
                msg("2", "@a:x", 9_000, false)
            ]),
            [false, false]
        );
    }

    /// Mutation seen failing: dropping the `sender.is_some()` guard.
    #[test]
    fn a_message_with_no_sender_never_continues_a_run() {
        let mut a = msg("1", "@a:x", 1_000, false);
        let mut b = msg("2", "@a:x", 2_000, false);
        a.item.sender = None;
        b.item.sender = None;
        assert_eq!(continues(vec![a, b]), [false, false]);
    }

    // --- speakers --------------------------------------------------------

    fn suffixed(id: &str, sender: &str, own: bool) -> TimelineRow {
        let mut row = msg(id, sender, 1, own);
        row.sender_name = format!("{sender} (Claude on Ashram)");
        row
    }

    /// Mutation seen failing: `several = true`.
    #[test]
    fn a_room_where_one_agent_speaks_does_not_repeat_its_runtime() {
        let rows = group_rows(vec![
            suffixed("1", "@a:x", false),
            suffixed("2", "@a:x", false),
        ]);
        assert!(rows.iter().all(|r| !r.shows_runtime));
    }

    /// Mutation seen failing: `several = false`; and `several` alone, without
    /// the suffix test (a row with no suffix would show one).
    #[test]
    fn a_room_where_several_speak_keeps_it() {
        let rows = group_rows(vec![
            suffixed("1", "@a:x", false),
            suffixed("2", "@b:x", false),
            msg("3", "@c:x", 3, false),
        ]);
        assert!(rows[0].shows_runtime && rows[1].shows_runtime);
        assert!(!rows[2].shows_runtime, "no suffix, nothing to show");
    }

    /// Mutation seen failing: counting own senders (`.filter(|_| true)`).
    #[test]
    fn your_own_messages_do_not_make_a_room_multi_voiced() {
        let rows = group_rows(vec![
            suffixed("1", "@a:x", false),
            suffixed("2", "@me:x", true),
        ]);
        assert!(rows.iter().all(|r| !r.shows_runtime));
    }

    // --- membership ------------------------------------------------------

    /// Mutation seen failing: `MAX_NAMED` 2 to 3.
    #[test]
    fn a_run_of_the_same_change_becomes_one_sentence() {
        let rows = group_rows(vec![
            change("1", "Ganesha", "joined the room"),
            change("2", "Krishna", "joined the room"),
            change("3", "Annapurna", "joined the room"),
            change("4", "Surya", "joined the room"),
        ]);
        let first = rows[0].membership.as_ref().unwrap();
        assert_eq!(
            first.run_text.as_deref(),
            Some("Ganesha, Krishna and 2 others joined the room")
        );
        assert_eq!(first.run_id, "group:1");
        assert!(rows[1..]
            .iter()
            .all(|r| r.membership.as_ref().unwrap().run_text.is_none()
                && r.membership.as_ref().unwrap().run_id == "group:1"));
        assert_eq!(first.stretch_id, None, "one run is not a stretch");
    }

    /// Mutation seen failing: not de-duplicating names, and always "others".
    #[test]
    fn interleaved_churn_by_several_people_collapses_into_one_line() {
        let rows = group_rows(vec![
            change("1", "Strategy Sam", "was invited"),
            change("2", "Rakesh", "updated their membership"),
            change("3", "Strategy Sam", "left the room"),
            change("4", "strategy-sam", "was invited"),
            change("5", "Strategy Sam", "joined the room"),
        ]);
        let first = rows[0].membership.as_ref().unwrap();
        assert_eq!(
            first.stretch_text.as_deref(),
            Some("5 membership changes · Strategy Sam, Rakesh and 1 other")
        );
        for row in &rows {
            assert_eq!(
                row.membership.as_ref().unwrap().stretch_id.as_deref(),
                Some("stretch:1")
            );
        }
        assert!(rows[1..]
            .iter()
            .all(|r| r.membership.as_ref().unwrap().stretch_text.is_none()));
    }

    /// Mutation seen failing: setting `run_text` only when the stretch is not collapsed.
    #[test]
    fn an_opened_stretch_still_has_its_lines() {
        let rows = group_rows(vec![
            change("1", "Strategy Sam", "was invited"),
            change("2", "Rakesh", "updated their membership"),
            change("3", "Strategy Sam", "left the room"),
        ]);
        assert!(rows.iter().all(|r| {
            let m = r.membership.as_ref().unwrap();
            m.stretch_id.is_some() && m.run_text.is_some()
        }));
    }

    /// Mutation seen failing: `STRETCH_COLLAPSE_THRESHOLD` 3 to 2.
    #[test]
    fn two_lines_are_not_worth_collapsing() {
        let rows = group_rows(vec![
            change("1", "Strategy Sam", "was invited"),
            change("2", "Rakesh", "updated their membership"),
        ]);
        assert!(rows
            .iter()
            .all(|r| r.membership.as_ref().unwrap().stretch_id.is_none()));
    }

    /// Mutation seen failing: not de-duplicating names (`if true` in `distinct_names`).
    #[test]
    fn one_person_changing_twice_is_named_once() {
        assert_eq!(
            run_texts(vec![
                change("1", "Annapurna", "updated their membership"),
                change("2", "Annapurna", "updated their membership"),
                change("3", "Surya", "updated their membership"),
            ]),
            ["Annapurna and Surya updated their membership"]
        );
    }

    /// Mutation seen failing: formatting a single name as "X and 0 others".
    #[test]
    fn a_run_of_one_reads_exactly_like_an_ungrouped_line() {
        assert_eq!(
            run_texts(vec![change("1", "Ganesha", "joined the room")]),
            ["Ganesha joined the room"]
        );
    }

    /// Mutation seen failing: never splitting a run on a new verb.
    #[test]
    fn different_verbs_stay_different_sentences() {
        assert_eq!(
            run_texts(vec![
                change("1", "Ganesha", "joined the room"),
                change("2", "Krishna", "left the room"),
            ]),
            ["Ganesha joined the room", "Krishna left the room"]
        );
    }

    /// Mutation seen failing: not flushing the stretch at a drawn non-membership row.
    #[test]
    fn messages_pass_through_untouched_and_break_a_run() {
        let rows = group_rows(vec![
            change("1", "Ganesha", "joined the room"),
            msg("m", "@a:x", 2, false),
            change("2", "Krishna", "joined the room"),
        ]);
        assert!(rows[1].membership.is_none());
        assert_ne!(
            rows[0].membership.as_ref().unwrap().run_id,
            rows[2].membership.as_ref().unwrap().run_id
        );
    }

    /// Mutation seen failing: flushing the stretch at a hidden row.
    #[test]
    fn a_silent_row_does_not_break_a_membership_run_either() {
        let rows = group_rows(vec![
            change("1", "Ganesha", "joined the room"),
            silent("s"),
            change("2", "Krishna", "joined the room"),
        ]);
        assert_eq!(rows[0].membership.as_ref().unwrap().run_id, "group:1");
        assert_eq!(rows[2].membership.as_ref().unwrap().run_id, "group:1");
    }

    /// Mutation seen failing: treating a hidden row as drawn when its kind is membership.
    #[test]
    fn a_membership_change_the_core_silenced_is_not_drawn_as_one() {
        let mut noop = change("s", "Krishna", "updated their membership");
        noop.view = ItemView::None;
        let rows = group_rows(vec![
            change("1", "Ganesha", "joined the room"),
            noop,
            change("2", "Annapurna", "joined the room"),
        ]);
        assert!(rows[1].membership.is_none());
        assert_eq!(
            rows[0].membership.as_ref().unwrap().run_text.as_deref(),
            Some("Ganesha and Annapurna joined the room")
        );
    }

    /// Mutation seen failing: removing rule 1 (never grouping one person's changes).
    #[test]
    fn one_person_joining_leaving_and_rejoining_is_one_line() {
        assert_eq!(
            run_texts(vec![
                change("1", "Strategy Sam", "joined the room"),
                change("2", "Strategy Sam", "left the room"),
                change("3", "Strategy Sam", "joined the room"),
            ]),
            ["Strategy Sam made 3 membership changes"]
        );
    }

    /// Mutation seen failing: removing rule 1 (never grouping one person's changes).
    #[test]
    fn two_changes_read_as_what_happened_in_order() {
        assert_eq!(
            run_texts(vec![
                change("1", "Strategy Sam", "joined the room"),
                change("2", "Strategy Sam", "left the room"),
            ]),
            ["Strategy Sam joined the room, then left the room"]
        );
    }

    /// Mutation seen failing: removing rule 1, which merges the neighbours by verb.
    #[test]
    fn a_persons_churn_does_not_swallow_the_neighbours_who_share_a_verb_with_it() {
        assert_eq!(
            run_texts(vec![
                change("1", "Alice", "joined the room"),
                change("2", "Strategy Sam", "joined the room"),
                change("3", "Strategy Sam", "left the room"),
                change("4", "Bob", "left the room"),
            ]),
            [
                "Alice joined the room",
                "Strategy Sam joined the room, then left the room",
                "Bob left the room"
            ]
        );
    }

    /// Mutation seen failing: treating any multi-row segment as churn (`end - start == 1` in rule 2).
    #[test]
    fn one_person_repeating_one_change_still_joins_a_crowd_making_it() {
        assert_eq!(
            run_texts(vec![
                change("1", "Annapurna", "updated their membership"),
                change("2", "Annapurna", "updated their membership"),
                change("3", "Surya", "updated their membership"),
            ]),
            ["Annapurna and Surya updated their membership"]
        );
    }

    /// Mutation seen failing: taking the name from `sender_short` instead of the subject.
    #[test]
    fn an_invite_and_the_join_that_follows_are_about_the_same_person() {
        assert_eq!(
            run_texts(vec![
                membership("1", "Rakesh", "was invited", Some("Strategy Sam")),
                change("2", "Strategy Sam", "joined the room"),
            ]),
            ["Strategy Sam was invited, then joined the room"]
        );
    }

    /// Mutation seen failing: not flushing the stretch at a drawn non-membership row.
    #[test]
    fn a_message_between_two_changes_keeps_them_apart() {
        assert_eq!(
            run_texts(vec![
                change("1", "Strategy Sam", "joined the room"),
                msg("m", "@a:x", 2, false),
                change("2", "Strategy Sam", "left the room"),
            ]),
            ["Strategy Sam joined the room", "Strategy Sam left the room"]
        );
    }

    /// Mutation seen failing: treating a date divider as a hidden row.
    #[test]
    fn a_date_divider_breaks_a_membership_stretch() {
        let rows = group_rows(vec![
            change("1", "A", "joined the room"),
            change("2", "B", "left the room"),
            divider(),
            change("3", "C", "joined the room"),
            change("4", "D", "left the room"),
        ]);
        assert!(rows
            .iter()
            .filter_map(|r| r.membership.as_ref())
            .all(|m| m.stretch_id.is_none()));
    }

    /// Mutation seen failing: changing the default verb.
    #[test]
    fn a_change_with_no_verb_reads_as_updated_their_membership() {
        let mut row = change("1", "Ganesha", "x");
        row.membership_verb = None;
        assert_eq!(run_texts(vec![row]), ["Ganesha updated their membership"]);
    }

    /// Mutation seen failing: taking the name from `sender_short` instead of the subject.
    #[test]
    fn a_run_names_the_person_changed_not_who_changed_them() {
        assert_eq!(
            run_texts(vec![membership("1", "Rakesh", "was invited", Some("Sam"))]),
            ["Sam was invited"]
        );
    }

    /// Mutation seen failing: taking the run id from the run's last row.
    #[test]
    fn a_growing_run_keeps_its_id() {
        let mut rows = vec![change("1", "A", "joined the room")];
        let first = group_rows(rows.clone())[0]
            .membership
            .clone()
            .unwrap()
            .run_id;
        rows.push(change("2", "B", "joined the room"));
        rows.push(change("3", "C", "joined the room"));
        assert_eq!(
            group_rows(rows)[0].membership.as_ref().unwrap().run_id,
            first
        );
    }

    /// Mutation seen failing: `MAX_NAMED` 2 to 3, and always "others".
    #[test]
    fn more_than_two_people_name_two_and_count_the_rest() {
        assert_eq!(
            run_texts(vec![
                change("1", "A", "joined the room"),
                change("2", "B", "joined the room"),
                change("3", "C", "joined the room"),
            ]),
            ["A, B and 1 other joined the room"]
        );
    }

    // --- quotes ----------------------------------------------------------

    fn hides(rows: Vec<TimelineRow>) -> Vec<bool> {
        group_rows(rows).iter().map(|r| r.hides_quote).collect()
    }

    /// Mutation seen failing: `hides_quote` forced false.
    #[test]
    fn a_reply_directly_under_its_parent_drops_the_quote() {
        assert_eq!(
            hides(vec![msg("$p", "@a:x", 1, false), reply("$r", "$p", 2)]),
            [false, true]
        );
    }

    /// Mutation seen failing: `hides_quote` forced true for any reply with a row above.
    #[test]
    fn a_reply_to_anything_further_up_keeps_it() {
        assert_eq!(
            hides(vec![
                msg("$p", "@a:x", 1, false),
                msg("$q", "@a:x", 1, false),
                reply("$r", "$p", 2),
            ]),
            [false, false, false]
        );
        assert_eq!(hides(vec![reply("$r", "$p", 2)]), [false]);
    }

    /// Mutation seen failing: letting a row with no event id become `previous_event`.
    #[test]
    fn a_local_echo_above_has_no_event_id_so_it_cannot_be_the_parent() {
        let mut echo = msg("$p", "@a:x", 1, false);
        echo.item.event_id = None;
        assert_eq!(hides(vec![echo, reply("$r", "$p", 2)]), [false, false]);
        // And it is skipped rather than standing in: the nearest row above
        // that has an event id is still the parent.
        let mut echo = msg("local", "@a:x", 1, false);
        echo.item.event_id = None;
        assert_eq!(
            hides(vec![
                msg("$p", "@a:x", 1, false),
                echo,
                reply("$r", "$p", 2)
            ]),
            [false, false, true]
        );
    }

    /// Mutation seen failing: `hides_quote` forced false.
    #[test]
    fn a_message_that_is_not_a_reply_never_has_a_quote_to_drop() {
        assert_eq!(
            hides(vec![
                msg("$p", "@a:x", 1, false),
                msg("$r", "@b:x", 2, false)
            ]),
            [false, false]
        );
    }

    /// Mutation seen failing: letting a row with no event id (the marker) become `previous_event`.
    #[test]
    fn a_reply_across_the_new_messages_line_drops_the_quote() {
        assert_eq!(
            hides(vec![
                msg("$p", "@a:x", 1, false),
                marker(),
                reply("$r", "$p", 2)
            ]),
            [false, false, true]
        );
    }

    /// Mutation seen failing: keeping `previous_event` across a membership row.
    #[test]
    fn a_membership_line_between_parent_and_reply_keeps_the_quote() {
        assert_eq!(
            hides(vec![
                msg("$p", "@a:x", 1, false),
                change("m", "Ganesha", "joined the room"),
                reply("$r", "$p", 2),
            ]),
            [false, false, false]
        );
    }

    // --- the change report -------------------------------------------------

    /// Mutation seen failing: reporting every index.
    #[test]
    fn settle_reports_only_the_rows_it_changed() {
        let mut rows = vec![msg("1", "@a:x", 1, false), msg("2", "@a:x", 2, false)];
        assert_eq!(settle(&mut rows), [1], "row 0 is already what it would be");
        assert!(
            settle(&mut rows).is_empty(),
            "a settled list changes nothing"
        );
        rows.push(msg("3", "@a:x", 3, false));
        assert_eq!(
            settle(&mut rows),
            [1, 2],
            "the new row, and the one it now follows"
        );
    }
}
