import type { ItemView, TurnErrorAttempt, TurnErrorCard } from "$lib/ipc";

/**
 * krishna's failed turn on 2026-09-26, as the core draws it.
 *
 * Hand-written from `core::turn_error`, and pinned there: the Rust test
 * `host_fixtures::turn_error_card` parses the same payload and asserts these
 * strings, so a wording change fails on the side that makes it.
 */

const routed = "Request is missing x-opencode-session and cannot be routed efficiently.";

function attempt(
  provider: string,
  model: string,
  kind: TurnErrorAttempt["kind"],
  label: string,
  message: string,
  count = 1,
): TurnErrorAttempt {
  return { provider, model, source: `${provider} / ${model}`, kind, label, message, count };
}

export const turnErrorCard: TurnErrorCard = {
  kind: "quota",
  label: "Usage limit reached",
  source: "kimi-coding / k2p6",
  headline: "Usage limit reached · kimi-coding / k2p6",
  message: "You've reached your weekly (7-day) usage limit.",
  harness: "openclaw",
  provider: "kimi-coding",
  model: "k2p6",
  retryable: false,
  attempts: [
    attempt(
      "kimi-coding",
      "k2p6",
      "quota",
      "Usage limit reached",
      "You've reached your weekly (7-day) usage limit.",
    ),
    attempt("opencode-go", "hy3-preview", "badRequest", "Request rejected", routed),
    attempt("opencode-go", "qwen3.7-plus", "badRequest", "Request rejected", routed, 4),
  ],
};

export function turnErrorView(card: TurnErrorCard = turnErrorCard): ItemView {
  return { render: "turnError", card };
}
