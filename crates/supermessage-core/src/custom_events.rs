//! The custom-event rendering registry.
//!
//! Suite events — Kaambaan cards and runs, permission requests, station
//! status — arrive as `kind: "customMessage"` timeline items, with
//! `TimelineItemDto::detail` carrying the Matrix event type and
//! `custom_payload` its bounded `content` object. This module turns one of
//! those into something a host can draw, and it is where **a permission
//! request becomes a decision** — wedge #3 in `docs/positioning.md`, and the
//! reason a decision is the only place amber ever appears.
//!
//! Ported from `$lib/components/customEvents.ts`. It moved into the core
//! because it parses arbitrary JSON from anyone who can send to the room, and
//! three hand-written copies of that — desktop, iOS, Android — would agree
//! only by convention and would drift. The drift renders a wrong approval
//! prompt, which nobody notices until it matters.
//!
//! This module does not — and must not — invent suite schemas. It builds the
//! seam so that landing one is a `register` call rather than a refactor.
//!
//! ## Versioning — the decision this module encodes
//!
//! Two axes, because they answer two different questions:
//!
//! - **Major version, in the event type string itself** (the trailing `.v1`,
//!   `.v2`, … in `dev.supermessage.demo.note.v1`). A breaking change — a
//!   field renamed, retyped, or made non-optional — mints a *new* event type.
//!   This is how Matrix itself handles incompatible changes, and it means an
//!   old client's dispatch is a single map lookup: an unrecognised major
//!   version is indistinguishable from an unrecognised type, which is a case
//!   the fallback chain already has to handle.
//! - **Minor version, as a `schema_version` integer inside `content`.** An
//!   additive, backward-compatible change bumps this without changing the
//!   type. A renderer that only reads the fields it was written against
//!   tolerates a higher `schema_version` for free — it simply never looks at
//!   the new field. [`resolve_custom_event`] still calls the renderer for a
//!   newer-than-known version (best effort) and marks the result
//!   `newer_version`, so a host can note it rather than silently pretend
//!   nothing changed.
//!
//! Rejected alternatives, recorded so the choice can be checked:
//!
//! - **Version only in the type string.** Simpler, but a client one minor
//!   version behind then treats a purely additive change as a wholly unknown
//!   type, and every new optional field forces a new type on every client.
//! - **Version only inside `content`.** Cheaper to extend, but a breaking
//!   change silently reuses a type an old client already has a renderer for.
//!   That renderer runs unmodified against a shape it was never written for —
//!   a client that *thinks* it understands the payload, rendering subtly
//!   wrong output instead of visibly degrading.
//! - **Both, expressed as one field** (`content.schemaVersion: "2.3"`).
//!   Nothing then distinguishes "old client, ignore this" from "old client,
//!   this is incompatible" without parsing the major component anyway — the
//!   type-suffix convention with extra steps and no dispatch by lookup.
//!
//! `schema_version`, not `schemaVersion`: this `content` is suite-shared wire
//! format, so it follows the wire's snake_case convention rather than this
//! codebase's. That is this module's assumption pending Kaambaan's actual
//! co-designed schema, not a demand on it — if their schema lands with a
//! different name, only [`read_schema_version`] changes.
//!
//! ## Why renderers never recurse
//!
//! A renderer reads **named fields, one level at a time** — the shape
//! [`safe_string_field`] exists to make easy. That single discipline is what
//! makes a huge or deeply nested payload harmless *without* a runtime depth
//! or size guard: a renderer that never descends cannot be made to descend a
//! thousand levels. Everything a renderer returns is **text only**. No host
//! may route it into markup, a link target, an image source, or a style.
//!
//! ## What is different from the TypeScript, and why
//!
//! The TypeScript's `boundDecision` took `unknown` and checked every level,
//! because the realistic mistake there was a renderer echoing
//! `content.decision` straight off an untrusted payload — TypeScript's
//! guarantee stops at the module edge. Here the trait returns a typed
//! [`CustomEventDecision`], so a renderer *cannot* produce a malformed one:
//! the arms for "not an object", "prompt is not a string", "options is not an
//! array" and the function-valued case are unrepresentable rather than
//! unwritten. What remains representable — the option cap, the length bounds,
//! and dropping a decision with no valid options — is still enforced and
//! still tested.
//!
//! The TypeScript also wrapped `render` in a `try`/`catch`, since a renderer
//! could throw. A Rust renderer returns a value or `None`; there is no
//! exception to catch, and the fall-through is exercised through those
//! returns instead. A panicking renderer is not caught, deliberately: the
//! three renderers here are in-tree, non-recursive and non-coercing, and
//! `catch_unwind` around them would suggest a boundary that does not exist —
//! the FFI layer above cannot survive a panic cleanly either way.

use std::collections::HashMap;

use serde_json::Value;

/// How many fields one card may show.
///
/// Applied to *every* renderer's result, not just its own output, so a future
/// renderer that forgets to bound itself still cannot blow out the layout.
const FIELD_MAX_COUNT: usize = 12;

/// Long enough to show a real sentence, short enough that a card stays a
/// summary rather than becoming a log viewer.
const FIELD_VALUE_MAX_CHARS: usize = 300;

const FIELD_LABEL_MAX_CHARS: usize = 60;

/// An option is a *button*, and a row of them is a decision a person has to
/// make at a glance.
const DECISION_MAX_OPTIONS: usize = 4;

/// One labelled row on a card. Both halves are display text.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct CustomEventField {
    pub label: String,
    pub value: String,
}

/// One answer the reader can give.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct CustomEventDecisionOption {
    /// Display text, bounded like any other field.
    pub label: String,
    /// An **identifier**, never rendered: it is handed back verbatim when the
    /// reader answers. Deliberately not truncated — silently shortening
    /// `approve-restart-hermes-gateway…` would produce a value the far end
    /// has never heard of, which is a wrong answer sent confidently and
    /// strictly worse than a long string in a callback. Its length is already
    /// bounded upstream by `timeline::CUSTOM_PAYLOAD_MAX_BYTES`.
    pub id: String,
}

/// A pending decision the reader still owes an answer to.
///
/// A **UI contract, not a wire schema** — that distinction is the point. A
/// renderer translates whatever its event type actually carries into this
/// shape; it never passes a payload object through.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct CustomEventDecision {
    pub prompt: String,
    pub options: Vec<CustomEventDecisionOption>,
}

/// What a renderer returns.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CustomEventRenderResult {
    /// The rows to show. Empty means "this renderer could do nothing useful
    /// with the payload", which [`resolve_custom_event`] treats exactly like
    /// an unrecognised type.
    pub fields: Vec<CustomEventField>,
    pub decision: Option<CustomEventDecision>,
}

/// The outcome of the whole fallback chain — what a host switches on.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "status")]
pub enum CustomEventView {
    Rendered {
        fields: Vec<CustomEventField>,
        /// The payload declared a `schema_version` above what this renderer
        /// knows. Rendered anyway, best effort, and flagged.
        newer_version: bool,
        /// Always present on this variant so a host never has to distinguish
        /// "no decision" from "this variant has no such field". Only this
        /// variant can carry one: the other two mean no renderer produced
        /// anything, so nothing could have set a decision.
        decision: Option<CustomEventDecision>,
    },
    /// No renderer produced anything, but the event carried a plain-text
    /// `body` fallback, as Matrix convention asks of a custom event.
    FallbackBody { text: String },
    /// Nothing usable at all. Never empty — an empty card reads as a
    /// rendering fault rather than as an unsupported event.
    Placeholder { text: String },
}

/// A renderer for one event type (major version baked into the type string).
pub trait CustomEventRenderer: Send + Sync {
    fn event_type(&self) -> &str;

    /// The highest `schema_version` this renderer was written against.
    fn max_known_schema_version(&self) -> f64;

    /// Turn a payload into rows. `content` is arbitrary JSON from anyone who
    /// can send to the room: read named fields one level at a time, never
    /// coerce, never recurse.
    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult;
}

/// Event type → renderer.
#[derive(Default)]
pub struct CustomEventRegistry {
    renderers: HashMap<String, Box<dyn CustomEventRenderer>>,
}

impl CustomEventRegistry {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a renderer, replacing any already registered for its type.
    pub fn register(&mut self, renderer: Box<dyn CustomEventRenderer>) {
        self.renderers
            .insert(renderer.event_type().to_string(), renderer);
    }

    pub fn get(&self, event_type: &str) -> Option<&dyn CustomEventRenderer> {
        self.renderers.get(event_type).map(AsRef::as_ref)
    }

    pub fn len(&self) -> usize {
        self.renderers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.renderers.is_empty()
    }
}

/// Truncate to `max_chars` code points, appending an ellipsis when it bites.
///
/// Cosmetic only — the bound that actually protects this process lives in the
/// core, at `timeline::CUSTOM_PAYLOAD_MAX_BYTES`, before any of this runs.
fn bound_text(value: &str, max_chars: usize) -> String {
    if value.chars().count() <= max_chars {
        return value.to_string();
    }
    let head: String = value.chars().take(max_chars).collect();
    format!("{head}…")
}

fn bound_fields(fields: Vec<CustomEventField>) -> Vec<CustomEventField> {
    fields
        .into_iter()
        .take(FIELD_MAX_COUNT)
        .map(|field| CustomEventField {
            label: bound_text(&field.label, FIELD_LABEL_MAX_CHARS),
            value: bound_text(&field.value, FIELD_VALUE_MAX_CHARS),
        })
        .collect()
}

/// Bound a renderer's decision, or drop it.
///
/// Everything that survives ends up in a bordered, amber, clickable object —
/// the highest-value surface in the timeline to get wrong — so a decision
/// with no options is dropped entirely rather than rendered as a card with no
/// answer on it.
///
/// The option cap counts **valid** options rather than raw entries, which
/// matters for the same reason it did in the TypeScript: capping first would
/// let junk entries crowd out real ones.
fn bound_decision(decision: Option<CustomEventDecision>) -> Option<CustomEventDecision> {
    let decision = decision?;
    let options: Vec<CustomEventDecisionOption> = decision
        .options
        .into_iter()
        .filter(|option| !option.id.is_empty() && !option.label.is_empty())
        .take(DECISION_MAX_OPTIONS)
        .map(|option| CustomEventDecisionOption {
            label: bound_text(&option.label, FIELD_LABEL_MAX_CHARS),
            // Not bounded. See the field's doc comment.
            id: option.id,
        })
        .collect();
    if options.is_empty() {
        return None;
    }
    Some(CustomEventDecision {
        prompt: bound_text(&decision.prompt, FIELD_VALUE_MAX_CHARS),
        options,
    })
}

/// Read `content[key]` as a string, one level deep, bounded.
///
/// The shape every renderer copies. `None` when `content` is not an object,
/// the key is absent, or the value is not a string — a hostile or malformed
/// payload degrades to "nothing here", never to a coercion that could turn an
/// attacker-controlled object into text that looks deliberate.
pub fn safe_string_field(content: &Value, key: &str, max_chars: usize) -> Option<String> {
    let value = content.as_object()?.get(key)?;
    value.as_str().map(|s| bound_text(s, max_chars))
}

/// The numeric [`safe_string_field`]. Named fields, one level, no coercion.
pub fn safe_number_field(content: &Value, key: &str) -> Option<f64> {
    content.as_object()?.get(key)?.as_f64()
}

/// `content.schema_version` as a number, or `None`.
///
/// Absent or malformed is treated as "assume the baseline version", never as
/// "newer than known".
fn read_schema_version(content: &Value) -> Option<f64> {
    safe_number_field(content, "schema_version")
}

/// The whole fallback chain, as one pure function.
///
/// Known type and a renderer that produced something → render it. Known type
/// but the renderer produced nothing → the plain-text `body`. Unknown type →
/// the plain-text `body`. No body → the generic placeholder. It never returns
/// anything that lets a host render *nothing* for a `customMessage` item.
///
/// `registry` is a parameter rather than a module-level singleton so that the
/// whole dispatch/fallback/version-tolerance behaviour is testable against a
/// small fixture registry.
pub fn resolve_custom_event(
    registry: &CustomEventRegistry,
    event_type: Option<&str>,
    content: Option<&Value>,
    body: Option<&str>,
) -> CustomEventView {
    let null = Value::Null;
    let content = content.unwrap_or(&null);

    if let Some(renderer) = event_type.and_then(|t| registry.get(t)) {
        let result = renderer.render(content, body);
        let fields = bound_fields(result.fields);
        if !fields.is_empty() {
            let newer_version = read_schema_version(content)
                .is_some_and(|version| version > renderer.max_known_schema_version());
            return CustomEventView::Rendered {
                fields,
                newer_version,
                decision: bound_decision(result.decision),
            };
        }
    }

    if let Some(text) = body.filter(|b| !b.trim().is_empty()) {
        return CustomEventView::FallbackBody {
            text: text.to_string(),
        };
    }
    CustomEventView::Placeholder {
        text: format!("Custom event ({})", event_type.unwrap_or("unknown")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// A fixture renderer that reads one named field, like a real one.
    struct Fixture {
        event_type: &'static str,
        max_version: f64,
        result: CustomEventRenderResult,
    }

    impl Fixture {
        fn new(event_type: &'static str) -> Self {
            Self {
                event_type,
                max_version: 1.0,
                result: CustomEventRenderResult::default(),
            }
        }

        fn with_fields(mut self, fields: Vec<(&str, &str)>) -> Self {
            self.result.fields = fields
                .into_iter()
                .map(|(label, value)| CustomEventField {
                    label: label.to_string(),
                    value: value.to_string(),
                })
                .collect();
            self
        }

        fn with_decision(mut self, decision: CustomEventDecision) -> Self {
            self.result.decision = Some(decision);
            self
        }
    }

    impl CustomEventRenderer for Fixture {
        fn event_type(&self) -> &str {
            self.event_type
        }
        fn max_known_schema_version(&self) -> f64 {
            self.max_version
        }
        fn render(&self, _content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
            self.result.clone()
        }
    }

    /// A renderer that reads `title` off the payload, so hostile-payload
    /// behaviour is exercised through a real read rather than a canned value.
    struct TitleReader;

    impl CustomEventRenderer for TitleReader {
        fn event_type(&self) -> &str {
            "test.title.v1"
        }
        fn max_known_schema_version(&self) -> f64 {
            1.0
        }
        fn render(&self, content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
            match safe_string_field(content, "title", FIELD_VALUE_MAX_CHARS) {
                Some(title) => CustomEventRenderResult {
                    fields: vec![CustomEventField {
                        label: "Title".into(),
                        value: title,
                    }],
                    decision: None,
                },
                None => CustomEventRenderResult::default(),
            }
        }
    }

    fn registry_with(renderers: Vec<Box<dyn CustomEventRenderer>>) -> CustomEventRegistry {
        let mut registry = CustomEventRegistry::new();
        for renderer in renderers {
            registry.register(renderer);
        }
        registry
    }

    fn option(id: &str, label: &str) -> CustomEventDecisionOption {
        CustomEventDecisionOption {
            id: id.to_string(),
            label: label.to_string(),
        }
    }

    // ---- dispatch --------------------------------------------------------

    #[test]
    fn renders_through_the_registered_renderer_for_a_known_type() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "hello")]),
        )]);
        assert_eq!(
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None),
            CustomEventView::Rendered {
                fields: vec![CustomEventField {
                    label: "Note".into(),
                    value: "hello".into()
                }],
                newer_version: false,
                decision: None,
            }
        );
    }

    #[test]
    fn picks_the_renderer_matching_the_exact_event_type_among_several() {
        let registry = registry_with(vec![
            Box::new(Fixture::new("test.a.v1").with_fields(vec![("A", "a")])),
            Box::new(Fixture::new("test.b.v1").with_fields(vec![("B", "b")])),
        ]);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.b.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields[0].label, "B");
    }

    #[test]
    fn falls_back_to_the_plain_text_body_for_an_unknown_type() {
        let registry = CustomEventRegistry::new();
        assert_eq!(
            resolve_custom_event(&registry, Some("test.unknown.v1"), None, Some("a note")),
            CustomEventView::FallbackBody {
                text: "a note".into()
            }
        );
    }

    #[test]
    fn falls_back_to_the_generic_placeholder_for_an_unknown_type_with_no_body() {
        let registry = CustomEventRegistry::new();
        assert_eq!(
            resolve_custom_event(&registry, Some("test.unknown.v1"), None, None),
            CustomEventView::Placeholder {
                text: "Custom event (test.unknown.v1)".into()
            }
        );
    }

    #[test]
    fn names_unknown_in_the_placeholder_when_there_is_no_event_type_at_all() {
        let registry = CustomEventRegistry::new();
        assert_eq!(
            resolve_custom_event(&registry, None, None, None),
            CustomEventView::Placeholder {
                text: "Custom event (unknown)".into()
            }
        );
    }

    #[test]
    fn falls_back_to_the_body_when_a_known_renderer_produces_no_fields() {
        // A renderer that could do nothing with the payload is treated
        // exactly like an unrecognised type. This is also how the Rust port
        // exercises what the TypeScript's try/catch covered: a Rust renderer
        // cannot throw, so "produced nothing" is the whole of that path.
        let registry = registry_with(vec![Box::new(Fixture::new("test.a.v1"))]);
        assert_eq!(
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), Some("body")),
            CustomEventView::FallbackBody {
                text: "body".into()
            }
        );
    }

    #[test]
    fn falls_back_to_the_placeholder_when_a_renderer_produces_nothing_and_there_is_no_body() {
        let registry = registry_with(vec![Box::new(Fixture::new("test.a.v1"))]);
        assert_eq!(
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None),
            CustomEventView::Placeholder {
                text: "Custom event (test.a.v1)".into()
            }
        );
    }

    #[test]
    fn treats_a_whitespace_only_body_the_same_as_no_body() {
        let registry = CustomEventRegistry::new();
        assert_eq!(
            resolve_custom_event(&registry, Some("test.x.v1"), None, Some("   \n  ")),
            CustomEventView::Placeholder {
                text: "Custom event (test.x.v1)".into()
            }
        );
    }

    // ---- version tolerance ----------------------------------------------

    #[test]
    fn is_not_newer_for_a_payload_at_or_below_the_renderers_known_version() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "x")]),
        )]);
        for version in [json!(1), json!(0)] {
            let CustomEventView::Rendered { newer_version, .. } = resolve_custom_event(
                &registry,
                Some("test.a.v1"),
                Some(&json!({ "schema_version": version })),
                None,
            ) else {
                panic!("expected a rendered view");
            };
            assert!(!newer_version, "version {version} was marked newer");
        }
    }

    #[test]
    fn still_renders_best_effort_and_marks_a_newer_schema_version() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "x")]),
        )]);
        let CustomEventView::Rendered {
            fields,
            newer_version,
            ..
        } = resolve_custom_event(
            &registry,
            Some("test.a.v1"),
            Some(&json!({ "schema_version": 2 })),
            None,
        )
        else {
            panic!("expected a rendered view");
        };
        assert!(newer_version);
        assert_eq!(fields.len(), 1, "a newer version must still render");
    }

    #[test]
    fn treats_a_missing_schema_version_as_the_baseline_not_as_newer() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "x")]),
        )]);
        let CustomEventView::Rendered { newer_version, .. } =
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert!(!newer_version);
    }

    #[test]
    fn ignores_a_non_numeric_schema_version_rather_than_treating_it_as_newer() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "x")]),
        )]);
        for version in [json!("2"), json!(null), json!({"v": 2}), json!([2])] {
            let CustomEventView::Rendered { newer_version, .. } = resolve_custom_event(
                &registry,
                Some("test.a.v1"),
                Some(&json!({ "schema_version": version })),
                None,
            ) else {
                panic!("expected a rendered view");
            };
            assert!(!newer_version, "{version} was treated as a version");
        }
    }

    // ---- hostile payloads render inert ----------------------------------

    #[test]
    fn degrades_when_a_field_expected_to_be_a_string_is_an_object_or_a_number() {
        let registry = registry_with(vec![Box::new(TitleReader)]);
        for title in [
            json!({"nested": "x"}),
            json!(42),
            json!([1, 2]),
            json!(null),
        ] {
            assert_eq!(
                resolve_custom_event(
                    &registry,
                    Some("test.title.v1"),
                    Some(&json!({ "title": title })),
                    Some("fallback"),
                ),
                CustomEventView::FallbackBody {
                    text: "fallback".into()
                },
                "a {title} title was coerced instead of refused"
            );
        }
    }

    #[test]
    fn degrades_when_content_itself_is_not_an_object() {
        let registry = registry_with(vec![Box::new(TitleReader)]);
        for content in [json!([1, 2]), json!("a string"), json!(7), json!(null)] {
            assert_eq!(
                resolve_custom_event(
                    &registry,
                    Some("test.title.v1"),
                    Some(&content),
                    Some("fallback"),
                ),
                CustomEventView::FallbackBody {
                    text: "fallback".into()
                },
                "content {content} was walked as an object"
            );
        }
    }

    /// Build a payload the way production does: as text, then parsed.
    fn parse_nested(depth: usize) -> Result<Value, serde_json::Error> {
        let text = format!(
            r#"{{"title":"the real one","trap":{}"deep"{}}}"#,
            r#"{"nested":"#.repeat(depth),
            "}".repeat(depth)
        );
        serde_json::from_str(&text)
    }

    #[test]
    fn a_payload_nested_past_serde_jsons_limit_never_becomes_a_value_at_all() {
        // Where the protection against a pathologically deep payload actually
        // lives, which is worth pinning because it is not where you would
        // first look. A renderer reading named fields one level at a time
        // cannot be made to descend — but `serde_json::Value` is itself a
        // recursive Rust type, and merely *dropping* a few thousand levels of
        // it overflows the stack before any renderer runs.
        //
        // That never happens in production because the payload arrives as
        // text and serde_json's parser refuses to build it: `custom_payload`
        // is then `None` and the item falls through to its body. Found by
        // this test aborting the whole suite when it constructed the value
        // programmatically instead, which is not a path any real input takes.
        assert!(
            parse_nested(1_000).is_err(),
            "serde_json accepted a 1000-deep payload; the recursion limit that \
             keeps a hostile payload from reaching a Value is gone"
        );
    }

    #[test]
    fn resolves_a_deep_but_parseable_payload_by_reading_only_its_shallow_field() {
        // Just inside the parser's limit: the renderer must still cost
        // nothing, because it never looks at `trap`.
        let payload = parse_nested(100).expect("100 levels parses");
        let registry = registry_with(vec![Box::new(TitleReader)]);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.title.v1"), Some(&payload), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields[0].value, "the real one");
    }

    // ---- field bounding --------------------------------------------------

    #[test]
    fn caps_an_overlong_field_value_and_appends_an_ellipsis() {
        let long = "x".repeat(FIELD_VALUE_MAX_CHARS + 200);
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", &long)]),
        )]);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields[0].value.chars().count(), FIELD_VALUE_MAX_CHARS + 1);
        assert!(fields[0].value.ends_with('…'));
    }

    #[test]
    fn caps_an_overlong_label_too_not_just_the_value() {
        let long = "L".repeat(FIELD_LABEL_MAX_CHARS + 40);
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![(&long, "v")]),
        )]);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields[0].label.chars().count(), FIELD_LABEL_MAX_CHARS + 1);
        assert!(fields[0].label.ends_with('…'));
    }

    #[test]
    fn caps_the_number_of_fields_a_renderer_can_contribute() {
        let many: Vec<(&str, &str)> = (0..FIELD_MAX_COUNT + 8).map(|_| ("L", "v")).collect();
        let registry = registry_with(vec![Box::new(Fixture::new("test.a.v1").with_fields(many))]);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields.len(), FIELD_MAX_COUNT);
    }

    // ---- decision --------------------------------------------------------

    fn with_decision(decision: CustomEventDecision) -> CustomEventRegistry {
        registry_with(vec![Box::new(
            Fixture::new("test.a.v1")
                .with_fields(vec![("Wants to", "restart the gateway")])
                .with_decision(decision),
        )])
    }

    fn decision_from(registry: &CustomEventRegistry) -> Option<CustomEventDecision> {
        let CustomEventView::Rendered { decision, .. } =
            resolve_custom_event(registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        decision
    }

    #[test]
    fn passes_a_well_formed_decision_through() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow restart?".into(),
            options: vec![option("allow", "Allow"), option("deny", "Deny")],
        });
        assert_eq!(
            decision_from(&registry),
            Some(CustomEventDecision {
                prompt: "Allow restart?".into(),
                options: vec![option("allow", "Allow"), option("deny", "Deny")],
            })
        );
    }

    #[test]
    fn bounds_the_prompt_and_each_option_label() {
        let registry = with_decision(CustomEventDecision {
            prompt: "P".repeat(FIELD_VALUE_MAX_CHARS + 50),
            options: vec![option("id", &"L".repeat(FIELD_LABEL_MAX_CHARS + 50))],
        });
        let decision = decision_from(&registry).expect("a decision");
        assert_eq!(decision.prompt.chars().count(), FIELD_VALUE_MAX_CHARS + 1);
        assert_eq!(
            decision.options[0].label.chars().count(),
            FIELD_LABEL_MAX_CHARS + 1
        );
    }

    #[test]
    fn leaves_the_option_id_untruncated_because_it_is_an_identifier() {
        // A shortened id is a wrong answer sent confidently — worse than a
        // long string in a callback.
        let long_id = "approve-".repeat(40);
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![option(&long_id, "Allow")],
        });
        let decision = decision_from(&registry).expect("a decision");
        assert_eq!(decision.options[0].id, long_id);
        assert!(!decision.options[0].id.ends_with('…'));
    }

    #[test]
    fn caps_the_option_count_at_four() {
        let options: Vec<_> = (0..9).map(|i| option(&format!("id{i}"), "Yes")).collect();
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options,
        });
        assert_eq!(
            decision_from(&registry).expect("a decision").options.len(),
            DECISION_MAX_OPTIONS
        );
    }

    #[test]
    fn counts_the_option_cap_in_valid_options_not_raw_entries() {
        // Capping before validating would let junk entries crowd out real
        // ones — six blanks would hide the two answers that mattered.
        let mut options: Vec<_> = (0..6).map(|_| option("", "")).collect();
        options.push(option("allow", "Allow"));
        options.push(option("deny", "Deny"));
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options,
        });
        let decision = decision_from(&registry).expect("a decision");
        assert_eq!(decision.options.len(), 2);
        assert_eq!(decision.options[0].id, "allow");
        assert_eq!(decision.options[1].id, "deny");
    }

    #[test]
    fn drops_options_with_an_empty_id_or_label_without_dropping_their_siblings() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![
                option("", "No id"),
                option("allow", "Allow"),
                option("no-label", ""),
            ],
        });
        let decision = decision_from(&registry).expect("a decision");
        assert_eq!(decision.options, vec![option("allow", "Allow")]);
    }

    #[test]
    fn drops_a_decision_with_no_valid_options_rather_than_rendering_a_dead_card() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![option("", ""), option("", "")],
        });
        assert_eq!(decision_from(&registry), None);
    }

    #[test]
    fn drops_an_empty_decision_entirely() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![],
        });
        assert_eq!(decision_from(&registry), None);
    }

    #[test]
    fn is_none_when_a_renderer_sets_nothing() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.a.v1").with_fields(vec![("Note", "x")]),
        )]);
        assert_eq!(decision_from(&registry), None);
    }

    #[test]
    fn never_sets_a_decision_on_a_fallback_or_placeholder_view() {
        // Structural: those variants have no decision field at all, so this
        // asserts the shape rather than a value — the point being that a host
        // cannot be handed a decision it has no card to draw.
        let registry = CustomEventRegistry::new();
        assert!(matches!(
            resolve_custom_event(&registry, Some("t.v1"), None, Some("b")),
            CustomEventView::FallbackBody { .. }
        ));
        assert!(matches!(
            resolve_custom_event(&registry, Some("t.v1"), None, None),
            CustomEventView::Placeholder { .. }
        ));
    }

    // ---- registry --------------------------------------------------------

    #[test]
    fn starts_empty_when_built_with_no_renderers() {
        assert!(CustomEventRegistry::new().is_empty());
    }

    #[test]
    fn registers_a_renderer_that_resolve_can_then_find() {
        let registry = registry_with(vec![Box::new(
            Fixture::new("test.new.v1").with_fields(vec![("N", "v")]),
        )]);
        assert!(matches!(
            resolve_custom_event(&registry, Some("test.new.v1"), Some(&json!({})), None),
            CustomEventView::Rendered { .. }
        ));
    }

    #[test]
    fn replaces_an_existing_renderer_registered_under_the_same_type() {
        let registry = registry_with(vec![
            Box::new(Fixture::new("test.a.v1").with_fields(vec![("Old", "old")])),
            Box::new(Fixture::new("test.a.v1").with_fields(vec![("New", "new")])),
        ]);
        assert_eq!(registry.len(), 1);
        let CustomEventView::Rendered { fields, .. } =
            resolve_custom_event(&registry, Some("test.a.v1"), Some(&json!({})), None)
        else {
            panic!("expected a rendered view");
        };
        assert_eq!(fields[0].label, "New");
    }

    // ---- safeStringField -------------------------------------------------

    #[test]
    fn safe_string_field_reads_a_top_level_string() {
        assert_eq!(
            safe_string_field(&json!({ "title": "hi" }), "title", 100).as_deref(),
            Some("hi")
        );
    }

    #[test]
    fn safe_string_field_is_none_when_the_field_is_absent() {
        assert_eq!(
            safe_string_field(&json!({ "other": "hi" }), "title", 100),
            None
        );
    }

    #[test]
    fn safe_string_field_is_none_when_the_value_is_not_a_string() {
        for value in [
            json!(1),
            json!(true),
            json!(null),
            json!([1]),
            json!({"a": 1}),
        ] {
            assert_eq!(
                safe_string_field(&json!({ "title": value }), "title", 100),
                None,
                "{value} was coerced"
            );
        }
    }

    #[test]
    fn safe_string_field_is_none_when_content_is_not_an_object() {
        for content in [json!("s"), json!(1), json!(null), json!([1])] {
            assert_eq!(safe_string_field(&content, "title", 100), None);
        }
    }

    #[test]
    fn safe_string_field_truncates_to_max_chars_with_an_ellipsis() {
        let long = "x".repeat(50);
        let read = safe_string_field(&json!({ "title": long }), "title", 10).expect("a value");
        assert_eq!(read.chars().count(), 11);
        assert!(read.ends_with('…'));
    }

    #[test]
    fn safe_string_field_cuts_on_a_character_boundary() {
        let long = "🎉".repeat(50);
        let read = safe_string_field(&json!({ "title": long }), "title", 10).expect("a value");
        let kept = read.trim_end_matches('…');
        assert_eq!(kept.chars().count(), 10);
        assert!(kept.chars().all(|c| c == '🎉'));
    }
}
