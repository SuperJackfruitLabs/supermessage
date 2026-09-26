//! The exact strings the hosts' preview fixtures hard-code.
//!
//! ## Why this file exists
//!
//! Three times in one afternoon a fixture claimed a value the core does not
//! produce, and every time the preview built on it looked entirely plausible:
//!
//! - a roster row whose `identity.name` kept its glyph and whose `initial`
//!   was a letter, when the core strips the one and uses the other — so the
//!   frame used to check the amber rule was a screen the product does not
//!   have;
//! - `matrix-rust-sdk` shown verbatim, when the core humanises it to
//!   `Matrix Rust Sdk`;
//! - a person and a typing line both named with a raw Matrix id, when the
//!   core names them from a bounded localpart. That one was reported as a
//!   product defect — a row printing the same string twice — and no such
//!   defect exists.
//!
//! **No test could catch that.** The fixtures live in Swift, Kotlin and
//! TypeScript, on the far side of an FFI boundary from the functions that
//! decide these strings, and each was written by reading the Rust rather than
//! running it.
//!
//! This is the cheapest thing that can: the values are pinned *here*, next to
//! the code that produces them, so a change to a naming rule fails a Rust test
//! that names the host files to update. It cannot prove a fixture matches —
//! nothing can, across three languages — but it makes the drift loud on the
//! side where it starts.
//!
//! **Keep the list in step by hand.** A fixture added on a host without a line
//! here is a fixture nothing is watching.

use supermessage_core::display_name::{sender_parts, user_label};
use supermessage_core::people::project_person_parts;
use supermessage_core::room_identity::parse_room_identity;

/// Roster rows, in `PreviewFixtures.roster` on both native platforms.
#[test]
fn roster_identities() {
    let cases = [
        // raw name,                 glyph,       name,              role,          initial
        (
            "⌘ Superpipeline — Delivery",
            Some("⌘"),
            "Superpipeline",
            Some("Delivery"),
            "⌘",
        ),
        (
            "✳ Atlas — Platform",
            Some("✳"),
            "Atlas",
            Some("Platform"),
            "✳",
        ),
        (
            "✒ Quill — Writing",
            Some("✒"),
            "Quill",
            Some("Writing"),
            "✒",
        ),
        ("Estate Planning", None, "Estate Planning", None, "E"),
        // Humanised: a machine-written name is not shown verbatim.
        ("matrix-rust-sdk", None, "Matrix Rust Sdk", None, "M"),
    ];

    for (raw, glyph, name, role, initial) in cases {
        let id = parse_room_identity(raw);
        assert_eq!(id.glyph.as_deref(), glyph, "glyph of {raw:?}");
        assert_eq!(id.name, name, "name of {raw:?}");
        assert_eq!(id.role.as_deref(), role, "role of {raw:?}");
        assert_eq!(id.initial, initial, "initial of {raw:?}");
    }
}

/// The directory, in `PreviewFixtures.people`.
#[test]
fn people_directory() {
    let atlas = project_person_parts(
        "@atlas:example.org",
        Some("✳ Atlas — Platform (claude-code @ foundry)"),
        None,
    );
    // The glyph belongs to the face here too, as it does on a timeline row.
    assert_eq!(atlas.initial, "✳");
    assert_eq!(atlas.name, "Atlas — Platform");
    let runtime = atlas.runtime.expect("an agent has a runtime");
    // Humanised on the way out — the fixtures said `claude-code`/`foundry`.
    assert_eq!(runtime.harness, "Claude Code");
    assert_eq!(runtime.host, "Foundry");

    let krishna = project_person_parts("@krishna:example.org", Some("Krishna"), None);
    assert_eq!(krishna.name, "Krishna");
    assert_eq!(krishna.initial, "K", "no glyph, so a letter");
    assert!(krishna.runtime.is_none(), "a colleague has no runtime");

    // No display name: named from the localpart, and **bounded**. The fixture
    // that claimed the whole id here is what made a row look like it printed
    // the same string twice.
    let unnamed = project_person_parts("@9247e5a1b3c4:id.agentpod.dev", None, None);
    assert_eq!(unnamed.name, "9247e5…");
    assert_eq!(unnamed.initial, "9", "the localpart's first character");
    assert!(
        unnamed.name.chars().count() < 12,
        "the fallback is bounded, so a row cannot be overflowed by an id"
    );
}

/// The typing line, in `src/lib/fixtures/live.ts`.
#[test]
fn typing_label_for_someone_with_no_display_name() {
    // What `timeline::typing_users` composes: the localpart fallback, then
    // the same naming rules as everything else.
    let raw = user_label("@9247e5a1b3c4:id.agentpod.dev");
    assert_eq!(sender_parts(&raw).0, "9247e5…");
}

/// The sender attribution, in every timeline fixture on both platforms.
#[test]
fn sender_parts_split_the_glyph_off() {
    let (head, runtime) = sender_parts("✳ Atlas — Platform (claude-code @ foundry)");
    assert_eq!(head, "✳ Atlas — Platform");
    assert_eq!(runtime.as_deref(), Some("Claude Code on Foundry"));

    // And the face's half, which the hosts read as `sender_initial`.
    let (initial, name) = supermessage_core::room_identity::sender_face_parts(&head);
    assert_eq!(initial, "✳");
    assert_eq!(name, "Atlas — Platform");
}

/// krishna's failed turn, in `TurnErrorFixtures.swift` (iOS),
/// `TurnErrorPresentationTests.swift` (iOS kit) and `fixtures/turnError.ts`
/// (web). Parsed from the payload the hub sent, so the strings those fixtures
/// hard-code are the ones the core actually produces.
#[test]
fn turn_error_card() {
    use supermessage_core::turn_error::{parse_turn_error, TurnErrorKind};

    let routed = "Request is missing x-opencode-session and cannot be routed efficiently.";
    let qwen = serde_json::json!({
        "provider": "opencode-go", "model": "qwen3.7-plus", "kind": "bad_request", "message": routed,
    });
    let payload = serde_json::json!({
        "schema_version": 1,
        "kind": "quota",
        "message": "You've reached your weekly (7-day) usage limit.",
        "harness": "openclaw",
        "provider": "kimi-coding",
        "model": "k2p6",
        "retryable": false,
        "attempts": [
            { "provider": "kimi-coding", "model": "k2p6", "kind": "quota",
              "message": "You've reached your weekly (7-day) usage limit." },
            { "provider": "opencode-go", "model": "hy3-preview", "kind": "bad_request", "message": routed },
            qwen.clone(), qwen.clone(), qwen.clone(), qwen,
        ]
    });
    let card = parse_turn_error(&payload).expect("the hub's card parses");

    assert_eq!(card.label, "Usage limit reached");
    assert_eq!(card.source.as_deref(), Some("kimi-coding / k2p6"));
    assert_eq!(card.headline, "Usage limit reached · kimi-coding / k2p6");
    assert_eq!(
        card.message,
        "You've reached your weekly (7-day) usage limit."
    );
    let lines: Vec<(&str, TurnErrorKind, &str, u32)> = card
        .attempts
        .iter()
        .map(|a| (a.source.as_str(), a.kind, a.label.as_str(), a.count))
        .collect();
    assert_eq!(
        lines,
        vec![
            (
                "kimi-coding / k2p6",
                TurnErrorKind::Quota,
                "Usage limit reached",
                1
            ),
            (
                "opencode-go / hy3-preview",
                TurnErrorKind::BadRequest,
                "Request rejected",
                1
            ),
            (
                "opencode-go / qwen3.7-plus",
                TurnErrorKind::BadRequest,
                "Request rejected",
                4
            ),
        ]
    );
}
