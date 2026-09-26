//! The turn error card: what an agent's failed turn says, structured.
//!
//! When an agent's turn fails, the AgentPod hub posts **one** ordinary
//! `m.room.message` (`m.text`). Its `body` is the readable sentence every
//! client can show — "This agent reported an error: You've reached your weekly
//! usage limit…" — and its `content` also carries a namespaced key,
//! [`TURN_ERROR_KEY`], holding the same failure as structured data: what kind
//! of failure, the provider's own words, and every model the harness tried on
//! the way down its fallback chain. The wire shape is agentpod's
//! `TurnErrorCard` (`packages/contract/src/matrix-events.ts`); see
//! `docs/agentpod-events.md` §3.
//!
//! This module turns that key into a [`TurnErrorCard`] a host can draw
//! without deciding anything: the kind's wording, the headline, and the
//! attempts with consecutive repeats already folded ("×4"). The wording lives
//! here, not in each host, so iOS, Android and the desktop say the same thing
//! about the same failure.
//!
//! ## Untrusted input
//!
//! Anyone who can send to a room can put this key on a message. The parser
//! follows `custom_events`' discipline: **named fields, one level at a time,
//! text only, bounded.** Every length is re-clipped here even though the
//! contract already caps it — the contract binds the hub, not whoever else is
//! in the room — and the attempt list is capped at [`ATTEMPTS_MAX`]. Nothing
//! read out of the payload becomes markup, a link, an image source or a style
//! on any host.
//!
//! ## What happens when it does not parse
//!
//! [`parse_turn_error`] returns `None`, and the message renders exactly as it
//! would without the key: the ordinary bubble of its `body`. A malformed card
//! never drops or blanks the message — the `body` is still the complete
//! sentence, which is the whole reason the hub writes one.
//!
//! ## Versioning — the choice made here
//!
//! `schema_version` must be present and numeric; a payload without one is not
//! this card and falls back to the plain message. A version **above**
//! [`MAX_KNOWN_SCHEMA_VERSION`] is read best-effort, as `custom_events` does
//! for its renderers: the fields this build knows are read, anything new is
//! ignored, and if a newer version dropped or retyped a required field the
//! payload simply fails to parse and the message falls back to its body. No
//! "newer version" note is shown — unlike a suite card, this card's `body` is
//! always present and always complete, so nothing a reader needs can be lost.

use serde_json::Value;

/// The `content` key the AgentPod hub writes the structured card under.
pub const TURN_ERROR_KEY: &str = "dev.agentpod.turn_error";

/// The highest `schema_version` this parser was written against.
pub const MAX_KNOWN_SCHEMA_VERSION: f64 = 1.0;

/// The provider's own words — the card's body text. Matches the contract.
pub const MESSAGE_MAX_CHARS: usize = 4_000;
/// An attempt's words. Matches the contract.
pub const ATTEMPT_MESSAGE_MAX_CHARS: usize = 2_000;
/// `harness`. Matches the contract.
pub const HARNESS_MAX_CHARS: usize = 100;
/// `provider` and `model`, on the card and on each attempt. Matches the
/// contract.
pub const NAME_MAX_CHARS: usize = 200;
/// How many attempts are read, counted *after* malformed entries are skipped
/// and *before* repeats are folded. Matches the contract.
pub const ATTEMPTS_MAX: usize = 16;

/// Why the turn failed, as AgentPod classifies it.
///
/// A wire value this build has never heard of is [`Self::Unknown`], never a
/// parse failure: a new kind is an additive change and the message it
/// arrives on is still worth drawing as a card.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum TurnErrorKind {
    Quota,
    RateLimit,
    Auth,
    BadRequest,
    ContextExhausted,
    Timeout,
    ProviderUnavailable,
    Refusal,
    MaxTokens,
    Cancelled,
    NodeOffline,
    HarnessExited,
    Unknown,
}

impl TurnErrorKind {
    /// The wire's snake_case value, or [`Self::Unknown`].
    pub fn from_wire(value: &str) -> Self {
        match value {
            "quota" => Self::Quota,
            "rate_limit" => Self::RateLimit,
            "auth" => Self::Auth,
            "bad_request" => Self::BadRequest,
            "context_exhausted" => Self::ContextExhausted,
            "timeout" => Self::Timeout,
            "provider_unavailable" => Self::ProviderUnavailable,
            "refusal" => Self::Refusal,
            "max_tokens" => Self::MaxTokens,
            "cancelled" => Self::Cancelled,
            "node_offline" => Self::NodeOffline,
            "harness_exited" => Self::HarnessExited,
            _ => Self::Unknown,
        }
    }

    /// What a reader is told happened. User-visible copy, shared by every
    /// host: changing one is a product decision.
    pub fn label(self) -> &'static str {
        match self {
            Self::Quota => "Usage limit reached",
            Self::RateLimit => "Rate limited",
            Self::Auth => "Sign-in problem",
            Self::BadRequest => "Request rejected",
            Self::ContextExhausted => "Context window full",
            Self::Timeout => "Timed out",
            Self::ProviderUnavailable => "Provider unavailable",
            Self::Refusal => "Declined",
            Self::MaxTokens => "Output limit reached",
            Self::Cancelled => "Stopped",
            Self::NodeOffline => "Machine offline",
            Self::HarnessExited => "Agent process exited",
            Self::Unknown => "Error",
        }
    }
}

/// One model the harness tried, with any identical attempts directly after
/// it folded in.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnErrorAttempt {
    pub provider: String,
    pub model: String,
    /// `provider / model`, composed once so every host writes it the same
    /// way.
    pub source: String,
    pub kind: TurnErrorKind,
    /// [`TurnErrorKind::label`] for `kind`.
    pub label: String,
    pub message: String,
    /// How many consecutive, identical attempts this line stands for — the
    /// same provider, model, kind and message. Never zero. A host shows "×N"
    /// when it is above one.
    pub count: u32,
}

/// A failed turn, ready to draw.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnErrorCard {
    pub kind: TurnErrorKind,
    /// [`TurnErrorKind::label`] for `kind` — "Usage limit reached".
    pub label: String,
    /// `provider / model` for the model that was asked for, or whichever of
    /// the two the sender gave. `None` when it gave neither.
    pub source: Option<String>,
    /// `label`, then ` · source` when there is one — "Usage limit reached ·
    /// kimi-coding / k2p6". The card's first line.
    pub headline: String,
    /// The provider's own words. Never empty.
    pub message: String,
    /// Which harness ran the turn — "openclaw", "claude-code".
    pub harness: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    /// Whether AgentPod thinks trying again could work. `None` when it did
    /// not say.
    pub retryable: Option<bool>,
    /// The fallback chain, in order: the first is the model that was asked
    /// for, the rest what the harness fell back to. Consecutive repeats are
    /// already folded — see [`TurnErrorAttempt::count`]. Empty when the
    /// sender listed none.
    pub attempts: Vec<TurnErrorAttempt>,
}

/// Parse `content[TURN_ERROR_KEY]` — the value under the key, not the whole
/// `content` — into a card, or `None` when it is not one.
///
/// `None` means "draw the ordinary message", never "draw nothing". See the
/// module doc for what counts as malformed and why a newer
/// `schema_version` is still read.
pub fn parse_turn_error(value: &Value) -> Option<TurnErrorCard> {
    let object = value.as_object()?;

    // Present and numeric, or this is not the card this parser knows.
    // Any version at or above 1 is read best-effort; see the module doc.
    let version = object.get("schema_version")?.as_f64()?;
    if version < 1.0 {
        return None;
    }

    let kind = TurnErrorKind::from_wire(object.get("kind")?.as_str()?);
    let message = text(object.get("message")?, MESSAGE_MAX_CHARS)?;
    let harness = object.get("harness")?.as_str()?;
    let harness = clip(harness.trim(), HARNESS_MAX_CHARS);

    let provider = object
        .get("provider")
        .and_then(|value| text(value, NAME_MAX_CHARS));
    let model = object
        .get("model")
        .and_then(|value| text(value, NAME_MAX_CHARS));
    // No coercion: `"false"` is not `false`.
    let retryable = object.get("retryable").and_then(Value::as_bool);

    let source = compose_source(provider.as_deref(), model.as_deref());
    let label = kind.label().to_string();
    let headline = match &source {
        Some(source) => format!("{label} · {source}"),
        None => label.clone(),
    };

    Some(TurnErrorCard {
        kind,
        label,
        source,
        headline,
        message,
        harness,
        provider,
        model,
        retryable,
        attempts: attempts(object.get("attempts")),
    })
}

/// The attempt list: malformed entries skipped, the first [`ATTEMPTS_MAX`]
/// valid ones kept, consecutive repeats folded.
///
/// An `attempts` that is not an array is treated as absent rather than as a
/// reason to refuse the whole card: the headline and message are the card,
/// and the chain is detail.
fn attempts(value: Option<&Value>) -> Vec<TurnErrorAttempt> {
    let Some(entries) = value.and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut folded: Vec<TurnErrorAttempt> = Vec::new();
    for attempt in entries.iter().filter_map(parse_attempt).take(ATTEMPTS_MAX) {
        match folded.last_mut() {
            Some(last)
                if last.provider == attempt.provider
                    && last.model == attempt.model
                    && last.kind == attempt.kind
                    && last.message == attempt.message =>
            {
                last.count += 1;
            }
            _ => folded.push(attempt),
        }
    }
    folded
}

/// One attempt, or `None` when it is not one.
///
/// All four fields are required strings in the contract. An attempt that
/// names neither a provider nor a model says nothing a reader can use, so it
/// is skipped too.
fn parse_attempt(value: &Value) -> Option<TurnErrorAttempt> {
    let object = value.as_object()?;
    let provider = clip(object.get("provider")?.as_str()?.trim(), NAME_MAX_CHARS);
    let model = clip(object.get("model")?.as_str()?.trim(), NAME_MAX_CHARS);
    let kind = TurnErrorKind::from_wire(object.get("kind")?.as_str()?);
    let message = clip(
        object.get("message")?.as_str()?.trim(),
        ATTEMPT_MESSAGE_MAX_CHARS,
    );
    let source = compose_source(Some(&provider), Some(&model))?;
    Some(TurnErrorAttempt {
        provider,
        model,
        source,
        kind,
        label: kind.label().to_string(),
        message,
        count: 1,
    })
}

/// A string field, trimmed and bounded; `None` when absent, not a string, or
/// empty once trimmed.
fn text(value: &Value, max_chars: usize) -> Option<String> {
    let trimmed = value.as_str()?.trim();
    (!trimmed.is_empty()).then(|| clip(trimmed, max_chars))
}

/// `provider / model`, or whichever of the two is non-empty.
fn compose_source(provider: Option<&str>, model: Option<&str>) -> Option<String> {
    let provider = provider.filter(|p| !p.is_empty());
    let model = model.filter(|m| !m.is_empty());
    match (provider, model) {
        (Some(provider), Some(model)) => Some(format!("{provider} / {model}")),
        (Some(one), None) | (None, Some(one)) => Some(one.to_string()),
        (None, None) => None,
    }
}

/// Truncate to `max` code points, appending an ellipsis when it bites. By
/// `char`, so it cannot split a character.
fn clip(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_string();
    }
    let head: String = value.chars().take(max).collect();
    format!("{head}…")
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// The card krishna posted on 2026-09-26, as the hub wrote it.
    fn krishna() -> Value {
        let routed = "Request is missing x-opencode-session and cannot be routed efficiently.";
        json!({
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
                { "provider": "opencode-go", "model": "qwen3.7-plus", "kind": "bad_request", "message": routed },
                { "provider": "opencode-go", "model": "qwen3.7-plus", "kind": "bad_request", "message": routed },
                { "provider": "opencode-go", "model": "qwen3.7-plus", "kind": "bad_request", "message": routed },
                { "provider": "opencode-go", "model": "qwen3.7-plus", "kind": "bad_request", "message": routed },
            ]
        })
    }

    #[test]
    fn the_real_card_reads_as_its_kind_and_the_model_that_was_asked_for() {
        let card = parse_turn_error(&krishna()).expect("the hub's own card must parse");
        assert_eq!(card.kind, TurnErrorKind::Quota);
        assert_eq!(card.label, "Usage limit reached");
        assert_eq!(card.source.as_deref(), Some("kimi-coding / k2p6"));
        assert_eq!(card.headline, "Usage limit reached · kimi-coding / k2p6");
        assert_eq!(
            card.message,
            "You've reached your weekly (7-day) usage limit."
        );
        assert_eq!(card.harness, "openclaw");
        assert_eq!(card.provider.as_deref(), Some("kimi-coding"));
        assert_eq!(card.model.as_deref(), Some("k2p6"));
        assert_eq!(card.retryable, Some(false));
    }

    #[test]
    fn four_identical_fallbacks_in_a_row_are_one_line_counted_four() {
        let card = parse_turn_error(&krishna()).unwrap();
        let lines: Vec<(&str, &str, u32)> = card
            .attempts
            .iter()
            .map(|a| (a.source.as_str(), a.label.as_str(), a.count))
            .collect();
        assert_eq!(
            lines,
            vec![
                ("kimi-coding / k2p6", "Usage limit reached", 1),
                ("opencode-go / hy3-preview", "Request rejected", 1),
                ("opencode-go / qwen3.7-plus", "Request rejected", 4),
            ]
        );
    }

    #[test]
    fn repeats_that_are_not_consecutive_stay_separate_lines() {
        // Folding is about a harness retrying one model; the same model
        // tried again after another is a different step in the chain.
        let a = json!({ "provider": "p", "model": "a", "kind": "timeout", "message": "slow" });
        let b = json!({ "provider": "p", "model": "b", "kind": "timeout", "message": "slow" });
        let mut payload = krishna();
        payload["attempts"] = json!([a.clone(), b, a]);
        let card = parse_turn_error(&payload).unwrap();
        assert_eq!(card.attempts.len(), 3);
        assert!(card.attempts.iter().all(|a| a.count == 1));
    }

    #[test]
    fn attempts_differing_only_in_their_message_are_not_folded() {
        let mut payload = krishna();
        payload["attempts"] = json!([
            { "provider": "p", "model": "m", "kind": "timeout", "message": "after 30s" },
            { "provider": "p", "model": "m", "kind": "timeout", "message": "after 60s" },
        ]);
        let card = parse_turn_error(&payload).unwrap();
        assert_eq!(card.attempts.len(), 2);
    }

    #[test]
    fn every_kind_has_its_own_wording() {
        let table = [
            ("quota", TurnErrorKind::Quota, "Usage limit reached"),
            ("rate_limit", TurnErrorKind::RateLimit, "Rate limited"),
            ("auth", TurnErrorKind::Auth, "Sign-in problem"),
            ("bad_request", TurnErrorKind::BadRequest, "Request rejected"),
            (
                "context_exhausted",
                TurnErrorKind::ContextExhausted,
                "Context window full",
            ),
            ("timeout", TurnErrorKind::Timeout, "Timed out"),
            (
                "provider_unavailable",
                TurnErrorKind::ProviderUnavailable,
                "Provider unavailable",
            ),
            ("refusal", TurnErrorKind::Refusal, "Declined"),
            (
                "max_tokens",
                TurnErrorKind::MaxTokens,
                "Output limit reached",
            ),
            ("cancelled", TurnErrorKind::Cancelled, "Stopped"),
            (
                "node_offline",
                TurnErrorKind::NodeOffline,
                "Machine offline",
            ),
            (
                "harness_exited",
                TurnErrorKind::HarnessExited,
                "Agent process exited",
            ),
            ("unknown", TurnErrorKind::Unknown, "Error"),
        ];
        for (wire, kind, label) in table {
            let mut payload = krishna();
            payload["kind"] = json!(wire);
            let card = parse_turn_error(&payload).unwrap();
            assert_eq!(card.kind, kind, "{wire}");
            assert_eq!(card.label, label, "{wire}");
        }
    }

    #[test]
    fn a_kind_this_build_has_never_heard_of_is_unknown_not_a_refusal() {
        let mut payload = krishna();
        payload["kind"] = json!("gpu_on_fire");
        payload["attempts"] = json!([
            { "provider": "p", "model": "m", "kind": "also_new", "message": "x" },
        ]);
        let card = parse_turn_error(&payload).expect("a new kind is additive");
        assert_eq!(card.kind, TurnErrorKind::Unknown);
        assert_eq!(card.headline, "Error · kimi-coding / k2p6");
        assert_eq!(card.attempts[0].kind, TurnErrorKind::Unknown);
        assert_eq!(card.attempts[0].label, "Error");
    }

    #[test]
    fn anything_that_is_not_the_card_falls_back_to_the_plain_message() {
        let base = krishna();
        let mut cases: Vec<(&str, Value)> = vec![
            ("a string", json!("quota")),
            ("an array", json!([base.clone()])),
            ("null", Value::Null),
        ];
        for (field, replacement) in [
            ("schema_version", Value::Null),
            ("schema_version", json!("1")),
            ("schema_version", json!(0)),
            ("kind", Value::Null),
            ("kind", json!(7)),
            ("message", Value::Null),
            ("message", json!({ "text": "nested" })),
            ("message", json!("   ")),
            ("harness", Value::Null),
            ("harness", json!(["openclaw"])),
        ] {
            let mut payload = base.clone();
            if replacement.is_null() {
                payload.as_object_mut().unwrap().remove(field);
            } else {
                payload[field] = replacement;
            }
            cases.push((field, payload));
        }
        for (what, payload) in cases {
            assert_eq!(parse_turn_error(&payload), None, "{what}: {payload}");
        }
    }

    #[test]
    fn a_newer_schema_version_is_read_best_effort() {
        let mut payload = krishna();
        payload["schema_version"] = json!(2);
        payload["something_new"] = json!({ "deep": ["ignored"] });
        let card = parse_turn_error(&payload).expect("an additive version still reads");
        assert_eq!(card.headline, "Usage limit reached · kimi-coding / k2p6");
    }

    #[test]
    fn optional_fields_of_the_wrong_type_are_absent_not_coerced() {
        let mut payload = krishna();
        payload["provider"] = json!(42);
        payload["model"] = json!({ "name": "k2p6" });
        payload["retryable"] = json!("false");
        payload["attempts"] = json!("not a list");
        let card = parse_turn_error(&payload).expect("the required fields are intact");
        assert_eq!(card.provider, None);
        assert_eq!(card.model, None);
        assert_eq!(card.source, None);
        assert_eq!(
            card.headline, "Usage limit reached",
            "no dangling separator"
        );
        assert_eq!(card.retryable, None);
        assert!(card.attempts.is_empty());
    }

    #[test]
    fn a_card_naming_only_its_model_heads_with_the_model() {
        let mut payload = krishna();
        payload.as_object_mut().unwrap().remove("provider");
        let card = parse_turn_error(&payload).unwrap();
        assert_eq!(card.headline, "Usage limit reached · k2p6");
    }

    #[test]
    fn malformed_attempts_are_skipped_and_the_rest_kept_in_order() {
        let mut payload = krishna();
        payload["attempts"] = json!([
            "not an object",
            { "provider": "p", "model": "m" },
            { "provider": 1, "model": "m", "kind": "timeout", "message": "x" },
            { "provider": "", "model": "  ", "kind": "timeout", "message": "nobody" },
            { "provider": "p", "model": "first", "kind": "timeout", "message": "x" },
            { "provider": "p", "model": "second", "kind": "auth", "message": "y" },
        ]);
        let card = parse_turn_error(&payload).unwrap();
        let models: Vec<&str> = card.attempts.iter().map(|a| a.model.as_str()).collect();
        assert_eq!(models, vec!["first", "second"]);
    }

    #[test]
    fn every_length_is_clipped_again_whatever_the_sender_claimed() {
        let mut payload = krishna();
        payload["message"] = json!("m".repeat(MESSAGE_MAX_CHARS + 500));
        payload["harness"] = json!("h".repeat(HARNESS_MAX_CHARS + 1));
        payload["provider"] = json!("p".repeat(NAME_MAX_CHARS * 3));
        payload["model"] = json!("é".repeat(NAME_MAX_CHARS + 1));
        payload["attempts"] = json!([{
            "provider": "p".repeat(NAME_MAX_CHARS + 1),
            "model": "m".repeat(NAME_MAX_CHARS + 1),
            "kind": "timeout",
            "message": "a".repeat(ATTEMPT_MESSAGE_MAX_CHARS * 2),
        }]);
        let card = parse_turn_error(&payload).unwrap();
        let clipped = |s: &str, max: usize| s.chars().count() == max + 1 && s.ends_with('…');
        assert!(clipped(&card.message, MESSAGE_MAX_CHARS));
        assert!(clipped(&card.harness, HARNESS_MAX_CHARS));
        assert!(clipped(card.provider.as_deref().unwrap(), NAME_MAX_CHARS));
        assert!(clipped(card.model.as_deref().unwrap(), NAME_MAX_CHARS));
        let attempt = &card.attempts[0];
        assert!(clipped(&attempt.provider, NAME_MAX_CHARS));
        assert!(clipped(&attempt.model, NAME_MAX_CHARS));
        assert!(clipped(&attempt.message, ATTEMPT_MESSAGE_MAX_CHARS));
    }

    #[test]
    fn at_most_sixteen_attempts_are_read_however_many_are_sent() {
        let many: Vec<Value> = (0..200)
            .map(|n| json!({ "provider": "p", "model": format!("m{n}"), "kind": "timeout", "message": "x" }))
            .collect();
        let mut payload = krishna();
        payload["attempts"] = Value::Array(many);
        let card = parse_turn_error(&payload).unwrap();
        assert_eq!(card.attempts.len(), ATTEMPTS_MAX);
        assert_eq!(
            card.attempts[0].model, "m0",
            "the first is the one asked for"
        );
        assert_eq!(card.attempts[ATTEMPTS_MAX - 1].model, "m15");
    }

    #[test]
    fn the_cap_counts_attempts_before_folding_so_a_folded_line_never_exceeds_it() {
        let same = json!({ "provider": "p", "model": "m", "kind": "timeout", "message": "x" });
        let mut payload = krishna();
        payload["attempts"] = Value::Array(vec![same; 100]);
        let card = parse_turn_error(&payload).unwrap();
        assert_eq!(card.attempts.len(), 1);
        assert_eq!(card.attempts[0].count, ATTEMPTS_MAX as u32);
    }

    #[test]
    fn the_wire_names_a_host_switches_on_are_camel_case() {
        let card = parse_turn_error(&krishna()).unwrap();
        let json = serde_json::to_value(&card).unwrap();
        assert_eq!(json["kind"], "quota");
        assert_eq!(json["headline"], "Usage limit reached · kimi-coding / k2p6");
        assert_eq!(json["attempts"][2]["kind"], "badRequest");
        assert_eq!(json["attempts"][2]["count"], 4);
        assert_eq!(
            serde_json::to_value(TurnErrorKind::ContextExhausted).unwrap(),
            "contextExhausted"
        );
    }
}
