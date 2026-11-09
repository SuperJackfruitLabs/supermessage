//! gate.v2 — a superpipeline gate whose options and subject come from the gate.
//!
//! charter → `decisions/2026-11-07-gate-v2-is-the-decision-envelope.md`. The
//! hub puts these fields on the same prose `m.room.message` that carries the
//! v1 card under `dev.superpipeline.gate` (`crate::embedded`), marked with
//! `suite_event_type: "dev.superpipeline.gate.v2"`. The wire is pinned by
//! `fixtures/ecosystem-identity/matrix_gate_events.json`, vendored from
//! AgentPod byte for byte and read by the tests below.
//!
//! What v2 changes for this client, and where each rule lives:
//!
//! - **No list of valid ids.** v1 filters options to superpipeline's three
//!   `GateDecision`s (`custom_events::GATE_OPTION_IDS`). A v2 gate says what
//!   each of its options *means* — `approve`, `reject`, `revise`, `choose`,
//!   `defer` — and a host draws by meaning, never by id. superpipeline is the
//!   only judge of an id; this client only refuses an id the gate it drew did
//!   not offer.
//! - **A subject and its preview.** A gate bound to an approval subject (an X
//!   post today) carries the exact bytes being approved as preview blocks. A
//!   client that cannot draw every block, or is told the preview is
//!   incomplete, must not offer an approving option. That rule is applied
//!   **here**, in [`render`]: an approving option the reader could not see the
//!   subject of never reaches a host, so no host can draw it by mistake.
//! - **The answer carries the binding.** `decision.v2` copies the subject's id
//!   and digest from the gate the reader saw ([`decision_request`]).
//! - **A choice of versions.** A gate may carry up to three versions in
//!   `subject.variants` instead of one subject (charter →
//!   `decisions/2026-11-09-a-gate-may-offer-a-choice-of-variants.md`). Each
//!   is withheld on its own by the preview rule, and an approval carries the
//!   chosen version's binding. A malformed set makes the whole gate.v2
//!   unreadable, so the v1 card — which never approves — is drawn.
//!
//! A message whose v2 fields do not validate is read exactly as before: the
//! embedded v1 card. [`parse`] is the one place that decides which.
//!
//! Everything here is room content, so it is hostile: fields are read one
//! level at a time, never coerced, and every string a host draws is bounded.

use serde_json::Value;

use crate::custom_events::{
    bound_text, safe_link, safe_string_field, safe_url, CustomEventDecision,
    CustomEventDecisionOption, CustomEventField, CustomEventRenderResult, CustomEventRenderer,
    GATE_OPTION_IDS,
};
use crate::error::{CoreError, CoreResult};

/// The `suite_event_type` marker on a gate.v2 prose message, and the event
/// type its card is registered under.
pub const GATE_V2_EVENT_TYPE: &str = "dev.superpipeline.gate.v2";

/// The `suite_event_type` of the answer to a gate.v2.
pub const GATE_DECISION_V2_SUITE_TYPE: &str = "dev.superpipeline.gate.decision.v2";

/// How large a gate.v2's payload may be on a timeline row.
///
/// Four times `timeline::CUSTOM_PAYLOAD_MAX_BYTES`, and deliberately: the
/// preview is the exact post the reader approves, and the hub bounds it at
/// 16,000 bytes of JSON-encoded strings (`PREVIEW_TOTAL_BYTES` in its
/// `gate-wire.ts`). The prose body and the v1 card ride on the same message
/// and are stripped before this is measured ([`embedded_payload`]). A payload
/// over this falls back to the v1 card rather than drawing nothing.
pub const GATE_V2_PAYLOAD_MAX_BYTES: usize = 32 * 1024;

/// The hub's own per-block bounds (`gate-wire.ts`). A block past them was not
/// sent by a hub that follows the contract, so it is not drawn — and the
/// preview is then incomplete.
const PREVIEW_TEXT_MAX_CHARS: usize = 4_000;
const PREVIEW_FIELD_MAX_CHARS: usize = 200;
const PREVIEW_MAX_BLOCKS: usize = 20;

/// How many options a v2 card draws. The publishing gate has four; a gate
/// offering more than a person can weigh at a glance falls back to v1.
pub(crate) const OPTIONS_MAX: usize = 8;

/// An option's label is a button.
pub(crate) const OPTION_LABEL_MAX_CHARS: usize = 60;

/// How long a subject's `expires_at` may be. An ISO timestamp, not prose.
const EXPIRES_AT_MAX_CHARS: usize = 60;

/// A strict decision's subject: Salaam's frozen operation. charter →
/// `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md` §3.
pub const SALAAM_OPERATION_SCHEMA: &str = "salaam-operation/v1";

/// A harness permission's subject: the ACP request AgentPod recorded (§5).
pub const HARNESS_PERMISSION_SCHEMA: &str = "harness-permission/v1";

/// A SuperToolkit write held for approval: the digest of the exact request,
/// its expiry and a preview of the service, capability, target and every
/// argument. charter →
/// `decisions/2026-11-08-waiting-cards-and-broker-approvals-reach-chat.md` §3.
pub const STK_CAPABILITY_SCHEMA: &str = "stk-capability/v1";

const FIELD_VALUE_MAX_CHARS: usize = 300;
const FIELD_LABEL_MAX_CHARS: usize = 60;
const REASONING_MAX_CHARS: usize = 4_000;

/// What the card says when it withholds every approving option.
///
/// Worded here so no host words it differently. It names the way forward
/// (the card's link), because a card that silently has no Approve button
/// reads as a bug.
pub const APPROVALS_WITHHELD_NOTE: &str =
    "This card can't show everything you would be approving. Open the card to approve it.";

/// What a gate.v2 option means. A host draws by this, never by id.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum GateOptionMeaning {
    /// The main action. A publishing gate has two (automatic and manual).
    Approve,
    /// Destructive.
    Reject,
    /// Sends the work back; always carries a comment in practice.
    Revise,
    /// One of several neutral choices.
    Choose,
    /// Neutral: not now.
    Defer,
}

impl GateOptionMeaning {
    pub(crate) fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "approve" => Self::Approve,
            "reject" => Self::Reject,
            "revise" => Self::Revise,
            "choose" => Self::Choose,
            "defer" => Self::Defer,
            _ => return None,
        })
    }
}

/// How far an option reaches. charter →
/// `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md` §2.
/// A host shows it; the producer judges it.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum OptionScope {
    /// This once.
    Once,
    /// For the rest of this run.
    Run,
    /// From now on.
    Always,
}

impl OptionScope {
    pub(crate) fn parse(raw: &str) -> Option<Self> {
        Some(match raw {
            "once" => Self::Once,
            "run" => Self::Run,
            "always" => Self::Always,
            _ => return None,
        })
    }
}

/// Who may take an option (§2). A host shows it ("admins only") and may hide
/// an option the viewer cannot take; the producer judges it.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum OptionRequires {
    /// A workspace owner or admin.
    WorkspaceAdmin,
}

impl OptionRequires {
    pub(crate) fn parse(raw: &str) -> Option<Self> {
        match raw {
            "workspace-admin" => Some(Self::WorkspaceAdmin),
            _ => None,
        }
    }
}

/// What an option's input takes. charter →
/// `decisions/2026-11-07-the-envelope-covers-questions-and-follow-ups.md`.
#[derive(
    Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize, uniffi::Enum,
)]
#[serde(rename_all = "camelCase")]
pub enum OptionInputKind {
    /// Free text.
    Text,
    /// An `https://` URL — the live link a manual delivery owes.
    Url,
}

/// An option that asks for input before it is sent: the host draws a field
/// labelled `label`, refuses to send a `required` one empty, and never sends
/// more than `max_length` characters. The core checks all three again
/// ([`checked_input`]).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct OptionInput {
    pub kind: OptionInputKind,
    /// Display text, bounded.
    pub label: String,
    pub required: bool,
    /// In characters. At least 1, at most [`INPUT_MAX_LENGTH`].
    pub max_length: u32,
}

/// The longest input an option may ask for. A follow-up's live link asks for
/// 2,000; a question's free text is bounded at 2,000 too.
pub const INPUT_MAX_LENGTH: u32 = 4_000;

/// One answer a gate.v2 offers.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateOptionV2 {
    /// Handed back verbatim; never drawn, never cut.
    pub id: String,
    /// Free text from the board, bounded. Display only.
    pub label: String,
    pub meaning: GateOptionMeaning,
    /// The host must ask for text, and the core refuses to send this option
    /// without it.
    pub needs_comment: bool,
    /// The input this option asks for, on a question.v2 or delivery.v2
    /// option. Always `None` on a gate.v2 option: a decision.v2 carries a
    /// comment, never an input's text.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub input: Option<OptionInput>,
    /// How far the option reaches, when it says — a strict decision's or a
    /// harness permission's option. Display only: the producer judges it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub scope: Option<OptionScope>,
    /// Who may take it, when it says. Display only, as `scope`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub requires: Option<OptionRequires>,
}

/// One block of a subject's preview — what the reader must see before
/// approving. Every string is text to draw verbatim: no markdown, no links
/// detected in it, line breaks kept.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum GatePreviewBlock {
    /// The exact bytes of the thing approved — an X post — character for
    /// character.
    Text {
        label: Option<String>,
        text: String,
    },
    Field {
        label: String,
        value: String,
    },
    /// `url` passed `custom_events::safe_url`: `https://`, printable ASCII.
    Link {
        label: String,
        url: String,
    },
}

/// The subject a decision.v2 is bound to, copied back from the gate shown.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateSubjectBinding {
    pub id: String,
    pub digest: String,
}

/// A gate.v2 as a host draws and answers it. Rides on
/// `CustomEventDecision::gate_v2`.
///
/// Also the shape of a delivery.v2 follow-up (`crate::delivery_v2`), which
/// names the gate it follows, carries the same subject and preview, and
/// withholds its approving option by the same rule.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateV2Decision {
    pub gate_id: String,
    /// What this client may offer, in the gate's order. Approving options
    /// are already gone when the preview could not be shown in full.
    pub options: Vec<GateOptionV2>,
    /// Empty for a gate bound to no subject.
    pub preview: Vec<GatePreviewBlock>,
    /// [`APPROVALS_WITHHELD_NOTE`] when approving options were withheld.
    pub approvals_withheld: Option<String>,
    /// Present on a subject-bound gate; copied into the answer.
    pub subject: Option<GateSubjectBinding>,
    /// A choice of versions (charter →
    /// `decisions/2026-11-09-a-gate-may-offer-a-choice-of-variants.md`), in
    /// the gate's order; empty for any other gate. On a variant gate
    /// `subject` and `preview` are empty — each version carries its own —
    /// and an approving option is answered with the chosen version
    /// ([`decision_request`]), which must be [`GateVariantV2::approvable`].
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[uniffi(default = [])]
    pub variants: Vec<GateVariantV2>,
    /// A gate that needs more than one approval (charter →
    /// `decisions/2026-11-09-a-gate-may-need-more-than-one-approver.md`),
    /// with how far it has got (`crate::gate_quorum`); `None` for a gate
    /// that passes on one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub quorum: Option<crate::gate_quorum::GateQuorum>,
    /// What would hold an approval before it acts (charter →
    /// `decisions/2026-11-09-an-approval-may-wait-for-its-release-time.md`);
    /// worded at the host's clock by `crate::gate_release::release_line`.
    /// `None` for a gate whose approval acts at once.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub release: Option<crate::gate_release::GateRelease>,
    /// The machine checks that ran before the gate opened for people (charter
    /// → `decisions/2026-11-09-a-gate-may-be-decided-by-checks.md`), in the
    /// board's order, each with its plain line ("build (CI) — passed")
    /// (`crate::gate_checks`). Empty for a gate whose stage has none. Never
    /// overridable here: by the time a gate opens every check is over.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    #[uniffi(default = [])]
    pub checks: Vec<crate::gate_checks::GateCheck>,
}

/// One version a variant gate offers: its label, its own subject and
/// preview, and whether this client may approve it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct GateVariantV2 {
    /// Handed back verbatim as the chosen version; never drawn.
    pub id: String,
    /// One line, bounded. Display only.
    pub label: String,
    /// Its preview drawn verbatim, and the binding an approval copies.
    pub subject: SubjectV2,
    /// Its whole preview was drawable and the board said it was complete.
    /// Only such a version may be chosen here; the rest are chosen on the
    /// board. Request changes and reject never depend on it.
    pub approvable: bool,
    /// [`VARIANT_WITHHELD_NOTE`] exactly when not `approvable`.
    pub withheld: Option<String>,
}

/// What a version that cannot be chosen here says, under its preview.
/// Worded here so no host words it differently.
pub const VARIANT_WITHHELD_NOTE: &str = "Too long to show here \u{2014} choose it on the board.";

/// What a variant gate says when no version at all can be chosen here.
pub const VARIANTS_WITHHELD_NOTE: &str =
    "None of these versions can be shown in full here. Open the card to choose one.";

/// The most versions a gate may offer (operator ruling, 2026-11-09).
pub const VARIANTS_MAX: usize = 3;

/// A version's label sits in a sentence and a picker row. The hub's bound.
const VARIANT_LABEL_MAX_CHARS: usize = 80;

/// The subject a question.v2 or a permission.v2 is bound to, as a host draws
/// it: the preview verbatim, and the binding the answer copies back.
///
/// A gate.v2 carries the same subject flattened onto [`GateV2Decision`]; this
/// is its shape for the decisions that came after it. charter →
/// `decisions/2026-11-07-strict-decisions-and-permissions-bind-their-subject.md`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct SubjectV2 {
    /// Copied into the answer; never drawn.
    pub binding: GateSubjectBinding,
    /// [`SALAAM_OPERATION_SCHEMA`], [`HARNESS_PERMISSION_SCHEMA`] or
    /// [`STK_CAPABILITY_SCHEMA`].
    pub schema: String,
    /// Drawn verbatim, as a gate's.
    pub preview: Vec<GatePreviewBlock>,
    /// When the producer stops accepting an answer, as it said it (an ISO
    /// timestamp), or `None` when it set none. Display only.
    pub expires_at: Option<String>,
}

/// A subject as read off the wire.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Subject {
    pub(crate) binding: GateSubjectBinding,
    pub(crate) preview: Vec<GatePreviewBlock>,
    /// Every block drawable, none dropped, and the board said it was whole.
    pub(crate) complete: bool,
    /// `subject.schema`, when it is a string. Checked only by
    /// [`parse_bound_subject`]; a gate.v2 never required it.
    pub(crate) schema: Option<String>,
    /// `subject.expires_at`, when it is a short string.
    pub(crate) expires_at: Option<String>,
}

impl Subject {
    /// The subject as a host draws it.
    pub(crate) fn view(&self) -> SubjectV2 {
        SubjectV2 {
            binding: self.binding.clone(),
            schema: self.schema.clone().unwrap_or_default(),
            preview: self.preview.clone(),
            expires_at: self.expires_at.clone(),
        }
    }
}

/// A gate.v2 that validated.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct ParsedGateV2 {
    gate_id: String,
    card_title: String,
    stage_key: Option<String>,
    prompt: Option<String>,
    handoff_summary: Option<String>,
    deep_link: Option<String>,
    options: Vec<GateOptionV2>,
    subject: Option<Subject>,
    /// A variant gate's versions; empty otherwise, and then `subject` may be
    /// set. Never both.
    variants: Vec<Variant>,
    /// `required`, `from` and the approvals recorded when it was posted.
    quorum: Option<(
        u32,
        crate::gate_quorum::QuorumFrom,
        Vec<crate::gate_quorum::GateQuorumApproval>,
    )>,
    release: Option<crate::gate_release::GateRelease>,
    checks: Vec<crate::gate_checks::GateCheck>,
}

/// One version of a variant gate as read off the wire.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Variant {
    id: String,
    label: String,
    subject: Subject,
}

impl Variant {
    fn view(&self) -> GateVariantV2 {
        let approvable = self.subject.complete;
        GateVariantV2 {
            id: self.id.clone(),
            label: self.label.clone(),
            subject: self.subject.view(),
            approvable,
            withheld: (!approvable).then(|| VARIANT_WITHHELD_NOTE.to_string()),
        }
    }
}

/// A non-empty string at `value[key]`, unbounded — an identifier, compared
/// and handed back, never drawn. The payload is bounded as a whole.
pub(crate) fn id_field(value: &Value, key: &str) -> Option<String> {
    value
        .get(key)?
        .as_str()
        .filter(|s| !s.is_empty())
        .map(str::to_string)
}

/// A string at `value[key]` that fits `max_chars` exactly, or `None` —
/// never cut, because a preview shown cut is not the thing approved.
fn exact_string(value: &Value, key: &str, max_chars: usize) -> Option<String> {
    let s = value.get(key)?.as_str()?;
    (s.chars().count() <= max_chars).then(|| s.to_string())
}

/// One preview block, or `None` when this client cannot draw it as sent.
fn parse_block(raw: &Value) -> Option<GatePreviewBlock> {
    match raw.get("type")?.as_str()? {
        "text" => {
            let label = match raw.get("label") {
                None | Some(Value::Null) => None,
                Some(_) => Some(exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?),
            };
            Some(GatePreviewBlock::Text {
                label,
                text: exact_string(raw, "text", PREVIEW_TEXT_MAX_CHARS)?,
            })
        }
        "field" => Some(GatePreviewBlock::Field {
            label: exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?,
            value: exact_string(raw, "value", PREVIEW_FIELD_MAX_CHARS)?,
        }),
        "link" => {
            let url = raw.get("url")?.as_str()?;
            // Exactly as sent, or not at all: `safe_url` trims, and a link
            // drawn other than as sent is not the link approved.
            let safe = safe_url(url).filter(|safe| safe == url)?;
            Some(GatePreviewBlock::Link {
                label: exact_string(raw, "label", PREVIEW_FIELD_MAX_CHARS)?,
                url: safe,
            })
        }
        // A kind of block this build has never heard of. It cannot be drawn,
        // so the preview is incomplete — the case the charter names.
        _ => None,
    }
}

/// `content.subject`: `Ok(None)` when absent, `Err(())` when present and not
/// a subject an answer could be bound to (no id or no digest).
pub(crate) fn parse_subject(content: &Value) -> Result<Option<Subject>, ()> {
    let raw = match content.get("subject") {
        None | Some(Value::Null) => return Ok(None),
        Some(raw) => raw,
    };
    let (Some(id), Some(digest)) = (id_field(raw, "id"), id_field(raw, "digest")) else {
        return Err(());
    };
    // Only an explicit `true` counts. Absent is a board that did not say.
    let mut complete = raw.get("preview_complete").and_then(Value::as_bool) == Some(true);
    let mut preview = Vec::new();
    match raw.get("preview").and_then(Value::as_array) {
        Some(blocks) => {
            for block in blocks {
                if preview.len() == PREVIEW_MAX_BLOCKS {
                    complete = false;
                    break;
                }
                match parse_block(block) {
                    Some(block) => preview.push(block),
                    None => complete = false,
                }
            }
        }
        None => complete = false,
    }
    if preview.is_empty() {
        // Nothing shown is not a preview, whatever the flag says.
        complete = false;
    }
    Ok(Some(Subject {
        binding: GateSubjectBinding { id, digest },
        preview,
        complete,
        schema: raw
            .get("schema")
            .and_then(Value::as_str)
            .map(str::to_string),
        expires_at: safe_string_field(raw, "expires_at", EXPIRES_AT_MAX_CHARS),
    }))
}

/// Whether `content.subject` declares a choice of versions — well formed or
/// not. Such a subject is never read as a single subject, even with an `id`
/// and `digest` beside its `variants` (the corpus refuses that).
fn declares_variants(content: &Value) -> bool {
    content
        .get("subject")
        .and_then(Value::as_object)
        .is_some_and(|s| s.contains_key("variants"))
}

/// `content.subject.variants`, or `Err(())` when they are not a choice a
/// client can honestly offer: an `id` or `digest` beside them, not a list,
/// none, more than [`VARIANTS_MAX`], a version without an id or a label, a
/// version whose own subject has no id or digest ([`parse_subject`]), or two
/// versions sharing an id or a subject id. Any of these makes the whole
/// gate.v2 unreadable, so the v1 card — which never approves — is drawn.
///
/// Each version's preview is judged on its own: one that is incomplete is
/// read, and only it is withheld.
fn parse_variants(content: &Value) -> Result<Vec<Variant>, ()> {
    let raw = content
        .get("subject")
        .and_then(Value::as_object)
        .ok_or(())?;
    if raw.contains_key("id") || raw.contains_key("digest") {
        return Err(());
    }
    let list = raw.get("variants").and_then(Value::as_array).ok_or(())?;
    if list.is_empty() || list.len() > VARIANTS_MAX {
        return Err(());
    }
    let mut variants: Vec<Variant> = Vec::with_capacity(list.len());
    for entry in list {
        let id = id_field(entry, "id").ok_or(())?;
        let label = entry
            .get("label")
            .and_then(Value::as_str)
            .map(|l| l.split_whitespace().collect::<Vec<_>>().join(" "))
            .filter(|l| !l.is_empty())
            .map(|l| bound_text(&l, VARIANT_LABEL_MAX_CHARS))
            .ok_or(())?;
        // A version's `subject` is the ordinary envelope, read by the one
        // reader every subject goes through.
        let subject = parse_subject(entry)?.ok_or(())?;
        if variants
            .iter()
            .any(|v| v.id == id || v.subject.binding.id == subject.binding.id)
        {
            // Choosing is by version: two sharing an id, or one subject
            // offered twice, make the answer ambiguous.
            return Err(());
        }
        variants.push(Variant { id, label, subject });
    }
    Ok(variants)
}

/// `content.subject` as a question.v2 or a permission.v2 carries it — the
/// gate's subject read by [`parse_subject`], held to the whole of its Matrix
/// shape: a `schema` among `schemas`, a whole-number `revision`, and an
/// explicit boolean `preview_complete`. A gate.v2 tolerates a missing flag
/// (it reads as incomplete); these came later, from carriers that always
/// send it, so a subject without it — `previewComplete`, the board's own
/// spelling — is not the wire and is refused, as the corpus refuses it.
///
/// `Ok(None)` when absent; `Err(())` when present and not that.
pub(crate) fn parse_bound_subject(
    content: &Value,
    schemas: &[&str],
) -> Result<Option<Subject>, ()> {
    let raw = match content.get("subject") {
        None | Some(Value::Null) => return Ok(None),
        Some(raw) => raw,
    };
    let schema_known = raw
        .get("schema")
        .and_then(Value::as_str)
        .is_some_and(|schema| schemas.contains(&schema));
    let revision = raw.get("revision").and_then(Value::as_u64).is_some();
    let flag = raw.get("preview_complete").is_some_and(Value::is_boolean);
    if !(schema_known && revision && flag) {
        return Err(());
    }
    parse_subject(content)
}

/// An option's `scope` and `requires`: absent (or null) is `None`; a value
/// this build does not know refuses the option, as the corpus refuses a
/// scope outside `once | run | always` — a host must not draw "Approve" for
/// an option whose reach it cannot say.
pub(crate) fn parse_scope_requires(
    entry: &Value,
) -> Option<(Option<OptionScope>, Option<OptionRequires>)> {
    let scope = match entry.get("scope") {
        None | Some(Value::Null) => None,
        Some(raw) => Some(OptionScope::parse(raw.as_str()?)?),
    };
    let requires = match entry.get("requires") {
        None | Some(Value::Null) => None,
        Some(raw) => Some(OptionRequires::parse(raw.as_str()?)?),
    };
    Some((scope, requires))
}

/// The options a host may offer, and the note to show in place of any it
/// may not: every approve-meaning option is withheld unless `approvable`.
/// One rule for a gate, a question and a permission.
pub(crate) fn offerable(
    options: &[GateOptionV2],
    approvable: bool,
    note: &str,
) -> (Vec<GateOptionV2>, Option<String>) {
    let offered: Vec<GateOptionV2> = options
        .iter()
        .filter(|o| approvable || o.meaning != GateOptionMeaning::Approve)
        .cloned()
        .collect();
    let withheld = !approvable
        && options
            .iter()
            .any(|o| o.meaning == GateOptionMeaning::Approve);
    (offered, withheld.then(|| note.to_string()))
}

/// The gate's options, or `None` when they are not v2 options a client can
/// honestly answer: empty, too many, an option with no id, no meaning a
/// client knows, `needs_comment` that is not a boolean, or two sharing an id.
fn parse_options(content: &Value) -> Option<Vec<GateOptionV2>> {
    let raw = content.get("options")?.as_array()?;
    if raw.is_empty() {
        return None;
    }
    // A gate.v2 option's `input`, if a board ever sends one, is not read: a
    // decision.v2 has nowhere to carry its text.
    parse_option_list(raw, false)
}

/// An option's `input`: `Ok(None)` when absent, `Err(())` when present and
/// not one a client can honestly draw — not an object, a kind this build
/// does not know, a label or `required` missing, or a `max_length` that is
/// not a whole number from 1 to [`INPUT_MAX_LENGTH`] (`maxLength`, the
/// board's own spelling, is not the Matrix field and counts as missing).
fn parse_input(entry: &Value) -> Result<Option<OptionInput>, ()> {
    let raw = match entry.get("input") {
        None | Some(Value::Null) => return Ok(None),
        Some(raw) => raw,
    };
    let kind = match raw.get("kind").and_then(Value::as_str) {
        Some("text") => OptionInputKind::Text,
        Some("url") => OptionInputKind::Url,
        _ => return Err(()),
    };
    let label = safe_string_field(raw, "label", OPTION_LABEL_MAX_CHARS)
        .filter(|l| !l.trim().is_empty())
        .ok_or(())?;
    let required = raw.get("required").and_then(Value::as_bool).ok_or(())?;
    let max_length = raw
        .get("max_length")
        .and_then(Value::as_u64)
        .filter(|n| (1..=u64::from(INPUT_MAX_LENGTH)).contains(n))
        .ok_or(())?;
    Ok(Some(OptionInput {
        kind,
        label,
        required,
        // In range by the filter above.
        max_length: max_length as u32,
    }))
}

/// A list of envelope options — gate.v2, question.v2 or delivery.v2 — or
/// `None` when they are not options a client can honestly answer: too many,
/// an option with no id, no meaning a client knows, `needs_comment` that is
/// not a boolean, an input that does not validate, a `scope` or `requires`
/// this build does not know (`parse_scope_requires`), or two sharing an id.
/// An empty list is returned as empty; whether that is allowed is the
/// caller's rule. `read_input` false ignores every `input`.
pub(crate) fn parse_option_list(raw: &[Value], read_input: bool) -> Option<Vec<GateOptionV2>> {
    if raw.len() > OPTIONS_MAX {
        return None;
    }
    let mut options: Vec<GateOptionV2> = Vec::with_capacity(raw.len());
    for entry in raw {
        let id = id_field(entry, "id")?;
        let meaning = GateOptionMeaning::parse(entry.get("meaning")?.as_str()?)?;
        let needs_comment = entry.get("needs_comment")?.as_bool()?;
        if options.iter().any(|o| o.id == id) {
            // Answering is by id: two entries sharing one are ambiguous.
            return None;
        }
        let input = if read_input {
            parse_input(entry).ok()?
        } else {
            None
        };
        let (scope, requires) = parse_scope_requires(entry)?;
        let label = safe_string_field(entry, "label", OPTION_LABEL_MAX_CHARS)
            .filter(|l| !l.trim().is_empty())
            .unwrap_or_else(|| bound_text(&id, OPTION_LABEL_MAX_CHARS));
        options.push(GateOptionV2 {
            id,
            label,
            meaning,
            needs_comment,
            input,
            scope,
            requires,
        });
    }
    Some(options)
}

/// The text an option's input sends, checked: `Ok(None)` when the option
/// has no input or an optional one was left empty, `Ok(Some(trimmed))`
/// otherwise. Refuses, with a reason a host can show, a required input left
/// empty, text over `max_length` characters, and a `url` input that is not
/// an `https://` URL a host may open (`custom_events::safe_url`).
///
/// Text sent for an option without an input is dropped, not refused: the
/// wire carries `text` only beside an option that asked for it.
pub(crate) fn checked_input(
    option: &GateOptionV2,
    text: Option<&str>,
) -> CoreResult<Option<String>> {
    let Some(input) = &option.input else {
        return Ok(None);
    };
    let text = text.map(str::trim).filter(|t| !t.is_empty());
    let Some(text) = text else {
        if input.required {
            return Err(CoreError::Protocol(format!(
                "\u{201C}{}\u{201D} needs {}",
                option.label,
                match input.kind {
                    OptionInputKind::Url => "a link",
                    OptionInputKind::Text => "an answer",
                }
            )));
        }
        return Ok(None);
    };
    if text.chars().count() > input.max_length as usize {
        return Err(CoreError::Protocol(format!(
            "\u{201C}{}\u{201D} takes at most {} characters",
            input.label, input.max_length
        )));
    }
    if input.kind == OptionInputKind::Url && safe_url(text).as_deref() != Some(text) {
        return Err(CoreError::Protocol(
            "the link must be an https:// address".into(),
        ));
    }
    Ok(Some(text.to_string()))
}

/// Read a gate.v2 from a message's `content`, or `None` when it is not one
/// this client may draw from v2 — in which case the v1 card is read instead.
///
/// `body` is the message's own; a gate with none is one a stock client would
/// show as nothing (the corpus refuses it).
pub(crate) fn parse(content: &Value, body: Option<&str>) -> Option<ParsedGateV2> {
    if content.get("suite_event_type")?.as_str()? != GATE_V2_EVENT_TYPE {
        return None;
    }
    body.filter(|b| !b.trim().is_empty())?;
    let gate_id = id_field(content, "gate_id")?;
    let card_title = safe_string_field(content, "card_title", FIELD_VALUE_MAX_CHARS)?;
    let options = parse_options(content)?;
    let (subject, variants) = if declares_variants(content) {
        (None, parse_variants(content).ok()?)
    } else {
        (parse_subject(content).ok()?, Vec::new())
    };
    let quorum = crate::gate_quorum::parse_quorum(content).ok()?;
    let release = crate::gate_release::parse_release(content).ok()?;
    let checks = crate::gate_checks::parse_checks(content)
        .ok()?
        .unwrap_or_default();
    Some(ParsedGateV2 {
        gate_id,
        card_title,
        stage_key: safe_string_field(content, "stage_key", FIELD_LABEL_MAX_CHARS),
        prompt: safe_string_field(content, "prompt", FIELD_VALUE_MAX_CHARS),
        handoff_summary: safe_string_field(content, "handoff_summary", REASONING_MAX_CHARS),
        deep_link: safe_link(content, "deep_link"),
        options,
        subject,
        variants,
        quorum,
        release,
        checks,
    })
}

/// The payload a gate.v2 message rides on its timeline row, or `None` when
/// the message is not a valid gate.v2 (or too large to carry one).
///
/// The message's `content` without what the row already has or will not
/// read: the `body` (the row's own), the embedded v1 card, and — for a
/// subject-bound gate — the handoff summary, which is the preview's text
/// again.
pub fn embedded_payload(content: &Value) -> Option<Value> {
    let body = content.get("body").and_then(Value::as_str);
    let parsed = parse(content, body)?;
    let mut payload = content.as_object()?.clone();
    payload.remove("body");
    payload.remove(crate::embedded::EMBEDDED_GATE_KEY);
    if parsed.bound() {
        payload.remove("handoff_summary");
    }
    let payload = Value::Object(payload);
    let size = serde_json::to_string(&payload)
        .map(|s| s.len())
        .unwrap_or(usize::MAX);
    (size <= GATE_V2_PAYLOAD_MAX_BYTES).then_some(payload)
}

impl ParsedGateV2 {
    /// Bound to a subject or to a choice of them: an approval needs a
    /// preview the reader saw, and the handoff summary is that preview's
    /// text again (or, for a choice, a pointer to the card).
    fn bound(&self) -> bool {
        self.subject.is_some() || !self.variants.is_empty()
    }

    /// Whether this client may offer an approving option: a gate bound to no
    /// subject, one whose whole preview it can draw, or a choice with at
    /// least one version it can draw whole — which version an approval may
    /// name is checked again when it is sent ([`decision_request`]).
    fn approvable(&self) -> bool {
        if !self.variants.is_empty() {
            return self.variants.iter().any(|v| v.subject.complete);
        }
        self.subject.as_ref().is_none_or(|s| s.complete)
    }

    /// The card a host draws.
    /// [`Self::decision`], for a test elsewhere in the crate.
    #[cfg(test)]
    pub(crate) fn decision_for_test(&self) -> GateV2Decision {
        self.decision()
    }

    fn decision(&self) -> GateV2Decision {
        let note = if self.variants.is_empty() {
            APPROVALS_WITHHELD_NOTE
        } else {
            VARIANTS_WITHHELD_NOTE
        };
        let (options, approvals_withheld) = offerable(&self.options, self.approvable(), note);
        GateV2Decision {
            gate_id: self.gate_id.clone(),
            options,
            preview: self
                .subject
                .as_ref()
                .map(|s| s.preview.clone())
                .unwrap_or_default(),
            approvals_withheld,
            subject: self.subject.as_ref().map(|s| s.binding.clone()),
            variants: self.variants.iter().map(Variant::view).collect(),
            quorum: self.quorum.as_ref().map(|(required, from, approvals)| {
                let variants: Vec<(String, String)> = self
                    .variants
                    .iter()
                    .map(|v| (v.id.clone(), v.label.clone()))
                    .collect();
                crate::gate_quorum::GateQuorum {
                    required: *required,
                    from: *from,
                    approvals: approvals.clone(),
                    progress: crate::gate_quorum::progress_line(*required, approvals, &variants),
                    approved_by_you: false,
                }
            }),
            release: self.release.clone(),
            checks: self.checks.clone(),
        }
    }

    /// The options a host that knows nothing of v2 may offer and answer with
    /// a decision.v1: superpipeline's three ids — what the v1 card beside it
    /// offers — and never an approval of a subject, which needs the preview
    /// such a host does not draw.
    fn legacy_options(&self) -> Vec<CustomEventDecisionOption> {
        self.options
            .iter()
            .filter(|o| GATE_OPTION_IDS.contains(&o.id.as_str()))
            .filter(|o| !self.bound() || o.meaning != GateOptionMeaning::Approve)
            .map(|o| CustomEventDecisionOption {
                id: o.id.clone(),
                label: o.label.clone(),
            })
            .collect()
    }
}

/// Draws a gate.v2 — registered under [`GATE_V2_EVENT_TYPE`].
pub struct GateV2Renderer;

impl CustomEventRenderer for GateV2Renderer {
    fn event_type(&self) -> &str {
        GATE_V2_EVENT_TYPE
    }

    fn label(&self) -> &str {
        "Approval"
    }

    fn max_known_schema_version(&self) -> f64 {
        1.0
    }

    fn render(&self, content: &Value, body: Option<&str>) -> CustomEventRenderResult {
        let Some(gate) = parse(content, body) else {
            return CustomEventRenderResult::default();
        };
        let mut fields = vec![CustomEventField {
            label: "Card".into(),
            value: gate.card_title.clone(),
        }];
        if let Some(stage) = &gate.stage_key {
            fields.push(CustomEventField {
                label: "Stage".into(),
                value: stage.clone(),
            });
        }
        CustomEventRenderResult {
            fields,
            // A subject-bound gate's summary is its preview's text, drawn
            // above as the preview itself.
            reasoning: (!gate.bound())
                .then(|| gate.handoff_summary.clone())
                .flatten(),
            link: gate.deep_link.clone(),
            decision: Some(CustomEventDecision {
                prompt: gate
                    .prompt
                    .clone()
                    .unwrap_or_else(|| format!("Approve \"{}\"?", gate.card_title)),
                options: gate.legacy_options(),
                subject: Some(gate.gate_id.clone()),
                gate_v2: Some(gate.decision()),
                question_v2: None,
                delivery_v2: None,
                permission_v2: None,
                attention_v2: None,
                checks_waiting: None,
                // The stage's timeout (`crate::decision_timeout`).
                deadline: crate::decision_timeout::parse_deadline(content),
            }),
        }
    }
}

/// The sentence a decision.v2 leaves in the room.
///
/// The past tense of what the option *means*, so the record reads the same
/// whatever a board labels its buttons — plus, when the label qualifies the
/// verb after an em dash ("Approve — post automatically"), that
/// qualification, which is the difference between the two approvals a
/// publishing gate offers. A choice says what was chosen.
pub(crate) fn decision_body(option: &GateOptionV2) -> String {
    let verb = match option.meaning {
        GateOptionMeaning::Approve => "Approved",
        GateOptionMeaning::Reject => "Rejected",
        GateOptionMeaning::Revise => "Requested changes",
        GateOptionMeaning::Choose => "Chose",
        GateOptionMeaning::Defer => "Deferred",
    };
    let label = option.label.trim();
    let detail = match option.meaning {
        GateOptionMeaning::Choose => Some(label),
        _ => label.split_once(" — ").map(|(_, rest)| rest.trim()),
    }
    .filter(|d| !d.is_empty());
    match detail {
        Some(detail) => format!("{verb} — {detail}"),
        None => verb.to_string(),
    }
}

/// A decision.v2's `content`, validated against the gate the reader saw —
/// the whole of what goes on the wire.
///
/// Refuses, with a reason a host can show: an option the gate did not offer
/// (or that was withheld), an approval of a subject whose preview was not
/// shown in full, an option that needs a comment sent without one, and a
/// reference that is not an event id.
///
/// `chosen_variant` is the id of the version picked on a variant gate
/// ([`GateV2Decision::variants`]). An approving answer to such a gate must
/// name one this client may approve, and carries that version's binding;
/// request changes and reject act on the whole set and carry none, so a
/// chosen version beside them is not sent. A chosen version on a gate that
/// offers none is refused: the reader picked something this gate is not.
///
/// `comment` is always present — `null` when there is none — matching the
/// corpus's decision.v2 examples; `subject` is present exactly when the gate
/// was bound to one. An answer to a variant gate is the corpus's too, which
/// leaves out a `comment` it does not have.
pub(crate) fn decision_request(
    gate: &GateV2Decision,
    option_id: &str,
    chosen_variant: Option<&str>,
    comment: Option<&str>,
    in_reply_to: &str,
) -> CoreResult<Value> {
    if gate.gate_id.is_empty() {
        return Err(CoreError::Protocol("a decision needs a gate".into()));
    }
    let option = gate
        .options
        .iter()
        .find(|o| o.id == option_id)
        .ok_or_else(|| {
            CoreError::Protocol(format!("not an option this gate offers: {option_id}"))
        })?;
    if option.meaning == GateOptionMeaning::Approve && gate.approvals_withheld.is_some() {
        return Err(CoreError::Protocol(
            "this card could not show everything being approved".into(),
        ));
    }
    let comment = comment.filter(|c| !c.trim().is_empty());
    if option.needs_comment && comment.is_none() {
        return Err(CoreError::Protocol(format!(
            "\u{201C}{}\u{201D} needs a comment",
            option.label
        )));
    }
    let binding = binding_for(gate, option, chosen_variant)?;
    let event_id = matrix_sdk::ruma::EventId::parse(in_reply_to)
        .map_err(|e| CoreError::Protocol(e.to_string()))?;
    let mut content = serde_json::json!({
        "msgtype": "m.text",
        "body": decision_body(option),
        "schema_version": 1,
        "suite_event_type": GATE_DECISION_V2_SUITE_TYPE,
        "gate_id": gate.gate_id,
        "option_id": option.id,
        "comment": comment,
        "m.relates_to": {
            "rel_type": "m.reference",
            "event_id": event_id.as_str(),
        },
    });
    if !gate.variants.is_empty() && comment.is_none() {
        content
            .as_object_mut()
            .expect("built as an object")
            .remove("comment");
    }
    if let Some(subject) = binding {
        content["subject"] = serde_json::json!({ "id": subject.id, "digest": subject.digest });
    }
    Ok(content)
}

/// The binding an answer carries: the gate's own subject; on a variant gate
/// the chosen version's for an approval and none otherwise. Refuses what
/// [`decision_request`] says it refuses about versions.
fn binding_for<'a>(
    gate: &'a GateV2Decision,
    option: &GateOptionV2,
    chosen_variant: Option<&str>,
) -> CoreResult<Option<&'a GateSubjectBinding>> {
    if gate.variants.is_empty() {
        if chosen_variant.is_some() {
            return Err(CoreError::Protocol(
                "this gate offers no versions to choose from".into(),
            ));
        }
        return Ok(gate.subject.as_ref());
    }
    if option.meaning != GateOptionMeaning::Approve {
        return Ok(None);
    }
    let chosen =
        chosen_variant.ok_or_else(|| CoreError::Protocol("choose a version to approve".into()))?;
    let variant = gate
        .variants
        .iter()
        .find(|v| v.id == chosen)
        .ok_or_else(|| CoreError::Protocol(format!("not a version this gate offers: {chosen}")))?;
    if !variant.approvable {
        return Err(CoreError::Protocol(format!(
            "\u{201C}{}\u{201D} could not be shown in full here; choose it on the board",
            variant.label
        )));
    }
    Ok(Some(&variant.subject.binding))
}

/// The corpus, checked end to end: every gate.v2 `accept` draws from v2,
/// every `reject` falls back, and what the client sends for the corpus's
/// gate is exactly the corpus's decision.v2.
#[cfg(test)]
mod tests {
    use super::*;
    use crate::custom_events::{default_registry, resolve_custom_event, CustomEventView};
    use serde_json::json;

    const CORPUS: &str =
        include_str!("../../../fixtures/ecosystem-identity/matrix_gate_events.json");

    fn corpus() -> Value {
        serde_json::from_str(CORPUS).expect("the corpus is JSON")
    }

    fn event(suite_type: &str) -> Value {
        corpus()["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type)
            .unwrap_or_else(|| panic!("the corpus pins {suite_type}"))
            .clone()
    }

    fn cases(suite_type: &str, which: &str) -> Vec<(String, Value)> {
        event(suite_type)[which]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["why"].as_str().unwrap().to_string(), c["content"].clone()))
            .collect()
    }

    /// The publishing gate: subject-bound, four options.
    fn publishing() -> Value {
        cases(GATE_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    /// The ordinary gate: no subject.
    fn ordinary() -> Value {
        cases(GATE_V2_EVENT_TYPE, "accept")[1].1.clone()
    }

    fn body(content: &Value) -> Option<&str> {
        content.get("body").and_then(Value::as_str)
    }

    /// The card a host gets for `content`, through the production registry.
    fn decision_for(content: &Value) -> CustomEventDecision {
        let payload = embedded_payload(content).expect("a valid gate.v2");
        match resolve_custom_event(
            default_registry(),
            Some(GATE_V2_EVENT_TYPE),
            Some(&payload),
            body(content),
        ) {
            CustomEventView::Rendered {
                decision: Some(d), ..
            } => d,
            other => panic!("expected a decision, got {other:?}"),
        }
    }

    fn v2(content: &Value) -> GateV2Decision {
        decision_for(content).gate_v2.expect("drawn from v2")
    }

    #[test]
    fn every_accepted_gate_v2_is_drawn_from_v2() {
        for (why, content) in cases(GATE_V2_EVENT_TYPE, "accept") {
            assert!(parse(&content, body(&content)).is_some(), "{why}");
            assert!(embedded_payload(&content).is_some(), "{why}");
        }
    }

    #[test]
    fn every_rejected_gate_v2_falls_back() {
        let rejects = cases(GATE_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 5, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    #[test]
    fn the_publishing_gate_offers_four_options_by_meaning() {
        let gate = v2(&publishing());
        let drawn: Vec<(&str, &str, GateOptionMeaning, bool)> = gate
            .options
            .iter()
            .map(|o| (o.id.as_str(), o.label.as_str(), o.meaning, o.needs_comment))
            .collect();
        assert_eq!(
            drawn,
            [
                (
                    "approve_manual",
                    "Approve — I'll post it myself",
                    GateOptionMeaning::Approve,
                    false
                ),
                (
                    "approve_automatic",
                    "Approve — post automatically",
                    GateOptionMeaning::Approve,
                    false
                ),
                (
                    "request_changes",
                    "Request changes",
                    GateOptionMeaning::Revise,
                    true
                ),
                ("reject", "Reject", GateOptionMeaning::Reject, false),
            ]
        );
        assert_eq!(gate.approvals_withheld, None);
        assert_eq!(
            gate.subject,
            Some(GateSubjectBinding {
                id: "aps_2c9e71".into(),
                digest: "sha256:9b2f4e0c1a7d".into()
            })
        );
    }

    #[test]
    fn the_preview_text_is_the_post_character_for_character() {
        let gate = v2(&publishing());
        assert_eq!(
            gate.preview,
            [
                GatePreviewBlock::Text {
                    label: Some("Post".into()),
                    text: "Our next release ships today.\n\nThanks to everyone who tested it."
                        .into(),
                },
                GatePreviewBlock::Field {
                    label: "Account".into(),
                    value: "@fixture_account on X".into(),
                },
                GatePreviewBlock::Field {
                    label: "Approval expires".into(),
                    value: "2030-01-02T03:04:05Z".into(),
                },
            ]
        );
    }

    #[test]
    fn markdown_in_the_post_is_kept_not_interpreted() {
        let mut content = publishing();
        let post = "**not bold**\n  `two spaces`\n# not a heading\n<b>tag</b> _x_";
        content["subject"]["preview"][0]["text"] = json!(post);
        let GatePreviewBlock::Text { text, .. } = &v2(&content).preview[0] else {
            panic!("a text block");
        };
        assert_eq!(text, post);
    }

    fn approve_ids(gate: &GateV2Decision) -> Vec<&str> {
        gate.options
            .iter()
            .filter(|o| o.meaning == GateOptionMeaning::Approve)
            .map(|o| o.id.as_str())
            .collect()
    }

    fn assert_withheld(content: &Value, why: &str) {
        let decision = decision_for(content);
        let gate = decision.gate_v2.as_ref().unwrap();
        assert!(approve_ids(gate).is_empty(), "{why}: approval offered");
        assert_eq!(
            gate.approvals_withheld.as_deref(),
            Some(APPROVALS_WITHHELD_NOTE),
            "{why}"
        );
        // Revise and reject stay, and so does the link to the card.
        let rest: Vec<&str> = gate.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(rest, ["request_changes", "reject"], "{why}");
        assert!(
            !decision.options.iter().any(|o| o.id.starts_with("approve")),
            "{why}: the legacy list must not approve either"
        );
    }

    #[test]
    fn an_incomplete_preview_offers_no_approval() {
        let mut content = publishing();
        content["subject"]["preview_complete"] = json!(false);
        assert_withheld(&content, "preview_complete false");
    }

    #[test]
    fn a_preview_that_does_not_say_it_is_complete_offers_no_approval() {
        let mut content = publishing();
        content["subject"]
            .as_object_mut()
            .unwrap()
            .remove("preview_complete");
        assert_withheld(&content, "preview_complete absent");
    }

    #[test]
    fn a_block_this_client_cannot_draw_offers_no_approval() {
        let mut content = publishing();
        content["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "image", "label": "Card", "url": "https://x.example/a.png" }));
        assert_withheld(&content, "an unknown block type");
        // The blocks it can draw are still shown.
        assert_eq!(v2(&content).preview.len(), 3);
    }

    #[test]
    fn a_block_past_its_bounds_or_with_an_unsafe_link_offers_no_approval() {
        let mut long = publishing();
        long["subject"]["preview"][0]["text"] = json!("x".repeat(PREVIEW_TEXT_MAX_CHARS + 1));
        assert_withheld(&long, "a text block past 4000 characters");

        for url in [
            "javascript:alert(1)",
            "http://x.example",
            " https://x.example",
        ] {
            let mut link = publishing();
            link["subject"]["preview"]
                .as_array_mut()
                .unwrap()
                .push(json!({ "type": "link", "label": "Where", "url": url }));
            assert_withheld(&link, url);
        }
    }

    #[test]
    fn an_empty_or_missing_preview_offers_no_approval() {
        let mut empty = publishing();
        empty["subject"]["preview"] = json!([]);
        assert_withheld(&empty, "empty preview");
        let mut missing = publishing();
        missing["subject"]
            .as_object_mut()
            .unwrap()
            .remove("preview");
        assert_withheld(&missing, "missing preview");
    }

    #[test]
    fn a_safe_link_block_is_drawn_and_keeps_approval() {
        let mut content = publishing();
        content["subject"]["preview"]
            .as_array_mut()
            .unwrap()
            .push(json!({ "type": "link", "label": "Thread", "url": "https://x.example/t/1" }));
        let gate = v2(&content);
        assert_eq!(approve_ids(&gate), ["approve_manual", "approve_automatic"]);
        assert_eq!(
            gate.preview[3],
            GatePreviewBlock::Link {
                label: "Thread".into(),
                url: "https://x.example/t/1".into()
            }
        );
    }

    #[test]
    fn an_ordinary_gate_offers_approval_with_no_preview() {
        let decision = decision_for(&ordinary());
        let gate = decision.gate_v2.as_ref().unwrap();
        assert_eq!(approve_ids(gate), ["approve"]);
        assert!(gate.preview.is_empty());
        assert_eq!(gate.subject, None);
        assert_eq!(decision.subject.as_deref(), Some("gate_4e8b"));
    }

    #[test]
    fn a_host_without_v2_sees_only_what_a_decision_v1_can_answer() {
        // Desktop and the notification actions read `options` and answer with
        // decision.v1. For the publishing gate that is what the v1 card beside
        // it offers: no approval of a subject it cannot show.
        let ids =
            |d: &CustomEventDecision| d.options.iter().map(|o| o.id.clone()).collect::<Vec<_>>();
        assert_eq!(
            ids(&decision_for(&publishing())),
            ["request_changes", "reject"]
        );
        assert_eq!(
            ids(&decision_for(&ordinary())),
            ["approve", "request_changes", "reject"]
        );
        // A subject-bound gate whose approval is spelled with v1's own id is
        // still an approval of a subject such a host cannot show.
        let mut plain_id = publishing();
        plain_id["options"][0]["id"] = json!("approve");
        assert_eq!(ids(&decision_for(&plain_id)), ["request_changes", "reject"]);
    }

    #[test]
    fn labels_are_bounded_and_ids_are_not() {
        let mut content = ordinary();
        let id = format!("choose_{}", "a".repeat(150));
        content["options"][0] = json!({
            "id": id, "label": "L".repeat(500), "meaning": "choose", "needs_comment": false
        });
        let gate = v2(&content);
        assert_eq!(gate.options[0].id, id);
        assert_eq!(
            gate.options[0].label.chars().count(),
            OPTION_LABEL_MAX_CHARS + 1
        );
    }

    #[test]
    fn a_message_without_the_v2_marker_is_not_v2() {
        let mut content = ordinary();
        content["suite_event_type"] = json!("dev.superpipeline.gate.v1");
        assert!(embedded_payload(&content).is_none());
        content.as_object_mut().unwrap().remove("suite_event_type");
        assert!(embedded_payload(&content).is_none());
    }

    #[test]
    fn an_oversized_gate_falls_back_rather_than_vanishing() {
        let mut content = publishing();
        let blocks: Vec<Value> = (0..PREVIEW_MAX_BLOCKS)
            .map(|_| json!({ "type": "text", "text": "y".repeat(PREVIEW_TEXT_MAX_CHARS) }))
            .collect();
        content["subject"]["preview"] = json!(blocks);
        assert!(embedded_payload(&content).is_none());
    }

    #[test]
    fn the_payload_drops_what_the_row_already_has() {
        let mut content = publishing();
        content[crate::embedded::EMBEDDED_GATE_KEY] = json!({ "gate_id": "gate_8f3a" });
        let payload = embedded_payload(&content).unwrap();
        assert!(payload.get("body").is_none());
        assert!(payload.get(crate::embedded::EMBEDDED_GATE_KEY).is_none());
        assert!(payload.get("handoff_summary").is_none());
        assert!(embedded_payload(&ordinary())
            .unwrap()
            .get("handoff_summary")
            .is_some());
    }

    // ---- decision.v2 -------------------------------------------------------

    const GATE_EVENT: &str = "$gateEventId";

    #[test]
    fn the_answers_are_exactly_the_corpus_decisions() {
        let gate = v2(&publishing());
        let accepts = cases(GATE_DECISION_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 2, "the corpus grew; read the new case");
        assert_eq!(
            decision_request(&gate, "approve_automatic", None, None, GATE_EVENT).unwrap(),
            accepts[0].1,
            "{}",
            accepts[0].0
        );
        assert_eq!(
            decision_request(
                &gate,
                "request_changes",
                None,
                Some("Say which release."),
                GATE_EVENT
            )
            .unwrap(),
            accepts[1].1,
            "{}",
            accepts[1].0
        );
    }

    #[test]
    fn an_answer_to_a_subject_gate_always_carries_the_binding() {
        // Corpus reject: "an answer to a subject-bound gate with no subject
        // binding".
        let gate = v2(&publishing());
        for option in ["approve_manual", "approve_automatic", "reject"] {
            let content = decision_request(&gate, option, None, None, GATE_EVENT).unwrap();
            assert_eq!(
                content["subject"],
                json!({ "id": "aps_2c9e71", "digest": "sha256:9b2f4e0c1a7d" })
            );
        }
        // And an ordinary gate's answer carries none.
        let ordinary = v2(&ordinary());
        let content = decision_request(&ordinary, "approve", None, None, GATE_EVENT).unwrap();
        assert!(content.get("subject").is_none());
        assert_eq!(content["body"], "Approved");
    }

    #[test]
    fn an_option_that_needs_a_comment_is_refused_without_one() {
        // Corpus reject: "an option that needs a comment, without one".
        let gate = v2(&publishing());
        for comment in [None, Some(""), Some("  \n ")] {
            let err = decision_request(&gate, "request_changes", None, comment, GATE_EVENT)
                .expect_err("an empty revise must not reach the room");
            assert!(matches!(&err, CoreError::Protocol(m) if m.contains("needs a comment")));
        }
    }

    #[test]
    fn an_option_the_gate_did_not_offer_is_refused() {
        // Corpus reject: "an option the gate did not offer".
        let gate = v2(&publishing());
        let err =
            decision_request(&gate, "approve", None, None, GATE_EVENT).expect_err("not offered");
        assert!(matches!(&err, CoreError::Protocol(m) if m.contains("approve")));
    }

    #[test]
    fn a_withheld_approval_cannot_be_sent() {
        let mut content = publishing();
        content["subject"]["preview_complete"] = json!(false);
        let gate = v2(&content);
        assert!(decision_request(&gate, "approve_automatic", None, None, GATE_EVENT).is_err());
        // Even if a host put it back.
        let mut forged = gate.clone();
        forged.options.insert(
            0,
            GateOptionV2 {
                id: "approve_automatic".into(),
                label: "Approve".into(),
                meaning: GateOptionMeaning::Approve,
                needs_comment: false,
                input: None,
                scope: None,
                requires: None,
            },
        );
        assert!(decision_request(&forged, "approve_automatic", None, None, GATE_EVENT).is_err());
        // Revise and reject still go.
        assert!(decision_request(&gate, "reject", None, None, GATE_EVENT).is_ok());
    }

    #[test]
    fn an_answer_must_reference_the_gate_event() {
        // Corpus reject: "no m.relates_to".
        let gate = v2(&publishing());
        assert!(decision_request(&gate, "reject", None, None, "not-an-event").is_err());
        let content = decision_request(&gate, "reject", None, None, GATE_EVENT).unwrap();
        assert_eq!(
            content["m.relates_to"],
            json!({ "rel_type": "m.reference", "event_id": GATE_EVENT })
        );
    }

    #[test]
    fn the_sentence_says_what_the_option_means() {
        let option = |label: &str, meaning| GateOptionV2 {
            id: "x".into(),
            label: label.into(),
            meaning,
            needs_comment: false,
            input: None,
            scope: None,
            requires: None,
        };
        use GateOptionMeaning::*;
        assert_eq!(
            decision_body(&option("Approve — I'll post it myself", Approve)),
            "Approved — I'll post it myself"
        );
        assert_eq!(decision_body(&option("Ship it", Approve)), "Approved");
        assert_eq!(
            decision_body(&option("Send back", Revise)),
            "Requested changes"
        );
        assert_eq!(decision_body(&option("Nope", Reject)), "Rejected");
        assert_eq!(decision_body(&option("Later", Defer)), "Deferred");
        assert_eq!(decision_body(&option("Blue", Choose)), "Chose — Blue");
    }

    // ---- a choice of versions (corpus `variant: "choice"`) ------------------

    /// The corpus's cases for `suite_type` in its `choice` entry.
    fn choice_cases(suite_type: &str, which: &str) -> Vec<(String, Value)> {
        let entry = corpus()["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["suiteEventType"] == suite_type && e["variant"] == "choice")
            .unwrap_or_else(|| panic!("the corpus pins a choice for {suite_type}"))
            .clone();
        entry[which]
            .as_array()
            .unwrap()
            .iter()
            .map(|c| (c["why"].as_str().unwrap().to_string(), c["content"].clone()))
            .collect()
    }

    /// Three versions; the third was cut to its share and is incomplete.
    fn choice() -> Value {
        choice_cases(GATE_V2_EVENT_TYPE, "accept")[0].1.clone()
    }

    fn variant_ids(gate: &GateV2Decision) -> Vec<(&str, bool)> {
        gate.variants
            .iter()
            .map(|v| (v.id.as_str(), v.approvable))
            .collect()
    }

    #[test]
    fn every_accepted_variant_gate_is_drawn_with_each_version_judged_alone() {
        let accepts = choice_cases(GATE_V2_EVENT_TYPE, "accept");
        assert_eq!(accepts.len(), 1, "the corpus grew; read the new case");
        for (why, content) in &accepts {
            assert!(parse(content, body(content)).is_some(), "{why}");
            assert!(embedded_payload(content).is_some(), "{why}");
        }
        let gate = v2(&choice());
        assert_eq!(
            variant_ids(&gate),
            [("short", true), ("thread", true), ("long", false)]
        );
        let labels: Vec<&str> = gate.variants.iter().map(|v| v.label.as_str()).collect();
        assert_eq!(labels, ["Short", "With the thread", "Long form"]);
        let withheld: Vec<Option<&str>> = gate
            .variants
            .iter()
            .map(|v| v.withheld.as_deref())
            .collect();
        assert_eq!(withheld, [None, None, Some(VARIANT_WITHHELD_NOTE)]);
        assert_eq!(
            gate.variants[1].subject.binding,
            GateSubjectBinding {
                id: "aps_v2".into(),
                digest: "sha256:v2d1".into()
            }
        );
        assert_eq!(gate.variants[1].subject.schema, "social-publish/v1");
        assert_eq!(
            gate.variants[1].subject.preview,
            [GatePreviewBlock::Text {
                label: Some("Post".into()),
                text: "Our next release ships today.\n\nThanks to everyone who tested it.".into(),
            }]
        );
        // The incomplete one is still shown, as far as it goes.
        assert_eq!(gate.variants[2].subject.preview.len(), 1);
        // The gate itself has no subject of its own, and keeps every option:
        // which version an approval may name is the version's to say.
        assert_eq!(gate.subject, None);
        assert!(gate.preview.is_empty());
        assert_eq!(gate.approvals_withheld, None);
        let ids: Vec<&str> = gate.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(
            ids,
            [
                "approve_manual",
                "approve_automatic",
                "request_changes",
                "reject"
            ]
        );
    }

    #[test]
    fn every_rejected_variant_gate_falls_back_to_the_v1_card() {
        let rejects = choice_cases(GATE_V2_EVENT_TYPE, "reject");
        assert_eq!(rejects.len(), 4, "the corpus grew; read the new case");
        for (why, content) in rejects {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    /// `content` with `variants[index]` passed through `edit`.
    fn with_variant(index: usize, edit: impl Fn(&mut serde_json::Map<String, Value>)) -> Value {
        let mut content = choice();
        let variant = content["subject"]["variants"][index]
            .as_object_mut()
            .unwrap();
        edit(variant);
        content
    }

    #[test]
    fn a_malformed_choice_falls_back_too() {
        let mut empty = choice();
        empty["subject"]["variants"] = json!([]);
        let mut not_a_list = choice();
        not_a_list["subject"]["variants"] = json!({});
        let mut digest_beside = choice();
        digest_beside["subject"]["digest"] = json!("sha256:v1d1");
        let cases = [
            ("no versions", empty),
            ("versions not a list", not_a_list),
            ("only a digest beside the versions", digest_beside),
            (
                "a version without a label",
                with_variant(0, |v| {
                    v.remove("label");
                }),
            ),
            (
                "a blank label",
                with_variant(0, |v| {
                    v.insert("label".into(), json!("  \n "));
                }),
            ),
            (
                "a version without an id",
                with_variant(1, |v| {
                    v.insert("id".into(), json!(""));
                }),
            ),
            (
                "a version without a subject",
                with_variant(1, |v| {
                    v.remove("subject");
                }),
            ),
            (
                "a version whose subject has no id",
                with_variant(2, |v| {
                    v["subject"].as_object_mut().unwrap().remove("id");
                }),
            ),
            (
                "two versions offering one subject",
                with_variant(1, |v| {
                    v["subject"]["id"] = json!("aps_v1");
                }),
            ),
        ];
        for (why, content) in cases {
            assert!(parse(&content, body(&content)).is_none(), "{why}");
            assert!(embedded_payload(&content).is_none(), "{why}");
        }
    }

    #[test]
    fn a_label_is_one_line_and_bounded() {
        let mut content = choice();
        content["subject"]["variants"][0]["label"] = json!("  Short\n  and   sweet ");
        content["subject"]["variants"][1]["label"] = json!("L".repeat(500));
        let gate = v2(&content);
        assert_eq!(gate.variants[0].label, "Short and sweet");
        assert_eq!(
            gate.variants[1].label.chars().count(),
            VARIANT_LABEL_MAX_CHARS + 1
        );
    }

    #[test]
    fn one_version_that_cannot_be_drawn_is_withheld_alone() {
        let image = json!({ "type": "image", "label": "Card", "url": "https://x.example/a.png" });
        let content = with_variant(0, |v| {
            v["subject"]["preview"]
                .as_array_mut()
                .unwrap()
                .push(image.clone());
        });
        let gate = v2(&content);
        assert_eq!(
            variant_ids(&gate),
            [("short", false), ("thread", true), ("long", false)]
        );
        assert_eq!(gate.approvals_withheld, None);
    }

    #[test]
    fn a_choice_with_no_version_shown_whole_offers_no_approval() {
        let mut content = choice();
        for i in 0..3 {
            content["subject"]["variants"][i]["subject"]["preview_complete"] = json!(false);
        }
        let gate = v2(&content);
        assert!(approve_ids(&gate).is_empty());
        assert_eq!(
            gate.approvals_withheld.as_deref(),
            Some(VARIANTS_WITHHELD_NOTE)
        );
        let rest: Vec<&str> = gate.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(rest, ["request_changes", "reject"]);
        let approve = decision_request(&gate, "approve_automatic", Some("short"), None, GATE_EVENT);
        assert!(approve.is_err());
        assert!(decision_request(&gate, "reject", None, None, GATE_EVENT).is_ok());
    }

    #[test]
    fn a_host_without_v2_never_approves_a_choice() {
        // Desktop and the notification actions read `options`.
        let decision = decision_for(&choice());
        let ids: Vec<&str> = decision.options.iter().map(|o| o.id.as_str()).collect();
        assert_eq!(ids, ["request_changes", "reject"]);
        assert_eq!(
            decision.prompt,
            "Choose a version of \"Announce the release\""
        );
        // The summary says to open the card; the card draws the versions.
        let payload = embedded_payload(&choice()).unwrap();
        assert!(payload.get("handoff_summary").is_none());
    }

    #[test]
    fn the_answers_to_a_choice_are_exactly_the_corpus_decisions() {
        let gate = v2(&choice());
        let accepts = choice_cases(GATE_DECISION_V2_SUITE_TYPE, "accept");
        assert_eq!(accepts.len(), 2, "the corpus grew; read the new case");
        let approve =
            decision_request(&gate, "approve_automatic", Some("thread"), None, GATE_EVENT);
        assert_eq!(approve.unwrap(), accepts[0].1, "{}", accepts[0].0);
        let reject = decision_request(&gate, "reject", None, None, GATE_EVENT);
        assert_eq!(reject.unwrap(), accepts[1].1, "{}", accepts[1].0);
        // The set is rejected whichever version was picked: no binding goes.
        let picked = decision_request(&gate, "reject", Some("short"), None, GATE_EVENT);
        assert_eq!(picked.unwrap(), accepts[1].1);
        // A comment, when there is one, is carried.
        let revise = decision_request(
            &gate,
            "request_changes",
            Some("long"),
            Some("Shorter."),
            GATE_EVENT,
        )
        .unwrap();
        assert_eq!(revise["comment"], "Shorter.");
        assert!(revise.get("subject").is_none());
    }

    #[test]
    fn an_approval_of_a_choice_must_name_a_version_shown_whole() {
        let gate = v2(&choice());
        let refused = |chosen: Option<&str>, why: &str| {
            let sent = decision_request(&gate, "approve_manual", chosen, None, GATE_EVENT);
            assert!(matches!(sent, Err(CoreError::Protocol(_))), "{why}");
        };
        // Corpus reject: "an approval with no binding: it chooses nothing".
        refused(None, "no version chosen");
        // Corpus reject: "choosing the variant whose preview was incomplete".
        refused(Some("long"), "the incomplete version");
        // Corpus reject: "a binding that names no variant of the gate".
        refused(Some("fourth"), "an unknown version");
        refused(Some("aps_v1"), "a subject id is not a version id");
        // Each version a reader saw whole carries its own binding.
        for (chosen, id, digest) in [
            ("short", "aps_v1", "sha256:v1d1"),
            ("thread", "aps_v2", "sha256:v2d1"),
        ] {
            let sent = decision_request(&gate, "approve_manual", Some(chosen), None, GATE_EVENT);
            let content = sent.unwrap();
            assert_eq!(content["subject"], json!({ "id": id, "digest": digest }));
            assert_eq!(content["body"], "Approved — I'll post it myself");
        }
    }

    #[test]
    fn a_chosen_version_on_a_gate_without_versions_is_refused() {
        for gate in [v2(&publishing()), v2(&ordinary())] {
            for option in ["reject", "request_changes"] {
                let sent = decision_request(&gate, option, Some("short"), Some("x"), GATE_EVENT);
                let err = sent.expect_err("no versions to choose from");
                assert!(matches!(&err, CoreError::Protocol(m) if m.contains("no versions")));
            }
        }
    }
}
