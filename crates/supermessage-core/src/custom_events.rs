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
    /// What this decision resolves, handed back verbatim when the reader
    /// answers — a kaambaan `gate_id` today.
    ///
    /// Without it a host can draw the buttons and has nothing to name when it
    /// sends the answer. The alternative was for the host to reach past this
    /// type into the raw payload for one field, which would make every host a
    /// second parser of untrusted JSON — the exact duplication this module
    /// moved into the core to prevent.
    ///
    /// `Option`, because not every decision has one: a permission request is
    /// identified by the event it arrived on and needs no separate subject.
    pub subject: Option<String>,
}

/// What a renderer returns.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CustomEventRenderResult {
    /// The rows to show. Empty means "this renderer could do nothing useful
    /// with the payload", which [`resolve_custom_event`] treats exactly like
    /// an unrecognised type.
    pub fields: Vec<CustomEventField>,
    pub decision: Option<CustomEventDecision>,
    /// A URL the card may offer as a tappable affordance.
    ///
    /// **The one exception to "everything a renderer returns is text"**, and it
    /// is narrow on purpose. `safe_link` is the only way to set it: it accepts
    /// `https://` and nothing else, so `javascript:`, `data:`, `file:` and a
    /// bare `//host` cannot reach a host as something to open. A renderer
    /// cannot hand back an unvalidated string here without going through it.
    ///
    /// Without this the `deep_link` a gate carries is decoration — a URL
    /// printed at a reader who then has to retype it. That is worse than not
    /// sending it, because it looks like an affordance and is not.
    pub link: Option<String>,
    /// Long-form prose the card carries beside its rows — an agent's
    /// reasoning, today.
    ///
    /// Separate from [`Self::fields`] because a field is a label and a short
    /// value (300 characters), and reasoning is neither: it is paragraphs,
    /// and squeezing it into a value column would truncate the middle of a
    /// thought.
    pub reasoning: Option<String>,
}

/// The outcome of the whole fallback chain — what a host switches on.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "status")]
pub enum CustomEventView {
    Rendered {
        fields: Vec<CustomEventField>,
        /// How the agent reached this, when it said — see
        /// [`CustomEventRenderResult::reasoning`].
        ///
        /// **This is where reasoning persists.** The live channel carries it
        /// on to-device messages, which are not room history and are gone the
        /// moment the turn ends; a turn card is a real room event, so
        /// reasoning that arrives here is still there tomorrow, on every
        /// client, in its place in the conversation.
        reasoning: Option<String>,
        /// The payload declared a `schema_version` above what this renderer
        /// knows. Rendered anyway, best effort, and flagged.
        newer_version: bool,
        /// Always present on this variant so a host never has to distinguish
        /// "no decision" from "this variant has no such field". Only this
        /// variant can carry one: the other two mean no renderer produced
        /// anything, so nothing could have set a decision.
        decision: Option<CustomEventDecision>,
        /// A validated `https://` URL the host may open. See
        /// [`CustomEventRenderResult::link`] and [`safe_link`].
        link: Option<String>,
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

    /// What to call this on screen.
    ///
    /// The card used to head itself with `event_type` — `dev.agentpod.turn.v1`
    /// printed at a reader. That is an address for a schema, not a name for a
    /// thing, and a reading surface should say the latter.
    fn label(&self) -> &str;

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

/// How much reasoning a card carries.
///
/// Far above [`FIELD_VALUE_MAX_CHARS`], because this is prose rather than a
/// value — a thought cut at 300 characters is a thought nobody can follow —
/// and still bounded, because it arrives from a sender and a card is not a
/// document viewer.
const REASONING_MAX_CHARS: usize = 4_000;

/// Trim reasoning for display. Empty and absent are the same thing: a
/// disclosure that opens onto nothing says there is something to read.
fn bound_reasoning(value: Option<String>) -> Option<String> {
    let value = value?;
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(bound_text(trimmed, REASONING_MAX_CHARS))
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
        // Not bounded, for the same reason an option id is not: it is an
        // identifier handed back verbatim, and a truncated one names nothing.
        subject: decision.subject,
    })
}

/// The longest URL a card will carry.
///
/// Generous — a board deep link with ids in it is already ~60 characters — and
/// bounded anyway, because this one is handed to a host to *open* rather than
/// to draw, and an unbounded value there is a different class of problem.
const LINK_MAX_CHARS: usize = 2048;

/// Read `content[key]` as a URL a host may safely open, or `None`.
///
/// **`https://` only.** Not a style preference: `javascript:` is the classic
/// way a rendered link becomes code execution, `data:` smuggles a document
/// into a URL bar, `file:` reaches the device, and a protocol-relative
/// `//host/path` inherits whatever scheme the opener assumes. A reader tapping
/// something a card drew must not be able to land on any of those.
///
/// `http://` is refused too. Every URL this suite emits is https, so accepting
/// plaintext would only ever admit something that did not come from us.
pub fn safe_link(content: &Value, key: &str) -> Option<String> {
    let raw = content.get(key)?.as_str()?.trim();
    if raw.len() > LINK_MAX_CHARS || !raw.is_char_boundary(raw.len()) {
        return None;
    }
    // Case-insensitive: `HTTPS://` is the same scheme, and a check that missed
    // it would be a check that could be walked around by shouting.
    let lower = raw.to_ascii_lowercase();
    if !lower.starts_with("https://") {
        return None;
    }
    // A scheme and nothing else is not a destination.
    if raw.len() <= "https://".len() {
        return None;
    }
    // ASCII graphic only — every printable ASCII character except space.
    //
    // Stricter than "no control characters and no whitespace", which is what
    // this checked first and which a test caught as insufficient:
    // `char::is_control` is Unicode category **Cc**, so `U+202E` RIGHT-TO-LEFT
    // OVERRIDE is not a control character by that definition and went straight
    // through. That codepoint is the classic way a link'"'"'s displayed text is made
    // to disagree with where it goes, and it is exactly the class of thing this
    // function exists to stop.
    //
    // Rather than enumerate the format characters that matter — Cf has dozens,
    // and homoglyphs are a separate problem again — this requires the whole URL
    // to be printable ASCII. A suite deep link is percent-encoded ASCII
    // already, so nothing legitimate is lost, and the rule is one a reader can
    // check by eye. An internationalised domain would be refused; when one
    // needs to work, it arrives punycoded or this rule is revisited on purpose.
    if !raw.chars().all(|c| c.is_ascii_graphic()) {
        return None;
    }
    Some(raw.to_string())
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
                reasoning: bound_reasoning(result.reasoning),
                newer_version,
                decision: bound_decision(result.decision),
                link: result.link,
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

// ---------------------------------------------------------------------------
// The shipped renderers.
// ---------------------------------------------------------------------------

/// The demo renderer, shipped to prove the extension path end to end.
///
/// **Not** a Kaambaan schema — those are co-designed with that team, never
/// invented here. `dev.supermessage.demo.*` is a namespace this app owns for
/// exactly this purpose, so it can never collide with, or be mistaken for, a
/// genuine card, run or permission request.
///
/// Reads exactly one field: the minimum needed to demonstrate a renderer that
/// only touches named fields at a fixed depth and tolerates a payload with
/// extra unrecognised fields without any special-casing.
pub const DEMO_NOTE_EVENT_TYPE: &str = "dev.supermessage.demo.note.v1";

pub struct DemoNoteRenderer;

impl CustomEventRenderer for DemoNoteRenderer {
    fn event_type(&self) -> &str {
        DEMO_NOTE_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Note"
    }
    fn max_known_schema_version(&self) -> f64 {
        1.0
    }
    fn render(&self, content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
        match safe_string_field(content, "title", FIELD_VALUE_MAX_CHARS) {
            Some(title) => CustomEventRenderResult {
                fields: vec![CustomEventField {
                    label: "Note".into(),
                    value: title,
                }],
                reasoning: None,
                link: None,
                decision: None,
            },
            None => CustomEventRenderResult::default(),
        }
    }
}

/// What an agent did during one turn — AgentPod's `dev.agentpod.turn.v1`.
///
/// The first renderer here for a real event type rather than a demonstration.
/// It reads a bounded summary and nothing else: the wire carries at most
/// twenty tool records and a set of counts, and a card is a summary surface,
/// not a log viewer. Tool *output* never crosses the bridge at all.
///
/// Two of the fields it wants are a number and an array, which
/// [`safe_string_field`] cannot express, so it reads those itself with the
/// same discipline: check the shape, take the value, never coerce, never
/// recurse.
/// A tool's title, made readable without changing what it says.
///
/// An agent reports what it did as the argv it ran, which arrives wrapped in
/// a shell invocation, spread over continuation lines, and carrying absolute
/// paths long enough that the field cap cuts the filename off the end — the
/// one part of the path a reader wanted. Three narrow, reversible-in-meaning
/// passes fix all three: fold the whitespace, drop the shell wrapper, and
/// shorten deep paths from the front so the tail survives.
///
/// Cosmetic only, and applied after the payload has already been bounded and
/// validated — this never reads a new field or coerces a value.
fn tool_title(raw: &str) -> String {
    // A lone `\` is a line continuation with the line gone — folding leaves
    // it stranded mid-command where it reads as an argument.
    let folded = raw
        .split_whitespace()
        .filter(|token| *token != "\\")
        .collect::<Vec<_>>()
        .join(" ");
    let unwrapped = unwrap_shell(&folded);
    unwrapped
        .split(' ')
        .map(shorten_path)
        .collect::<Vec<_>>()
        .join(" ")
}

/// `bash -lc 'cargo test'` is a way of running something, not the something.
fn unwrap_shell(command: &str) -> String {
    const SHELLS: [&str; 3] = ["bash", "sh", "zsh"];
    let mut parts = command.splitn(3, ' ');
    let (Some(program), Some(flags), Some(rest)) = (parts.next(), parts.next(), parts.next())
    else {
        return command.to_string();
    };
    if !SHELLS.contains(&program) {
        return command.to_string();
    }
    // `-c`, `-lc`, `-lic` — any flag cluster ending in `c`, which is the one
    // that says "the next argument is the command".
    if !(flags.starts_with('-') && flags.ends_with('c')) {
        return command.to_string();
    }
    let inner = rest.trim();
    for quote in ['\'', '"'] {
        if let Some(body) = inner
            .strip_prefix(quote)
            .and_then(|r| r.strip_suffix(quote))
        {
            return body.to_string();
        }
    }
    inner.to_string()
}

/// `/Users/rakesh/Projects/app/crates/core/src/lib.rs` → `…/src/lib.rs`.
///
/// Only deep absolute paths, because those are the ones whose interesting end
/// gets cut off. A short path is already readable and is left exactly as it is.
fn shorten_path(token: &str) -> String {
    let bare = token.trim_start_matches('~');
    if !bare.starts_with('/') {
        return token.to_string();
    }
    let segments: Vec<&str> = bare.split('/').filter(|s| !s.is_empty()).collect();
    if segments.len() <= 2 {
        return token.to_string();
    }
    format!("…/{}", segments[segments.len() - 2..].join("/"))
}

pub const TURN_ACTIVITY_EVENT_TYPE: &str = "dev.agentpod.turn.v1";

pub struct TurnActivityRenderer;

impl CustomEventRenderer for TurnActivityRenderer {
    fn event_type(&self) -> &str {
        TURN_ACTIVITY_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Turn"
    }
    fn max_known_schema_version(&self) -> f64 {
        1.0
    }
    fn render(&self, content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
        let Some(object) = content.as_object() else {
            return CustomEventRenderResult::default();
        };
        let null = Value::Null;
        let counts = object.get("counts").unwrap_or(&null);
        let total = safe_number_field(counts, "total");
        let failed = safe_number_field(counts, "failed").unwrap_or(0.0);
        let omitted = safe_number_field(counts, "omitted").unwrap_or(0.0);

        let empty: Vec<Value> = Vec::new();
        let tools = object
            .get("tools")
            .and_then(Value::as_array)
            .unwrap_or(&empty);

        // **Where reasoning becomes permanent.** The live channel carries it
        // on to-device messages, which are not room history — they are gone
        // the moment the turn ends. A turn card is a real room event, so
        // reasoning an agent puts here is still there tomorrow, on every
        // client, in its place in the conversation.
        //
        // Read with the same discipline as everything else in this module:
        // check the shape, take the value, never coerce, never recurse.
        let reasoning = content
            .get("reasoning")
            .and_then(Value::as_str)
            .map(str::to_string);

        let mut fields = Vec::new();
        // The headline first: a reader scanning a conversation wants "it did
        // seven things and one failed" before it wants to know which seven.
        if let Some(total) = total {
            let total = total as i64;
            let noun = if total == 1 { "thing" } else { "things" };
            let failed_note = if failed > 0.0 {
                format!(", {} failed", failed as i64)
            } else {
                String::new()
            };
            fields.push(CustomEventField {
                label: "Did".into(),
                value: format!("{total} {noun}{failed_note}"),
            });
        }

        for entry in tools {
            // The field cap truncates anyway; stopping here keeps the headline
            // row from being the one that gets dropped.
            if fields.len() >= FIELD_MAX_COUNT - 1 {
                break;
            }
            let Some(title) = safe_string_field(entry, "title", FIELD_VALUE_MAX_CHARS) else {
                continue;
            };
            // The status is the label, so the card reads as a list of what
            // happened rather than a list of identical rows.
            let status = safe_string_field(entry, "status", FIELD_LABEL_MAX_CHARS);
            fields.push(CustomEventField {
                label: status.unwrap_or_else(|| "did".to_string()),
                value: tool_title(&title),
            });
        }

        if omitted > 0.0 {
            fields.push(CustomEventField {
                label: "and".into(),
                value: format!("{} more not listed", omitted as i64),
            });
        }
        CustomEventRenderResult {
            fields,
            reasoning,
            link: None,
            decision: None,
        }
    }
}

/// A permission request a reader can answer — AgentPod's
/// `dev.agentpod.permission.v1`, and the first renderer anywhere to set a
/// decision.
///
/// **The option's `id` carries its NAME, not its `option_id`.** That reads
/// backwards until you see what `id` is for: it is handed back verbatim and
/// sent, and the room transcript is a shared human record. The hub's own
/// prose prints option names alongside the numbers "because '1' alone would
/// make the transcript unreadable afterwards" — and a button that leaves
/// `allow_once` in the room is the same mistake in a different alphabet. The
/// hub's matcher accepts the number, the name or the id, so any of the three
/// would work; the name is the one a person reading the room later
/// understands.
///
/// The event is sent *beside* an ordinary prose message carrying the same
/// question, so a client that never renders this — Element, or this one
/// before the renderer existed — is exactly as able to answer as it was.
pub const PERMISSION_REQUEST_EVENT_TYPE: &str = "dev.agentpod.permission.v1";

pub struct PermissionRequestRenderer;

impl CustomEventRenderer for PermissionRequestRenderer {
    fn event_type(&self) -> &str {
        PERMISSION_REQUEST_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Permission"
    }
    fn max_known_schema_version(&self) -> f64 {
        1.0
    }
    fn render(&self, content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
        let Some(title) = safe_string_field(content, "title", FIELD_VALUE_MAX_CHARS) else {
            return CustomEventRenderResult::default();
        };

        let mut options = Vec::new();
        if let Some(raw) = content.get("options").and_then(Value::as_array) {
            for entry in raw {
                // An option with no name is one nothing could label, and
                // nothing could be answered with.
                let Some(name) = safe_string_field(entry, "name", FIELD_LABEL_MAX_CHARS) else {
                    continue;
                };
                options.push(CustomEventDecisionOption {
                    id: name.clone(),
                    label: name,
                });
            }
        }

        let fields = vec![CustomEventField {
            label: "Wants to".into(),
            value: title.clone(),
        }];
        if options.is_empty() {
            // Nothing to decide. `bound_decision` would reject an empty list
            // anyway; the card falls back to describing the request.
            return CustomEventRenderResult {
                fields,
                reasoning: None,
                link: None,
                decision: None,
            };
        }
        CustomEventRenderResult {
            fields,
            reasoning: None,
            link: None,
            decision: Some(CustomEventDecision {
                prompt: format!("Allow {title}?"),
                options,
                subject: None,
            }),
        }
    }
}

/// A kaambaan approval gate a reader can answer — `dev.kaambaan.gate.v1`.
///
/// **The option `id` is a `GateDecision`, not a free-form name.** This is the
/// one place a gate differs from a permission request, which hands back an
/// option's *name* because the hub's matcher accepts any of three spellings.
/// kaambaan's `resolveGate` accepts exactly `approve | request_changes |
/// reject` and nothing else, so an id outside that set draws a button that is
/// refused the moment it is pressed. `charter` →
/// `decisions/2026-08-30-a-gate-closes-over-chat.md` records this as the one
/// correction to kaambaan#34 that would otherwise have failed at runtime: the
/// proposal there has ids mirroring a `select` signal's free-form semantics.
///
/// Unknown ids are dropped rather than renamed. A gate offering only ids this
/// renderer does not know therefore renders as a *description with no
/// decision* — which is the honest outcome, because every button it could
/// draw would be a lie.
///
/// As with `dev.agentpod.permission.v1`, the event is sent beside an ordinary
/// prose message carrying the same question, so a client that never renders
/// this is exactly as able to follow the room as it was.
pub const GATE_EVENT_TYPE: &str = "dev.kaambaan.gate.v1";

/// The only option ids kaambaan resolves against — its `GateDecision` union,
/// mirrored here and pinned by `fixtures/ecosystem-identity/matrix_gate_events.json`
/// in AgentPod, which both repos validate against.
pub const GATE_OPTION_IDS: [&str; 3] = ["approve", "request_changes", "reject"];

pub struct GateRenderer;

impl CustomEventRenderer for GateRenderer {
    fn event_type(&self) -> &str {
        GATE_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Approval"
    }

    fn max_known_schema_version(&self) -> f64 {
        // 2 adds `handoff_summary`. See `render`.
        2.0
    }

    fn render(&self, content: &Value, _body: Option<&str>) -> CustomEventRenderResult {
        // `gate_id` is what resolution keys on. Without it every button on this
        // card would be unpressable, and a card that looks answerable and is
        // not is worse than one that plainly is not.
        let Some(gate_id) = safe_string_field(content, "gate_id", FIELD_VALUE_MAX_CHARS) else {
            return CustomEventRenderResult::default();
        };
        let Some(card_title) = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)
        else {
            return CustomEventRenderResult::default();
        };

        let mut fields = vec![CustomEventField {
            label: "Card".into(),
            value: card_title.clone(),
        }];
        if let Some(stage) = safe_string_field(content, "stage_key", FIELD_LABEL_MAX_CHARS) {
            fields.push(CustomEventField {
                label: "Stage".into(),
                value: stage,
            });
        }

        let mut options: Vec<CustomEventDecisionOption> = Vec::new();
        let mut duplicated = false;
        if let Some(raw) = content.get("options").and_then(Value::as_array) {
            for entry in raw {
                let Some(id) = safe_string_field(entry, "id", FIELD_LABEL_MAX_CHARS) else {
                    continue;
                };
                if !GATE_OPTION_IDS.contains(&id.as_str()) {
                    continue;
                }
                if options.iter().any(|o| o.id == id) {
                    // Resolution is by id, so two entries sharing one id are
                    // ambiguous no matter how differently they are labelled.
                    // Keeping the first would silently pick for the reader.
                    duplicated = true;
                    break;
                }
                let label = safe_string_field(entry, "label", FIELD_LABEL_MAX_CHARS)
                    .unwrap_or_else(|| id.clone());
                options.push(CustomEventDecisionOption { id, label });
            }
        }

        if duplicated || options.is_empty() {
            return CustomEventRenderResult {
                fields,
                reasoning: None,
                link: None,
                decision: None,
            };
        }

        CustomEventRenderResult {
            fields,
            // What the reviewer is being asked to approve.
            //
            // In `reasoning` rather than a field row, and the distinction is
            // the point: a row is a label and 300 characters, and a handoff is
            // neither. It is also collapsed by default, which is right — the
            // prompt is the conclusion, the work is the context.
            //
            // A gate without it renders exactly as before. Until
            // schema_version 2 every gate asked a reviewer to approve work the
            // card did not show them (#37).
            reasoning: safe_string_field(content, "handoff_summary", REASONING_MAX_CHARS),
            link: safe_link(content, "deep_link"),
            decision: Some(CustomEventDecision {
                prompt: safe_string_field(content, "prompt", FIELD_VALUE_MAX_CHARS)
                    .unwrap_or_else(|| format!("Approve \"{card_title}\"?")),
                options,
                subject: Some(gate_id),
            }),
        }
    }
}

/// The registry hosts render through in production.
///
/// Built once. Kaambaan's gate schema landed 2026-08-30; see `GateRenderer`.
pub fn default_registry() -> &'static CustomEventRegistry {
    static REGISTRY: std::sync::OnceLock<CustomEventRegistry> = std::sync::OnceLock::new();
    REGISTRY.get_or_init(|| {
        let mut registry = CustomEventRegistry::new();
        registry.register(Box::new(DemoNoteRenderer));
        registry.register(Box::new(TurnActivityRenderer));
        registry.register(Box::new(PermissionRequestRenderer));
        registry.register(Box::new(GateRenderer));
        registry
    })
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
        fn label(&self) -> &str {
            "Fixture"
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
        fn label(&self) -> &str {
            "Title"
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
                    reasoning: None,
                    link: None,
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
                reasoning: None,
                newer_version: false,
                decision: None,
                link: None,
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
            subject: None,
        });
        assert_eq!(
            decision_from(&registry),
            Some(CustomEventDecision {
                prompt: "Allow restart?".into(),
                options: vec![option("allow", "Allow"), option("deny", "Deny")],
                subject: None,
            })
        );
    }

    #[test]
    fn bounds_the_prompt_and_each_option_label() {
        let registry = with_decision(CustomEventDecision {
            prompt: "P".repeat(FIELD_VALUE_MAX_CHARS + 50),
            options: vec![option("id", &"L".repeat(FIELD_LABEL_MAX_CHARS + 50))],
            subject: None,
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
            subject: None,
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
            subject: None,
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
            subject: None,
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
            subject: None,
        });
        let decision = decision_from(&registry).expect("a decision");
        assert_eq!(decision.options, vec![option("allow", "Allow")]);
    }

    #[test]
    fn drops_a_decision_with_no_valid_options_rather_than_rendering_a_dead_card() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![option("", ""), option("", "")],
            subject: None,
        });
        assert_eq!(decision_from(&registry), None);
    }

    #[test]
    fn drops_an_empty_decision_entirely() {
        let registry = with_decision(CustomEventDecision {
            prompt: "Allow?".into(),
            options: vec![],
            subject: None,
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

    // ---- the shipped renderers ------------------------------------------

    fn render_with(renderer: Box<dyn CustomEventRenderer>, content: Value) -> CustomEventView {
        let event_type = renderer.event_type().to_string();
        let registry = registry_with(vec![renderer]);
        resolve_custom_event(
            &registry,
            Some(&event_type),
            Some(&content),
            Some("fallback"),
        )
    }

    fn rendered_fields(view: &CustomEventView) -> &[CustomEventField] {
        match view {
            CustomEventView::Rendered { fields, .. } => fields,
            other => panic!("expected a rendered view, got {other:?}"),
        }
    }

    // -- demo note --

    #[test]
    fn the_demo_renderer_reads_its_one_field_and_sets_no_decision() {
        let view = render_with(
            Box::new(DemoNoteRenderer),
            json!({ "title": "Deployed to staging" }),
        );
        assert_eq!(
            view,
            CustomEventView::Rendered {
                fields: vec![CustomEventField {
                    label: "Note".into(),
                    value: "Deployed to staging".into()
                }],
                reasoning: None,
                newer_version: false,
                decision: None,
                link: None,
            },
            "the shipped demo renderer must stay decision-free"
        );
    }

    #[test]
    fn the_demo_renderer_tolerates_a_newer_schema_with_extra_fields() {
        // Additive minor versions must still render — a renderer that only
        // reads what it was written against gets that for free.
        let view = render_with(
            Box::new(DemoNoteRenderer),
            json!({ "title": "Note", "schema_version": 2, "some_new_field": "ignored" }),
        );
        let CustomEventView::Rendered {
            fields,
            newer_version,
            ..
        } = &view
        else {
            panic!("expected a rendered view");
        };
        assert!(newer_version);
        assert_eq!(fields[0].value, "Note");
    }

    // -- turn activity --

    fn a_turn() -> Value {
        json!({
            "schema_version": 1,
            "session_id": "sess_1",
            "tools": [
                { "id": "c1", "title": "Read src/main.ts", "kind": "read", "status": "completed", "locations": [] },
                { "id": "c2", "title": "Run tests", "kind": "execute", "status": "failed", "locations": [] }
            ],
            "counts": { "total": 2, "failed": 1, "omitted": 0 }
        })
    }

    #[test]
    fn a_turn_leads_with_what_happened_then_lists_it() {
        let view = render_with(Box::new(TurnActivityRenderer), a_turn());
        let fields = rendered_fields(&view);
        assert_eq!(
            fields[0],
            CustomEventField {
                label: "Did".into(),
                value: "2 things, 1 failed".into()
            }
        );
        assert_eq!(
            fields[1],
            CustomEventField {
                label: "completed".into(),
                value: "Read src/main.ts".into()
            }
        );
        assert_eq!(
            fields[2],
            CustomEventField {
                label: "failed".into(),
                value: "Run tests".into()
            }
        );
        assert!(matches!(
            view,
            CustomEventView::Rendered { decision: None, .. }
        ));
    }

    #[test]
    fn a_turn_carries_the_reasoning_that_produced_it() {
        // The point of putting it here: the live channel delivers reasoning
        // on to-device messages, which are not room history and are gone the
        // moment the turn ends. A turn card is a real room event.
        let mut turn = a_turn();
        turn["reasoning"] = json!("Checked the logs before touching anything.");
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        let CustomEventView::Rendered { reasoning, .. } = view else {
            panic!("expected a rendered card");
        };
        assert_eq!(
            reasoning.as_deref(),
            Some("Checked the logs before touching anything.")
        );
    }

    #[test]
    fn a_turn_that_said_nothing_about_its_reasoning_carries_none() {
        let view = render_with(Box::new(TurnActivityRenderer), a_turn());
        let CustomEventView::Rendered { reasoning, .. } = view else {
            panic!("expected a rendered card");
        };
        assert!(reasoning.is_none(), "reasoning was invented");
    }

    #[test]
    fn reasoning_that_is_not_prose_is_not_reasoning() {
        // Arbitrary JSON from anyone who can send to the room. A number, an
        // object or an array is not a thought, and coercing one into a string
        // would put `{"a":1}` where a paragraph belongs.
        for shape in [json!(42), json!({ "a": 1 }), json!(["a"]), json!(null)] {
            let mut turn = a_turn();
            turn["reasoning"] = shape.clone();
            let view = render_with(Box::new(TurnActivityRenderer), turn);
            let CustomEventView::Rendered { reasoning, .. } = view else {
                panic!("expected a rendered card");
            };
            assert!(reasoning.is_none(), "{shape} was read as reasoning");
        }
    }

    #[test]
    fn empty_reasoning_is_the_same_as_none() {
        // A disclosure that opens onto an empty box says there is something
        // to read.
        let mut turn = a_turn();
        turn["reasoning"] = json!("   \n  ");
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        let CustomEventView::Rendered { reasoning, .. } = view else {
            panic!("expected a rendered card");
        };
        assert!(reasoning.is_none());
    }

    #[test]
    fn a_very_long_thought_is_cut_and_says_so() {
        let mut turn = a_turn();
        turn["reasoning"] = json!("z".repeat(REASONING_MAX_CHARS + 200));
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        let CustomEventView::Rendered { reasoning, .. } = view else {
            panic!("expected a rendered card");
        };
        let text = reasoning.expect("reasoning");
        assert_eq!(text.chars().count(), REASONING_MAX_CHARS + 1);
        assert!(text.ends_with('…'), "the cut was silent");
    }

    #[test]
    fn a_turn_lists_what_it_did_not_the_argv_it_did_it_with() {
        let mut turn = a_turn();
        turn["tools"] = json!([{
            "id": "c1",
            "title": "bash -lc 'cargo test -p supermessage-core --lib /Users/rakesh/Projects/supermessage/crates/core/src/lib.rs'",
            "status": "completed"
        }]);
        turn["counts"] = json!({ "total": 1, "failed": 0, "omitted": 0 });
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        let fields = rendered_fields(&view);
        assert_eq!(
            fields[1].value, "cargo test -p supermessage-core --lib …/src/lib.rs",
            "the shell wrapper and the path prefix are plumbing; the file is the point"
        );
    }

    #[test]
    fn a_turn_says_one_thing_in_the_singular_and_stays_quiet_about_no_failures() {
        let mut turn = a_turn();
        turn["tools"] = json!([{ "id": "c1", "title": "Read a file", "status": "completed" }]);
        turn["counts"] = json!({ "total": 1, "failed": 0, "omitted": 0 });
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        assert_eq!(
            rendered_fields(&view)[0],
            CustomEventField {
                label: "Did".into(),
                value: "1 thing".into()
            }
        );
    }

    #[test]
    fn a_turn_admits_what_it_left_out() {
        let mut turn = a_turn();
        turn["counts"] = json!({ "total": 25, "failed": 0, "omitted": 5 });
        let view = render_with(Box::new(TurnActivityRenderer), turn);
        assert_eq!(
            rendered_fields(&view).last().expect("a last field"),
            &CustomEventField {
                label: "and".into(),
                value: "5 more not listed".into()
            }
        );
    }

    #[test]
    fn a_turn_degrades_rather_than_coercing_a_hostile_payload() {
        // Objects where strings and numbers belong. Nothing may be stringified
        // into a card, and nothing readable survives, so the body shows.
        let view = render_with(
            Box::new(TurnActivityRenderer),
            json!({
                "counts": { "total": { "toString": "nope" }, "failed": [], "omitted": null },
                "tools": [{ "title": { "evil": true }, "status": 42 }, "not an object", null]
            }),
        );
        assert_eq!(
            view,
            CustomEventView::FallbackBody {
                text: "fallback".into()
            }
        );
    }

    #[test]
    fn a_turn_falls_back_when_there_is_nothing_to_show() {
        assert_eq!(
            render_with(
                Box::new(TurnActivityRenderer),
                json!({ "tools": [], "counts": {} })
            ),
            CustomEventView::FallbackBody {
                text: "fallback".into()
            }
        );
        assert_eq!(
            render_with(Box::new(TurnActivityRenderer), json!(null)),
            CustomEventView::FallbackBody {
                text: "fallback".into()
            }
        );
    }

    // -- permission request --

    fn a_request() -> Value {
        json!({
            "schema_version": 1,
            "session_id": "sess_1",
            "request_seq": 41,
            "title": "Write src/main.ts",
            "options": [
                { "option_id": "allow_once", "name": "Allow once" },
                { "option_id": "reject", "name": "Reject" }
            ]
        })
    }

    #[test]
    fn a_permission_request_asks_the_question_and_offers_the_answers() {
        let view = render_with(Box::new(PermissionRequestRenderer), a_request());
        let CustomEventView::Rendered {
            fields, decision, ..
        } = &view
        else {
            panic!("expected a rendered view, got {view:?}");
        };
        assert_eq!(
            fields[0],
            CustomEventField {
                label: "Wants to".into(),
                value: "Write src/main.ts".into()
            }
        );
        let decision = decision.as_ref().expect("a decision");
        assert_eq!(decision.prompt, "Allow Write src/main.ts?");
        assert_eq!(
            decision.options,
            vec![
                option("Allow once", "Allow once"),
                option("Reject", "Reject")
            ]
        );
    }

    #[test]
    fn a_permission_option_carries_its_name_as_its_id_because_the_id_is_what_gets_sent() {
        // The id is sent verbatim as an ordinary message, and the room is a
        // shared human record: "Allow once" belongs in it, "allow_once" does
        // not. The hub's matcher accepts either.
        let view = render_with(Box::new(PermissionRequestRenderer), a_request());
        let CustomEventView::Rendered { decision, .. } = &view else {
            panic!("expected a rendered view");
        };
        let ids: Vec<&str> = decision
            .as_ref()
            .expect("a decision")
            .options
            .iter()
            .map(|o| o.id.as_str())
            .collect();
        assert_eq!(ids, vec!["Allow once", "Reject"]);
    }

    #[test]
    fn a_permission_request_with_no_answers_describes_itself_rather_than_vanishing() {
        let mut request = a_request();
        request["options"] = json!([]);
        let view = render_with(Box::new(PermissionRequestRenderer), request);
        let CustomEventView::Rendered {
            fields, decision, ..
        } = &view
        else {
            panic!("expected a rendered view, got {view:?}");
        };
        assert_eq!(fields[0].value, "Write src/main.ts");
        assert_eq!(*decision, None);
    }

    #[test]
    fn a_permission_option_with_no_name_is_dropped() {
        let mut request = a_request();
        request["options"] = json!([{ "option_id": "a" }, { "option_id": "b", "name": "Reject" }]);
        let view = render_with(Box::new(PermissionRequestRenderer), request);
        let CustomEventView::Rendered { decision, .. } = &view else {
            panic!("expected a rendered view");
        };
        assert_eq!(
            decision.as_ref().expect("a decision").options,
            vec![option("Reject", "Reject")]
        );
    }

    #[test]
    fn a_permission_request_renders_no_more_than_four_buttons() {
        // The hub caps at four too, so this is the second line of defence.
        let many: Vec<Value> = (0..7)
            .map(|i| json!({ "option_id": format!("o{i}"), "name": format!("Option {i}") }))
            .collect();
        let mut request = a_request();
        request["options"] = json!(many);
        let view = render_with(Box::new(PermissionRequestRenderer), request);
        let CustomEventView::Rendered { decision, .. } = &view else {
            panic!("expected a rendered view");
        };
        assert_eq!(
            decision.as_ref().expect("a decision").options.len(),
            DECISION_MAX_OPTIONS
        );
    }

    #[test]
    fn a_permission_request_falls_back_when_there_is_no_question() {
        assert_eq!(
            render_with(
                Box::new(PermissionRequestRenderer),
                json!({ "options": [] })
            ),
            CustomEventView::FallbackBody {
                text: "fallback".into()
            }
        );
    }

    #[test]
    fn the_default_registry_carries_all_shipped_renderers() {
        let registry = default_registry();
        for event_type in [
            DEMO_NOTE_EVENT_TYPE,
            TURN_ACTIVITY_EVENT_TYPE,
            PERMISSION_REQUEST_EVENT_TYPE,
            GATE_EVENT_TYPE,
        ] {
            assert!(
                registry.get(event_type).is_some(),
                "{event_type} is not registered"
            );
        }
        assert_eq!(registry.len(), 4);
    }
}

#[cfg(test)]
mod tool_title_tests {
    use super::tool_title;

    #[test]
    fn a_command_spread_over_lines_becomes_one_line() {
        assert_eq!(
            tool_title("cargo test \\\n  --all-features"),
            "cargo test --all-features"
        );
    }

    #[test]
    fn a_shell_wrapper_is_plumbing_not_what_it_did() {
        assert_eq!(tool_title("bash -lc 'cargo test'"), "cargo test");
        assert_eq!(tool_title("sh -c \"ls -la\""), "ls -la");
        assert_eq!(tool_title("zsh -c 'git status'"), "git status");
    }

    #[test]
    fn an_absolute_path_keeps_the_end_that_says_which_file() {
        assert_eq!(
            tool_title("cat /Users/rakesh/Projects/supermessage/crates/core/src/lib.rs"),
            "cat …/src/lib.rs"
        );
    }

    #[test]
    fn a_short_path_is_already_readable_and_is_left_alone() {
        assert_eq!(tool_title("cat src/lib.rs"), "cat src/lib.rs");
        assert_eq!(tool_title("cat /etc/hosts"), "cat /etc/hosts");
    }

    #[test]
    fn a_plain_sentence_of_a_title_is_not_a_command_and_is_untouched() {
        assert_eq!(
            tool_title("Read the deployment runbook"),
            "Read the deployment runbook"
        );
    }
}

/// The gate renderer, checked against the shared corpus.
///
/// These cases mirror `fixtures/ecosystem-identity/matrix_gate_events.json` in
/// AgentPod. They are hand-written rather than loaded, which is the same
/// arrangement kaambaan uses: the corpus is plain JSON depending on no type
/// from any repo, and each repo reimplements against it. A published package
/// would couple three release cadences to hold one contract.
///
/// The negative cases are the point. A renderer that draws a button which
/// cannot resolve anything is worse than one that draws no button, because the
/// failure only surfaces when someone presses it — and by then they believe
/// they have approved something.
#[cfg(test)]
mod gate_tests {
    use super::*;
    use serde_json::json;

    fn gate(options: Value) -> Value {
        json!({
            "msgtype": "m.text",
            "body": "Approval needed — \"Add OAuth login\" at stage `review`.",
            "schema_version": 1,
            "board_id": "brd_7c1f",
            "card_id": "crd_9a22",
            "gate_id": "gate_4e8b",
            "stage_key": "review",
            "return_stage_key": "code",
            "card_title": "Add OAuth login",
            "produced_by": "agt_31d0",
            "prompt": "Ship the OAuth change to staging?",
            "options": options,
        })
    }

    const ALL_THREE: fn() -> Value = || {
        json!([
            { "id": "approve", "label": "Approve" },
            { "id": "request_changes", "label": "Request changes" },
            { "id": "reject", "label": "Reject" }
        ])
    };

    #[test]
    fn renders_the_prompt_and_every_option_kaambaan_accepts() {
        let result = GateRenderer.render(&gate(ALL_THREE()), None);
        let decision = result.decision.expect("a gate is a decision");
        assert_eq!(decision.prompt, "Ship the OAuth change to staging?");
        assert_eq!(
            decision
                .options
                .iter()
                .map(|o| o.id.as_str())
                .collect::<Vec<_>>(),
            ["approve", "request_changes", "reject"]
        );
        assert_eq!(decision.options[1].label, "Request changes");
    }

    #[test]
    fn shows_the_card_and_stage_as_rows() {
        let result = GateRenderer.render(&gate(ALL_THREE()), None);
        let rows: Vec<(&str, &str)> = result
            .fields
            .iter()
            .map(|f| (f.label.as_str(), f.value.as_str()))
            .collect();
        assert_eq!(rows, [("Card", "Add OAuth login"), ("Stage", "review")]);
    }

    #[test]
    fn drops_an_option_id_kaambaan_would_refuse() {
        let result = GateRenderer.render(
            &gate(json!([
                { "id": "approve", "label": "Approve" },
                { "id": "ship_it", "label": "Ship it" }
            ])),
            None,
        );
        let decision = result.decision.expect("the valid option still stands");
        assert_eq!(
            decision
                .options
                .iter()
                .map(|o| o.id.as_str())
                .collect::<Vec<_>>(),
            ["approve"],
            "an id outside GateDecision draws a button that cannot resolve"
        );
    }

    #[test]
    fn offers_no_decision_when_every_option_is_unknown() {
        let result = GateRenderer.render(
            &gate(json!([{ "id": "ship_it", "label": "Ship it" }])),
            None,
        );
        assert!(
            result.decision.is_none(),
            "every button would have been a lie; describe the gate instead"
        );
        assert!(!result.fields.is_empty(), "still describes what is waiting");
    }

    #[test]
    fn offers_no_decision_on_duplicate_option_ids() {
        let result = GateRenderer.render(
            &gate(json!([
                { "id": "approve", "label": "Approve" },
                { "id": "approve", "label": "Approve again" }
            ])),
            None,
        );
        assert!(
            result.decision.is_none(),
            "resolution is by id, so a duplicate is ambiguous — picking the first would choose for the reader"
        );
    }

    #[test]
    fn offers_no_decision_when_options_are_empty() {
        let result = GateRenderer.render(&gate(json!([])), None);
        assert!(result.decision.is_none());
    }

    #[test]
    fn refuses_to_render_at_all_without_a_gate_id() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().remove("gate_id");
        let result = GateRenderer.render(&content, None);
        assert!(
            result.fields.is_empty() && result.decision.is_none(),
            "nothing on this card could be resolved, so it must fall back rather than render"
        );
    }

    #[test]
    fn refuses_to_render_at_all_without_a_card_title() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().remove("card_title");
        assert!(GateRenderer.render(&content, None).fields.is_empty());
    }

    #[test]
    fn a_non_string_option_id_is_ignored_rather_than_coerced() {
        let result = GateRenderer.render(
            &gate(json!([{ "id": 1, "label": "One" }, { "id": "reject", "label": "No" }])),
            None,
        );
        let decision = result.decision.expect("the string option survives");
        assert_eq!(decision.options.len(), 1);
        assert_eq!(decision.options[0].id, "reject");
    }

    #[test]
    fn falls_back_to_the_id_when_an_option_carries_no_label() {
        let result = GateRenderer.render(&gate(json!([{ "id": "approve" }])), None);
        let decision = result
            .decision
            .expect("a labelless option is still answerable");
        assert_eq!(decision.options[0].label, "approve");
    }

    #[test]
    fn names_the_card_in_the_prompt_when_the_gate_carries_none() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().remove("prompt");
        let decision = GateRenderer
            .render(&content, None)
            .decision
            .expect("still answerable");
        assert_eq!(decision.prompt, "Approve \"Add OAuth login\"?");
    }

    #[test]
    fn a_newer_schema_version_still_renders_and_is_flagged() {
        let mut content = gate(ALL_THREE());
        let obj = content.as_object_mut().unwrap();
        // 3, not 2: schema_version 2 is a version this renderer now knows,
        // because it is where handoff_summary arrived.
        obj.insert("schema_version".into(), json!(3));
        obj.insert(
            "a_field_from_the_future".into(),
            json!("ignored, not fatal"),
        );

        match resolve_custom_event(
            default_registry(),
            Some(GATE_EVENT_TYPE),
            Some(&content),
            None,
        ) {
            CustomEventView::Rendered {
                newer_version,
                decision,
                ..
            } => {
                assert!(
                    newer_version,
                    "the reader should be told they may see a partial view"
                );
                assert!(
                    decision.is_some(),
                    "additive changes must not cost the buttons"
                );
            }
            other => panic!("expected a rendered gate, got {other:?}"),
        }
    }

    #[test]
    fn an_unrenderable_gate_falls_back_to_its_body() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().remove("gate_id");
        let view = resolve_custom_event(
            default_registry(),
            Some(GATE_EVENT_TYPE),
            Some(&content),
            Some("Approval needed — \"Add OAuth login\"."),
        );
        assert_eq!(
            view,
            CustomEventView::FallbackBody {
                text: "Approval needed — \"Add OAuth login\".".into()
            }
        );
    }

    #[test]
    fn shows_the_work_the_reviewer_is_approving() {
        // supermessage#37. Before schema_version 2 a gate showed a card title
        // and no work, so the only way to read what you were approving was to
        // leave for the board.
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().insert(
            "handoff_summary".into(),
            json!("Added the OAuth routes and a test for the refresh-token path."),
        );
        assert_eq!(
            GateRenderer.render(&content, None).reasoning.as_deref(),
            Some("Added the OAuth routes and a test for the refresh-token path.")
        );
    }

    #[test]
    fn a_gate_without_the_work_still_renders_its_buttons() {
        // A board that predates schema_version 2 sends no summary, and that
        // must cost the reader nothing but the summary.
        let result = GateRenderer.render(&gate(ALL_THREE()), None);
        assert!(result.reasoning.is_none());
        assert!(result.decision.is_some());
    }

    #[test]
    fn refuses_a_handoff_summary_that_is_not_text() {
        // It is displayed prose. An object here would be rendered by whatever
        // a host does with a non-string, which is the coercion every renderer
        // in this module refuses.
        let mut content = gate(ALL_THREE());
        content
            .as_object_mut()
            .unwrap()
            .insert("handoff_summary".into(), json!({ "summary": "nested" }));
        assert!(GateRenderer.render(&content, None).reasoning.is_none());
    }

    #[test]
    fn a_v2_gate_is_not_flagged_as_newer_than_this_renderer() {
        let mut content = gate(ALL_THREE());
        content
            .as_object_mut()
            .unwrap()
            .insert("schema_version".into(), json!(2));
        match resolve_custom_event(
            default_registry(),
            Some(GATE_EVENT_TYPE),
            Some(&content),
            None,
        ) {
            CustomEventView::Rendered { newer_version, .. } => assert!(!newer_version),
            other => panic!("expected a rendered gate, got {other:?}"),
        }
    }

    #[test]
    fn carries_the_gate_id_as_the_decisions_subject() {
        let decision = GateRenderer
            .render(&gate(ALL_THREE()), None)
            .decision
            .expect("a gate is a decision");
        assert_eq!(
            decision.subject.as_deref(),
            Some("gate_4e8b"),
            "without this a host can draw the buttons and has nothing to name when it answers"
        );
    }

    #[test]
    fn a_permission_request_needs_no_subject() {
        // It is identified by the event it arrived on. Asserted so that
        // `subject` staying optional is a decision rather than an oversight.
        let content = json!({ "title": "run apt-get install python3", "options": [{ "name": "Allow once" }] });
        let decision = PermissionRequestRenderer
            .render(&content, None)
            .decision
            .expect("a permission request is a decision");
        assert!(decision.subject.is_none());
    }

    #[test]
    fn the_subject_survives_the_bounding_pass() {
        // `bound_decision` rebuilds the struct; a field dropped there would
        // pass every renderer test and fail only in the host.
        match resolve_custom_event(
            default_registry(),
            Some(GATE_EVENT_TYPE),
            Some(&gate(ALL_THREE())),
            None,
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => {
                assert_eq!(d.subject.as_deref(), Some("gate_4e8b"));
            }
            other => panic!("expected a rendered gate, got {other:?}"),
        }
    }

    #[test]
    fn carries_the_boards_deep_link_when_it_is_safe() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().insert(
            "deep_link".into(),
            json!("https://kaambaan.dev/b/brd_7c1f/c/crd_9a22"),
        );
        assert_eq!(
            GateRenderer.render(&content, None).link.as_deref(),
            Some("https://kaambaan.dev/b/brd_7c1f/c/crd_9a22")
        );
    }

    #[test]
    fn refuses_every_scheme_that_is_not_https() {
        // The whole reason this validator exists. `javascript:` is how a
        // rendered link becomes code execution, `data:` smuggles a document
        // into a URL bar, `file:` reaches the device, and `//host` inherits
        // whatever scheme the opener assumes.
        for hostile in [
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "//evil.example/path",
            "http://kaambaan.dev/b/x",
            "https://",
            "",
        ] {
            let mut content = gate(ALL_THREE());
            content
                .as_object_mut()
                .unwrap()
                .insert("deep_link".into(), json!(hostile));
            assert_eq!(
                GateRenderer.render(&content, None).link,
                None,
                "{hostile} must not reach a host as something to open"
            );
        }
    }

    #[test]
    fn accepts_https_however_it_is_capitalised() {
        let mut content = gate(ALL_THREE());
        content
            .as_object_mut()
            .unwrap()
            .insert("deep_link".into(), json!("HTTPS://kaambaan.dev/b/x"));
        assert!(
            GateRenderer.render(&content, None).link.is_some(),
            "a check that can be walked around by shouting is not a check"
        );
    }

    #[test]
    fn refuses_a_url_carrying_control_characters_or_whitespace() {
        // How a link's displayed text is made to disagree with where it goes.
        for sneaky in [
            "https://kaambaan.dev/\u{202e}evil",
            "https://kaambaan.dev/a b",
            "https://kaambaan.dev/a\nb",
        ] {
            let mut content = gate(ALL_THREE());
            content
                .as_object_mut()
                .unwrap()
                .insert("deep_link".into(), json!(sneaky));
            assert_eq!(GateRenderer.render(&content, None).link, None, "{sneaky:?}");
        }
    }

    #[test]
    fn refuses_an_absurdly_long_url() {
        let mut content = gate(ALL_THREE());
        content.as_object_mut().unwrap().insert(
            "deep_link".into(),
            json!(format!("https://kaambaan.dev/{}", "a".repeat(4000))),
        );
        assert_eq!(GateRenderer.render(&content, None).link, None);
    }

    #[test]
    fn a_gate_without_a_deep_link_offers_none() {
        assert_eq!(GateRenderer.render(&gate(ALL_THREE()), None).link, None);
    }

    #[test]
    fn the_link_survives_the_bounding_pass() {
        let mut content = gate(ALL_THREE());
        content
            .as_object_mut()
            .unwrap()
            .insert("deep_link".into(), json!("https://kaambaan.dev/b/x"));
        match resolve_custom_event(
            default_registry(),
            Some(GATE_EVENT_TYPE),
            Some(&content),
            None,
        ) {
            CustomEventView::Rendered { link, .. } => {
                assert_eq!(link.as_deref(), Some("https://kaambaan.dev/b/x"));
            }
            other => panic!("expected a rendered gate, got {other:?}"),
        }
    }

    #[test]
    fn the_option_ids_are_exactly_kaambaans_gate_decision_union() {
        // If this fails, kaambaan widened GateDecision and the fixture, the hub
        // and this renderer all need the same change in the same release.
        assert_eq!(GATE_OPTION_IDS, ["approve", "request_changes", "reject"]);
    }
}
