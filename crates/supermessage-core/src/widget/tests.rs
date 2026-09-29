use super::*;
use crate::dto::RoomSummary;
use crate::notification::{GateAnswers, NotificationActivity, PermissionAnswers};

const NOW: u64 = 1_800_000_000_000;
const MIN: u64 = 60 * 1000;

fn activity(kind: ActivityKind, at_ms: u64) -> NotificationActivity {
    NotificationActivity {
        kind,
        room_name: "Hermes".into(),
        room_is_agent: true,
        sender: "@agent_hermes:hs".into(),
        at_ms,
        line: None,
        gate_id: None,
        references: None,
        option_id: None,
    }
}

fn note(room: &str, event: &str, activity: NotificationActivity) -> NotificationDto {
    NotificationDto {
        activity: Some(activity),
        ..NotificationDto::generic(room, event, "Hermes")
    }
}

fn permission(room: &str, event: &str, at_ms: u64) -> NotificationDto {
    NotificationDto {
        category: NotificationCategory::Permission,
        body: "Allow   Run\nthe tests?".into(),
        permission: Some(PermissionAnswers {
            allow_option_id: "Allow once".into(),
            reject_option_id: "Reject".into(),
        }),
        ..note(room, event, activity(ActivityKind::Decision, at_ms))
    }
}

fn gate(room: &str, event: &str, gate_id: &str, at_ms: u64) -> NotificationDto {
    NotificationDto {
        category: NotificationCategory::Gate,
        body: "Approve \"Ship v2\"?".into(),
        gate: Some(GateAnswers {
            gate_id: gate_id.into(),
            prompt: "Ship v2".into(),
            option_ids: vec!["approve".into(), "request_changes".into(), "reject".into()],
        }),
        ..note(room, event, activity(ActivityKind::Decision, at_ms))
    }
}

fn message(room: &str, event: &str, line: &str, at_ms: u64) -> NotificationDto {
    note(
        room,
        event,
        NotificationActivity {
            line: Some(line.into()),
            ..activity(ActivityKind::Message, at_ms)
        },
    )
}

fn snapshot(write: &WidgetWrite) -> WidgetSnapshot {
    WidgetSnapshot::decode(Some(&write.json)).expect("a snapshot of this schema")
}

/// Apply each note in turn, from nothing.
fn pushed(notes: &[NotificationDto]) -> WidgetWrite {
    let mut json: Option<String> = None;
    let mut last = None;
    for n in notes {
        let write = apply_notification(json.as_deref(), n, NOW);
        json = Some(write.json.clone());
        last = Some(write);
    }
    last.expect("at least one note")
}

fn row(id: &str, name: &str, last_activity_ms: Option<u64>, last_message: Option<&str>) -> RoomRow {
    RoomRow::new(RoomSummary {
        id: id.into(),
        name: name.into(),
        avatar_url: None,
        unread: 0,
        last_message: last_message.map(str::to_string),
        last_message_is_own: false,
        last_message_names_sender: false,
        last_event_type: None,
        last_activity_ms,
        runtime: None,
        membership: Membership::Joined,
    })
}

// --- decisions from pushes

#[test]
fn a_permission_push_is_a_decision_with_allow_once_and_reject() {
    let write = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    assert!(write.changed && write.reload);
    let s = snapshot(&write);
    assert_eq!(s.decisions.len(), 1);
    let d = &s.decisions[0];
    assert_eq!(d.kind, WidgetDecisionKind::Permission);
    assert_eq!(d.agent, "Hermes");
    // Collapsed: one line, not the body's own whitespace.
    assert_eq!(d.question, "Allow Run the tests?");
    assert_eq!(d.asker.as_deref(), Some("@agent_hermes:hs"));
    assert_eq!(d.asked_at_ms, NOW - MIN);
    let options: Vec<(&str, bool, bool)> = d
        .options
        .iter()
        .map(|o| (o.label.as_str(), o.inline, o.declines))
        .collect();
    assert_eq!(
        options,
        vec![("Allow once", true, false), ("Reject", true, true)]
    );
    let frame = &s.frames[0];
    assert_eq!(frame.needs_you, 1);
    assert_eq!(frame.needs_you_line, "1 needs you");
    // The agent spoke just now — the question — so it is not "working".
    assert_eq!(frame.pulse, "1 needs you");
    assert_eq!(frame.states[0].tone, WidgetTone::NeedsYou);
}

#[test]
fn a_gate_offers_approve_and_reject_inline_and_request_changes_only_in_the_app() {
    let s = snapshot(&pushed(&[gate("!b:hs", "$g", "gate-9", NOW)]));
    let d = &s.decisions[0];
    assert_eq!(d.kind, WidgetDecisionKind::Gate);
    assert_eq!(d.gate_id.as_deref(), Some("gate-9"));
    assert_eq!(d.prompt, "Ship v2");
    let options: Vec<(&str, &str, bool)> = d
        .options
        .iter()
        .map(|o| (o.id.as_str(), o.label.as_str(), o.inline))
        .collect();
    assert_eq!(
        options,
        vec![
            ("approve", "Approve", true),
            ("request_changes", "Request changes", false),
            ("reject", "Reject", true),
        ]
    );
}

#[test]
fn the_same_push_twice_is_one_question_and_no_write() {
    let once = pushed(&[permission("!r:hs", "$p", NOW)]);
    let twice = apply_notification(Some(&once.json), &permission("!r:hs", "$p", NOW), NOW + MIN);
    assert!(!twice.changed);
    assert!(!twice.reload);
    assert_eq!(twice.json, once.json);
}

#[test]
fn decisions_are_bounded_newest_first_and_the_count_admits_the_rest() {
    let notes: Vec<NotificationDto> = (0..7)
        .map(|i| permission("!r:hs", &format!("$p{i}"), NOW - (10 - i) * MIN))
        .collect();
    let s = snapshot(&pushed(&notes));
    assert_eq!(s.decisions.len(), MAX_DECISIONS);
    assert_eq!(s.decisions[0].event_id, "$p6");
    assert_eq!(s.decisions[MAX_DECISIONS - 1].event_id, "$p2");
    assert!(s.overflow);
    assert_eq!(s.frames[0].needs_you_count, "5+");
    assert_eq!(s.frames[0].needs_you_line, "5+ need you");
}

// --- agents from pushes

#[test]
fn a_message_moves_its_agents_line_but_an_older_one_never_overwrites_it() {
    let newer = message("!r:hs", "$2", "  Deployed\n to staging ", NOW - MIN);
    let older = message("!r:hs", "$1", "Starting the deploy", NOW - 2 * MIN);
    let s = snapshot(&pushed(&[newer, older]));
    assert_eq!(s.agents.len(), 1);
    assert_eq!(s.agents[0].line.as_deref(), Some("Deployed to staging"));
    assert_eq!(s.agents[0].last_activity_ms, Some(NOW - MIN));
}

#[test]
fn a_line_is_bounded() {
    let long = "word ".repeat(40);
    let s = snapshot(&pushed(&[message("!r:hs", "$1", &long, NOW)]));
    let line = s.agents[0].line.clone().unwrap();
    assert_eq!(line.chars().count(), LINE_MAX_CHARS + 1);
    assert!(line.ends_with('…'));
}

#[test]
fn a_room_of_people_is_not_listed_from_a_push() {
    let mut people = message("!p:hs", "$1", "lunch?", NOW);
    people.activity.as_mut().unwrap().room_is_agent = false;
    let s = snapshot(&pushed(&[people]));
    assert!(s.agents.is_empty());
}

#[test]
fn a_turn_card_says_the_turn_finished_and_ends_its_step() {
    let mut stored = snapshot(&pushed(&[message("!r:hs", "$1", "on it", NOW - 2 * MIN)]));
    stored.agents[0].step = Some("Running the tests".into());
    let json = serde_json::to_string(&stored).unwrap();
    let turn = note(
        "!r:hs",
        "$t",
        NotificationActivity {
            line: Some("finished · 4 steps".into()),
            ..activity(ActivityKind::TurnFinished, NOW - MIN)
        },
    );
    let s = snapshot(&apply_notification(Some(&json), &turn, NOW));
    assert_eq!(s.agents[0].line.as_deref(), Some("Finished · 4 steps"));
    assert_eq!(s.agents[0].step, None);
}

// --- closing a decision

fn outcome(sender: &str, gate_id: Option<&str>, references: Option<&str>) -> NotificationDto {
    note(
        "!b:hs",
        "$o",
        NotificationActivity {
            sender: sender.into(),
            gate_id: gate_id.map(str::to_string),
            references: references.map(str::to_string),
            ..activity(ActivityKind::GateOutcome, NOW)
        },
    )
}

#[test]
fn the_boards_receipt_closes_its_gate_by_id_or_by_reference() {
    let asked = pushed(&[gate("!b:hs", "$g", "gate-9", NOW - MIN)]);
    let by_id = apply_notification(
        Some(&asked.json),
        &outcome("@agent_hermes:hs", Some("gate-9"), None),
        NOW,
    );
    assert!(snapshot(&by_id).decisions.is_empty());
    assert!(by_id.reload);

    let by_reference = apply_notification(
        Some(&asked.json),
        &outcome("@agent_hermes:hs", None, Some("$g")),
        NOW,
    );
    assert!(snapshot(&by_reference).decisions.is_empty());
}

#[test]
fn a_receipt_from_anyone_but_the_asker_closes_nothing() {
    let asked = pushed(&[gate("!b:hs", "$g", "gate-9", NOW - MIN)]);
    let forged = apply_notification(
        Some(&asked.json),
        &outcome("@mallory:hs", Some("gate-9"), None),
        NOW,
    );
    assert!(!forged.changed);
    assert_eq!(snapshot(&forged).decisions.len(), 1);
}

#[test]
fn a_receipt_for_another_gate_closes_nothing() {
    let asked = pushed(&[gate("!b:hs", "$g", "gate-9", NOW - MIN)]);
    let other = apply_notification(
        Some(&asked.json),
        &outcome("@agent_hermes:hs", Some("gate-10"), Some("$g")),
        NOW,
    );
    assert_eq!(snapshot(&other).decisions.len(), 1);
}

fn own(
    line: Option<&str>,
    gate_id: Option<&str>,
    option_id: Option<&str>,
    at_ms: u64,
) -> NotificationDto {
    note(
        "!r:hs",
        "$own",
        NotificationActivity {
            sender: "@me:hs".into(),
            line: line.map(str::to_string),
            gate_id: gate_id.map(str::to_string),
            option_id: option_id.map(str::to_string),
            ..activity(ActivityKind::OwnAnswer, at_ms)
        },
    )
}

#[test]
fn a_permission_answered_from_another_device_leaves() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    let chatter = apply_notification(
        Some(&asked.json),
        &own(Some("thanks"), None, None, NOW),
        NOW,
    );
    assert_eq!(snapshot(&chatter).decisions.len(), 1);
    let answered = apply_notification(
        Some(&asked.json),
        &own(Some(" Reject "), None, None, NOW),
        NOW,
    );
    assert!(snapshot(&answered).decisions.is_empty());
}

#[test]
fn an_answer_sent_before_the_question_is_not_its_answer() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    let earlier = apply_notification(
        Some(&asked.json),
        &own(Some("Reject"), None, None, NOW - 2 * MIN),
        NOW,
    );
    assert_eq!(snapshot(&earlier).decisions.len(), 1);
}

#[test]
fn once_nothing_is_owed_the_count_stops_admitting_more() {
    let notes: Vec<NotificationDto> = (0..6)
        .map(|i| {
            gate(
                "!b:hs",
                &format!("$g{i}"),
                &format!("gate-{i}"),
                NOW - (10 - i) * MIN,
            )
        })
        .collect();
    let mut json = pushed(&notes).json;
    assert!(snapshot_of(&json).overflow);
    for i in 1..6 {
        json = apply_notification(
            Some(&json),
            &outcome("@agent_hermes:hs", Some(&format!("gate-{i}")), None),
            NOW,
        )
        .json;
    }
    let s = snapshot_of(&json);
    assert!(s.decisions.is_empty());
    assert!(!s.overflow);
    assert_eq!(s.frames[0].needs_you_count, "0");
}

fn snapshot_of(json: &str) -> WidgetSnapshot {
    WidgetSnapshot::decode(Some(json)).unwrap()
}

#[test]
fn a_gate_answered_from_another_device_waits_for_the_board() {
    let mut asked_gate = gate("!r:hs", "$g", "gate-9", NOW - MIN);
    asked_gate.room_id = "!r:hs".into();
    let asked = pushed(&[asked_gate]);
    let answered = apply_notification(
        Some(&asked.json),
        &own(None, Some("gate-9"), Some("approve"), NOW),
        NOW,
    );
    let s = snapshot(&answered);
    let d = &s.decisions[0];
    let sent = d.answered.as_ref().expect("answered");
    assert_eq!(sent.option_id, "approve");
    assert_eq!(sent.line, "Sent: Approve · waiting for the board");
    assert!(!sent.line.contains("Approved"));
    assert_eq!(s.frames[0].needs_you, 0);
}

// --- a widget's own buttons

#[test]
fn a_button_sends_only_what_the_snapshot_still_owes() {
    let asked = pushed(&[
        gate("!b:hs", "$g", "gate-9", NOW - MIN),
        permission("!r:hs", "$p", NOW - MIN),
    ]);
    let json = Some(asked.json.as_str());

    let approve = answer_for(json, "!b:hs", "$g", "approve").expect("an owed gate");
    assert_eq!(approve.gate_id.as_deref(), Some("gate-9"));
    assert_eq!(approve.prompt, "Ship v2");
    let allow = answer_for(json, "!r:hs", "$p", "Allow once").expect("an owed permission");
    assert_eq!(allow.gate_id, None);

    // Request changes needs words: never from a button.
    assert_eq!(answer_for(json, "!b:hs", "$g", "request_changes"), None);
    // An option the card never offered.
    assert_eq!(answer_for(json, "!r:hs", "$p", "Allow always"), None);
    // The wrong room for the event.
    assert_eq!(answer_for(json, "!r:hs", "$g", "approve"), None);
}

#[test]
fn a_gate_the_board_already_resolved_sends_nothing() {
    let asked = pushed(&[gate("!b:hs", "$g", "gate-9", NOW - MIN)]);
    let resolved = apply_notification(
        Some(&asked.json),
        &outcome("@agent_hermes:hs", Some("gate-9"), None),
        NOW,
    );
    assert_eq!(
        answer_for(Some(&resolved.json), "!b:hs", "$g", "approve"),
        None
    );
}

#[test]
fn a_tap_marks_the_decision_sent_until_the_board_says_otherwise() {
    let asked = pushed(&[gate("!b:hs", "$g", "gate-9", NOW - MIN)]);
    let tapped = mark_answered(Some(&asked.json), "!b:hs", "$g", "reject", NOW);
    assert!(tapped.changed && tapped.reload);
    let s = snapshot(&tapped);
    assert_eq!(
        s.decisions[0].answered.as_ref().unwrap().line,
        "Sent: Reject · waiting for the board"
    );
    assert_eq!(s.frames[0].needs_you, 0);
    assert_eq!(s.frames[0].needs_you_line, "Nothing needs you");
    // A second tap sends nothing.
    assert_eq!(
        answer_for(Some(&tapped.json), "!b:hs", "$g", "reject"),
        None
    );

    // The send failed: owed again.
    let failed = clear_answer(Some(&tapped.json), "!b:hs", "$g", NOW);
    assert_eq!(snapshot(&failed).frames[0].needs_you, 1);
    assert!(answer_for(Some(&failed.json), "!b:hs", "$g", "approve").is_some());
}

#[test]
fn a_sent_permission_says_so_and_then_ages_out() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    let tapped = mark_answered(Some(&asked.json), "!r:hs", "$p", "Allow once", NOW);
    assert_eq!(
        snapshot(&tapped).decisions[0]
            .answered
            .as_ref()
            .unwrap()
            .line,
        "Sent: Allow once"
    );
    let later = apply_notification(
        Some(&tapped.json),
        &message("!r:hs", "$m", "running", NOW + ANSWERED_PERMISSION_KEEP_MS),
        NOW + ANSWERED_PERMISSION_KEEP_MS,
    );
    assert!(snapshot(&later).decisions.is_empty());

    let just_before = apply_notification(
        Some(&tapped.json),
        &message(
            "!r:hs",
            "$m",
            "running",
            NOW + ANSWERED_PERMISSION_KEEP_MS - 1,
        ),
        NOW + ANSWERED_PERMISSION_KEEP_MS - 1,
    );
    assert_eq!(snapshot(&just_before).decisions.len(), 1);
}

// --- the app's roster

#[test]
fn the_roster_replaces_the_agents_and_keeps_the_decisions() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - 5 * MIN)]);
    let rows = vec![
        row(
            "!r:hs",
            "🛠 Hermes — Ops",
            Some(NOW - 5 * MIN),
            Some("hello"),
        ),
        row(
            "!a:hs",
            "✳ Atlas — Platform",
            Some(NOW - 2 * MIN),
            Some("Building"),
        ),
        row("!p:hs", "lunch", Some(NOW - MIN), Some("sandwiches")),
    ];
    let write = apply_roster(Some(&asked.json), &rows, &[], NOW, NOW);
    let s = snapshot(&write);
    let names: Vec<&str> = s.agents.iter().map(|a| a.name.as_str()).collect();
    assert_eq!(names, vec!["Atlas", "Hermes"]);
    assert_eq!(s.agents[0].line.as_deref(), Some("Building"));
    assert_eq!(s.decisions.len(), 1);
    assert_eq!(s.roster_at_ms, NOW);
}

#[test]
fn an_older_roster_never_overwrites_a_newer_one() {
    let newer = apply_roster(
        None,
        &[row(
            "!a:hs",
            "✳ Atlas — Platform",
            Some(NOW - MIN),
            Some("Newer"),
        )],
        &[],
        NOW,
        NOW,
    );
    let older = apply_roster(
        Some(&newer.json),
        &[row(
            "!a:hs",
            "✳ Atlas — Platform",
            Some(NOW - 9 * MIN),
            Some("Older"),
        )],
        &[],
        NOW - MIN,
        NOW + MIN,
    );
    assert!(!older.changed);
    assert_eq!(snapshot(&older).agents[0].line.as_deref(), Some("Newer"));

    // Not only a line: an older roster that still had a room the newer one
    // no longer lists must not bring it back.
    let resurrecting = apply_roster(
        Some(&newer.json),
        &[
            row(
                "!a:hs",
                "✳ Atlas — Platform",
                Some(NOW - 9 * MIN),
                Some("Older"),
            ),
            row("!h:hs", "🛠 Hermes — Ops", Some(NOW - 9 * MIN), Some("Gone")),
        ],
        &[],
        NOW - MIN,
        NOW + MIN,
    );
    assert!(!resurrecting.changed);
    assert_eq!(snapshot(&resurrecting).agents.len(), 1);

    let same_time = apply_roster(
        Some(&newer.json),
        &[row(
            "!a:hs",
            "✳ Atlas — Platform",
            Some(NOW),
            Some("Same moment"),
        )],
        &[],
        NOW,
        NOW + MIN,
    );
    assert_eq!(
        snapshot(&same_time).agents[0].line.as_deref(),
        Some("Same moment")
    );
}

#[test]
fn a_pushed_line_newer_than_the_roster_survives_it() {
    let roster = apply_roster(
        None,
        &[row(
            "!r:hs",
            "🛠 Hermes — Ops",
            Some(NOW - 10 * MIN),
            Some("old"),
        )],
        &[],
        NOW - 10 * MIN,
        NOW - 10 * MIN,
    );
    let push = apply_notification(
        Some(&roster.json),
        &message("!r:hs", "$m", "pushed later", NOW - MIN),
        NOW - MIN,
    );
    // The app's roster was read before that push was decided.
    let stale_roster = apply_roster(
        Some(&push.json),
        &[row(
            "!r:hs",
            "🛠 Hermes — Ops",
            Some(NOW - 10 * MIN),
            Some("old"),
        )],
        &[],
        NOW - 5 * MIN,
        NOW,
    );
    let s = snapshot(&stale_roster);
    assert_eq!(s.agents[0].line.as_deref(), Some("pushed later"));
    assert_eq!(s.agents[0].last_activity_ms, Some(NOW - MIN));
}

#[test]
fn a_room_left_takes_its_decisions_with_it() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    let mut left = row("!r:hs", "🛠 Hermes — Ops", Some(NOW), None);
    left.room.membership = Membership::Left;
    let s = snapshot(&apply_roster(Some(&asked.json), &[left], &[], NOW, NOW));
    assert!(s.decisions.is_empty());

    // A roster that does not list the room — another space chosen, or not
    // loaded yet — is not a room left.
    let elsewhere = row("!a:hs", "✳ Atlas — Platform", Some(NOW), None);
    let s = snapshot(&apply_roster(
        Some(&asked.json),
        &[elsewhere],
        &[],
        NOW,
        NOW,
    ));
    assert_eq!(s.decisions.len(), 1);
    let s = snapshot(&apply_roster(Some(&asked.json), &[], &[], NOW, NOW));
    assert_eq!(s.decisions.len(), 1);
}

#[test]
fn a_live_turn_is_working_with_its_step() {
    let rows = [row(
        "!a:hs",
        "✳ Atlas — Platform",
        Some(NOW - MIN),
        Some("x"),
    )];
    let live = [WidgetLiveTurn {
        room_id: "!a:hs".into(),
        step: "Running the tests".into(),
    }];
    let s = snapshot(&apply_roster(None, &rows, &live, NOW, NOW));
    assert_eq!(s.agents[0].step.as_deref(), Some("Running the tests"));
    assert_eq!(s.frames[0].states[0].word, "working");
    assert_eq!(s.frames[0].pulse, "1 working");
}

#[test]
fn a_step_the_app_saw_long_ago_is_not_working_now() {
    let rows = [row(
        "!a:hs",
        "✳ Atlas — Platform",
        Some(NOW - 20 * MIN),
        Some("x"),
    )];
    let live = [WidgetLiveTurn {
        room_id: "!a:hs".into(),
        step: "Running the tests".into(),
    }];
    let s = snapshot(&apply_roster(None, &rows, &live, NOW, NOW));
    assert_eq!(s.frames[0].states[0].word, "idle");
}

#[test]
fn a_room_the_roster_says_is_waiting_counts_and_reads_needs_you() {
    let mut waiting = row(
        "!a:hs",
        "✳ Atlas — Platform",
        Some(NOW - 2 * MIN),
        Some("x"),
    );
    waiting.preview = Some(crate::room_preview::RoomPreview {
        text: "Approval needed".into(),
        pending: true,
    });
    let s = snapshot(&apply_roster(None, &[waiting], &[], NOW, NOW));
    assert!(s.agents[0].roster_needs_you);
    assert_eq!(
        s.agents[0].line, None,
        "the pending line is not something it said"
    );
    assert_eq!(s.roster_waiting, 1);
    assert_eq!(s.frames[0].states[0].tone, WidgetTone::NeedsYou);
    assert_eq!(s.frames[0].needs_you, 1);
}

// --- time

#[test]
fn frames_say_when_an_agent_goes_idle_and_then_quiet() {
    let spoke = NOW - MIN;
    let rows = [row("!a:hs", "✳ Atlas — Platform", Some(spoke), Some("x"))];
    let s = snapshot(&apply_roster(None, &rows, &[], NOW, NOW));
    let states: Vec<(u64, &str, &str)> = s
        .frames
        .iter()
        .map(|f| (f.from_ms, f.states[0].word.as_str(), f.pulse.as_str()))
        .collect();
    assert_eq!(
        states,
        vec![
            (NOW, "active", "1 working"),
            (spoke + ACTIVE_WITHIN_MS + 1, "idle", "All quiet"),
            (spoke + QUIET_AFTER_MS + 1, "quiet", "All quiet"),
        ]
    );
}

#[test]
fn every_change_asks_for_a_reload_even_just_after_one() {
    // WidgetKit defers and coalesces background reloads itself; a line that
    // moves a minute after the last reload must still ask, or nothing asks
    // again until the timeline's own refresh (seen on device, 2026-09-29).
    let first = pushed(&[message("!r:hs", "$1", "one", NOW)]);
    assert!(first.reload);
    let soon = apply_notification(
        Some(&first.json),
        &message("!r:hs", "$2", "two", NOW + MIN),
        NOW + MIN,
    );
    assert!(soon.changed && soon.reload);
    let same = apply_notification(
        Some(&soon.json),
        &message("!r:hs", "$2", "two", NOW + MIN),
        NOW + 2 * MIN,
    );
    assert!(!same.changed && !same.reload);
    let asked = apply_notification(
        Some(&soon.json),
        &permission("!r:hs", "$p", NOW + 2 * MIN),
        NOW + 2 * MIN,
    );
    assert!(asked.reload);
}

// --- the open room

fn timeline_row(event_id: &str, own: bool, body: &str, at_ms: u64) -> TimelineRow {
    let sender = if own { "@me:hs" } else { "@agent_hermes:hs" };
    TimelineRow::new(crate::timeline::project_item_parts(
        event_id,
        Some(event_id),
        "message",
        Some("m.text"),
        None,
        Some(sender),
        None,
        None,
        false,
        Some(body),
        None,
        None,
        None,
        Some(at_ms),
        own,
        None,
        None,
        false,
        Vec::new(),
        Vec::new(),
    ))
}

#[test]
fn the_open_room_closes_a_card_it_shows_as_no_longer_asking() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    // The event is loaded and is no longer a decision (redacted, replaced,
    // resolved): it leaves.
    let rows = vec![timeline_row("$p", false, "never mind", NOW - MIN)];
    let s = snapshot(&apply_timeline(Some(&asked.json), "!r:hs", &rows, NOW));
    assert!(s.decisions.is_empty());

    // Not loaded: the timeline cannot say, so it stays.
    let s = snapshot(&apply_timeline(Some(&asked.json), "!r:hs", &[], NOW));
    assert_eq!(s.decisions.len(), 1);
}

#[test]
fn the_open_room_sees_a_permission_answered_in_it() {
    let asked = pushed(&[permission("!r:hs", "$p", NOW - MIN)]);
    let before = vec![timeline_row("$x", true, "Allow once", NOW - 2 * MIN)];
    let s = snapshot(&apply_timeline(Some(&asked.json), "!r:hs", &before, NOW));
    assert_eq!(
        s.decisions.len(),
        1,
        "an answer from before the question is not its answer"
    );

    let after = vec![timeline_row("$y", true, "Allow once", NOW)];
    let s = snapshot(&apply_timeline(Some(&asked.json), "!r:hs", &after, NOW));
    assert!(s.decisions.is_empty());

    let other_room = snapshot(&apply_timeline(Some(&asked.json), "!q:hs", &after, NOW));
    assert_eq!(other_room.decisions.len(), 1);
}

// --- the stored form

#[test]
fn a_snapshot_of_another_schema_is_started_afresh() {
    let old = r#"{"signedIn":true,"needsYou":3,"agents":[],"updatedAt":0}"#;
    assert_eq!(WidgetSnapshot::decode(Some(old)), None);
    // Every field of this schema, but another schema's number.
    let mut other = snapshot(&pushed(&[permission("!r:hs", "$p", NOW)]));
    other.schema = WIDGET_SCHEMA + 1;
    let other = serde_json::to_string(&other).unwrap();
    assert_eq!(WidgetSnapshot::decode(Some(&other)), None);
    let write = apply_notification(Some(old), &permission("!r:hs", "$p", NOW), NOW);
    assert_eq!(snapshot(&write).decisions.len(), 1);
}

#[test]
fn every_write_that_changes_something_is_a_newer_revision() {
    let a = pushed(&[permission("!r:hs", "$p", NOW)]);
    let b = mark_answered(Some(&a.json), "!r:hs", "$p", "Reject", NOW + 1);
    assert_eq!(snapshot(&b).revision, snapshot(&a).revision + 1);
    let out = signed_out(Some(&b.json), NOW + 2);
    let s = snapshot(&out);
    assert!(!s.signed_in);
    assert!(s.decisions.is_empty());
    assert_eq!(s.revision, snapshot(&b).revision + 1);
}

#[test]
fn the_json_is_camel_case_for_the_widgets_mirror() {
    let write = pushed(&[gate("!b:hs", "$g", "gate-9", NOW)]);
    let value: serde_json::Value = serde_json::from_str(&write.json).unwrap();
    assert_eq!(value["schema"], WIDGET_SCHEMA);
    assert!(value["decisions"][0]["askedAtMs"].is_u64());
    assert_eq!(value["decisions"][0]["kind"], "gate");
    assert!(value["frames"][0]["needsYouLine"].is_string());
}
