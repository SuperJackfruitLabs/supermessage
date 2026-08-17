// The custom-event rendering registry — the plumbing `docs/matrix-events.md`
// §G and `docs/positioning.md` describe as M1's centrepiece: Kaambaan cards
// and runs, permission requests, station status all arrive as
// `kind: "customMessage"` timeline items (`TimelineItem.detail` carries the
// Matrix event type, `TimelineItem.customPayload` its bounded `content`
// object — see `core::dto::TimelineItemDto::custom_payload`'s doc comment on
// the Rust side). This module does not — and must not — invent those
// schemas; it only builds the seam so that when Kaambaan's team lands one,
// adding a renderer is a `registerCustomEventRenderer` call, not a refactor.
//
// ## Registering a renderer
//
// ```ts
// import { registerCustomEventRenderer, customEventRegistry } from "./customEvents";
//
// registerCustomEventRenderer(customEventRegistry, {
//   eventType: "org.kaambaan.card.v1",     // see "Versioning" below
//   maxKnownSchemaVersion: 1,               // see "Versioning" below
//   render(content, body) {
//     const title = safeStringField(content, "title", 200);
//     return { fields: title ? [{ label: "Title", value: title }] : [] };
//   },
// });
// ```
//
// `render` must return **text only** — every `value` ends up interpolated
// into the DOM with Svelte's default `{...}` escaping in `Timeline.svelte`,
// never `{@html}`, an `href`, an `src`, or a `style`. `content` is arbitrary
// JSON from anyone who can send to the room; treat it exactly like any other
// hostile input. Read named fields one level at a time (`safeStringField`
// below is the shape to copy) rather than walking the object recursively —
// see "Why renderers never recurse" further down for why that single
// discipline is what keeps a deeply nested or huge payload from being able
// to hang this module at all, without needing a runtime depth/size guard.
//
// ## Versioning — the decision this module encodes
//
// Two axes, both carried by every suite custom event, because they answer
// two different questions:
//
// - **Major version, in the event type string itself** (the trailing `.v1`,
//   `.v2`, … in `dev.supermessage.demo.note.v1`). A breaking schema change —
//   a field renamed, retyped, or made non-optional — mints a *new* event
//   type. This is not a novel convention: it's how Matrix itself already
//   handles incompatible changes at the type level (new `m.room.*`/MSC event
//   types rather than mutating an existing one in place), and it means an old
//   client's dispatch is a single `Map.get` — an unrecognized major version
//   is indistinguishable from an unrecognized type, which is exactly the
//   "unknown type" arm of the fallback chain this module already has to
//   implement regardless.
// - **Minor version, as a `schema_version` integer field inside `content`**.
//   An additive, backward-compatible change (a new optional field) bumps
//   this without changing the event type. A renderer that only reads the
//   fields it was written against — the whole point of `safeStringField`'s
//   shape — tolerates a higher `schema_version` for free: it simply never
//   looks at the new field. `resolveCustomEvent` still calls the registered
//   renderer for a newer-than-known `schema_version` (best-effort, per
//   `docs/matrix-events.md`'s "known type, unknown version" case) and marks
//   the result `newerVersion: true` so `Timeline.svelte` can show a subtle
//   "newer version" note rather than silently pretending nothing changed.
//
// Rejected alternatives:
// - **Version only in the type string** (`.v1`, `.v2`, `.v3`, … for every
//   change, additive or not). Simpler, but it means a client one minor
//   version behind treats a purely additive change as a fully unknown type —
//   the exact "must degrade gracefully on a newer minor version" case the
//   task calls out — and forces Kaambaan to mint (and every client to
//   register) a new type for every field it ever adds, forever.
// - **Version only inside `content`** (no suffix on the type string at all).
//   Cheaper to extend, but a breaking change then silently reuses the same
//   event type an old client already has a renderer registered for — that
//   renderer runs unmodified against a shape it was never written for,
//   which is a much worse failure mode than "falls back to placeholder": a
//   client that *thinks* it understands the payload can render subtly wrong
//   output instead of visibly degrading.
// - **Both, but expressed as one field** (e.g. `content.schemaVersion:
//   "2.3"`, no type suffix). Rejected for the same reason as the previous
//   bullet — nothing distinguishes "old client, ignore this" from "old
//   client, this is incompatible" without the client parsing and
//   understanding the major component anyway, which is just the type-suffix
//   convention with extra steps and no `Map.get`-by-type dispatch.
//
// `schema_version` (not `schemaVersion`) matches Matrix's own snake_case
// content-field convention (`avatar_url`, `power_level_content_override`,
// …) — this event's `content` is suite-shared wire format, not
// internal-to-this-app TypeScript, so it follows the wire's convention
// rather than this codebase's usual camelCase. This is this module's
// assumption pending Kaambaan's actual co-designed schema, not a demand on
// it — if Kaambaan's schema lands with a different minor-version field name,
// only `readSchemaVersion` below needs to change.
//
// ## Fallback chain
//
// `resolveCustomEvent` is the whole decision, in priority order:
//
// 1. Known type, a registered renderer produces at least one field →
//    render those fields (`status: "rendered"`).
// 2. Known type, but the renderer throws, or produces no fields (a
//    malformed payload it couldn't do anything useful with) → fall through
//    to 3.
// 3. A plain-text `content.body` fallback is present (Matrix convention:
//    every suite custom event should carry one, for clients — Element,
//    Cinny, or this app before a renderer existed — that don't understand
//    the type) → show it (`status: "fallbackBody"`).
// 4. Nothing else to show → the generic placeholder naming the event type
//    (`status: "placeholder"`), today's behaviour, unchanged. No custom
//    event ever reaches `Timeline.svelte` with nothing to render at all.
//
// ## Decisions
//
// `CustomEventRenderResult.decision` is **our UI contract, not a wire
// schema** — the distinction this module opens with, applied to the one
// case where it is easiest to blur. Nothing below describes, assumes or
// requires any particular shape inside `content`: a renderer reads whatever
// its own event type actually carries (one named field at a time, as
// always) and *translates* it into this shape. Kaambaan's permission-request
// event will be the first renderer to do so, wiring the operator's answer to
// that team's gate-resolution call (`docs/positioning.md`, wedge #3
// "Approvals from chat" — the suite's missing human-in-the-loop channel, and
// the reason `customMessage` handling exists at all). Until then no shipped
// renderer sets it, `boundDecision` returns `null` for every real event, and
// no button reaches the timeline — deliberately: spec §7.1 forbids shipping
// a visible control with nothing behind it.
//
// A decision on a card is the **only** place `--color-signal` (amber)
// appears in the entire application (spec §3, §7.1). Amber means "the
// operator owes someone an answer" and means nothing else — not a warning,
// not an error, not a newer-schema note. `Timeline.svelte`'s dispatch card
// switches the card's left edge and ground to the signal tokens exactly
// when `decision !== null`, which is why that field being trustworthy
// matters beyond mere layout.
//
// {@link boundDecision} therefore treats a renderer's `decision` as
// hostile, not merely as possibly-buggy: a renderer that echoed
// `content.decision` straight through would hand this module attacker-shaped
// data, and the fallout of accepting it is a bordered, amber, *clickable*
// object — a far better phishing surface than a mistyped text field. The
// validation is structural and total (see that function), and the failure
// mode is always "an ordinary card", never "a card with a broken button".
//
// ## Why renderers never recurse
//
// A Matrix event is capped at 64KiB, and this app bounds a custom event's
// `content` further still (`core::timeline::CUSTOM_PAYLOAD_MAX_BYTES`,
// 8KiB) — but *byte size* bounds nesting only weakly: `{"a":` repeated a few
// thousand times is a few thousand bytes and a few thousand levels deep.
// `JSON.parse` itself (native, and how Tauri's own IPC deserializes this
// value before this module ever sees it) doesn't recurse through user JS
// call frames, so it isn't at risk here — but a hand-written recursive
// *tree-walking* function absolutely would be, and this module deliberately
// contains none. Every accessor here (`safeStringField`,
// `readSchemaVersion`) reads one named key at a fixed depth and returns; a
// renderer that follows the same shape inherits the same guarantee for
// free. `resolveCustomEvent`'s `try`/`catch` around the renderer call is the
// backstop for the one case that discipline can't prevent by construction —
// a future renderer author who writes an unbounded recursive walk anyway —
// converting whatever it throws (a stack overflow among them) into the same
// graceful fallback a merely-wrong-shaped payload gets, never an
// unhandled exception that takes the timeline render down with it.

/** One label/value row a renderer contributes. Both are plain text — see
 * this module's doc comment for why nothing here may ever reach `{@html}`,
 * an `href`, an `src`, or a `style`. */
export interface CustomEventField {
  label: string;
  value: string;
}

/** One answer the operator can give to a {@link CustomEventDecision}.
 *
 * `label` is display text and is bounded/escaped like any other field.
 * `id` is an *identifier*, never rendered: it is what a renderer's own
 * event type calls this answer, and it is handed back verbatim to the
 * `onDecide` callback (and, once Kaambaan's gate resolution lands, to that
 * call). It is deliberately not truncated — see {@link boundDecision}. */
export interface CustomEventDecisionOption {
  id: string;
  label: string;
}

/** A pending decision the operator still owes an answer to — the UI
 * contract this module defines on its own result type, **not** a wire
 * schema (see this module's "Decisions" section for why that distinction is
 * the whole point, and for why a decision is the only place amber appears
 * in the app). A renderer translates whatever its event type actually
 * carries into this shape; it never passes a payload object through. */
export interface CustomEventDecision {
  prompt: string;
  options: CustomEventDecisionOption[];
}

/** What a renderer returns: the rows to show, or an empty list when the
 * payload had nothing this renderer could do anything useful with (treated
 * the same as an unrecognized type by {@link resolveCustomEvent} — falls
 * through to the plain-text `body`/generic placeholder). */
export interface CustomEventRenderResult {
  fields: CustomEventField[];
  /** Set by a renderer when the payload carries a decision the operator
   * still owes an answer to. No shipped renderer sets this yet — the
   * Kaambaan permission schema is the first that will. Validated by
   * {@link boundDecision} before it can reach the DOM: this field's static
   * type is a promise about the renderer's *intent*, never a guarantee
   * about its output. */
  decision?: CustomEventDecision;
}

/** A registered renderer for one custom event type (major version baked
 * into {@link CustomEventRenderer.eventType} — see this module's doc
 * comment). */
export interface CustomEventRenderer {
  /** The full Matrix event type this renderer handles, e.g.
   * `"dev.supermessage.demo.note.v1"`. Matches `TimelineItem.detail`
   * exactly (`core::timeline::classify_content`'s `customMessage` detail is
   * the raw `MessageLikeEventType`). */
  eventType: string;
  /** The highest `content.schema_version` this renderer was written
   * against and knows every field of. A payload with a higher
   * `schema_version` is still handed to {@link CustomEventRenderer.render}
   * (see this module's doc comment on additive minor versions) — this
   * value only controls whether {@link resolveCustomEvent} marks the result
   * `newerVersion: true`, not whether `render` runs at all. */
  maxKnownSchemaVersion: number;
  /**
   * Renders `content` (the bounded, already-parsed JSON payload —
   * `TimelineItem.customPayload`) plus the plain-text fallback `body`, into
   * label/value rows. `content` is untrusted, arbitrary JSON from anyone who
   * can send to the room: read named fields one level at a time (see
   * {@link safeStringField}), never assume a field has the type or shape
   * you expect, and never build a value containing HTML — see this module's
   * doc comment in full.
   *
   * May throw for any reason (a field of an unexpected type, a bug);
   * {@link resolveCustomEvent} catches it and falls back to `body`/the
   * generic placeholder, so a broken renderer degrades the one event it
   * failed on rather than the whole timeline.
   */
  render(content: unknown, body: string | null): CustomEventRenderResult;
}

/** A mutable table of renderers, keyed by {@link CustomEventRenderer.eventType}.
 * Construct one with {@link createCustomEventRegistry}, or use the module's
 * default {@link customEventRegistry} — `Timeline.svelte` (via
 * `timelineItemView.ts`) always renders through the latter. Kept as a plain
 * `Map`, not a class, so tests can build an isolated instance without any
 * of this module's own state. */
export type CustomEventRegistry = Map<string, CustomEventRenderer>;

/** Builds a fresh, empty (or pre-populated) registry. Tests should always
 * build their own with this rather than mutating {@link customEventRegistry}
 * — the module docblock's registration example is the one place production
 * code should reach for the shared instance. */
export function createCustomEventRegistry(
  renderers: readonly CustomEventRenderer[] = [],
): CustomEventRegistry {
  const registry: CustomEventRegistry = new Map();
  for (const renderer of renderers) {
    registerCustomEventRenderer(registry, renderer);
  }
  return registry;
}

/** Registers (or replaces, if `renderer.eventType` is already present)
 * `renderer` on `registry`. This — not reaching into the `Map` directly —
 * is "the documented way to register one" the module doc comment's example
 * uses. */
export function registerCustomEventRenderer(
  registry: CustomEventRegistry,
  renderer: CustomEventRenderer,
): void {
  registry.set(renderer.eventType, renderer);
}

/** Cap on how many fields {@link resolveCustomEvent} will pass through from
 * a renderer's result — defence in depth against a renderer (a future one,
 * not the demo below) that echoes an attacker-controlled *array* of
 * fields straight from the payload; the layout-overflow guard this
 * codebase applies to every other sender-controlled surface. */
const FIELD_MAX_COUNT = 12;
/** Cap, in UTF-16 code units, on a single field's rendered value — same
 * reasoning and same order of magnitude as
 * `timelineItemView.ts`'s `REPLY_PREVIEW_MAX_CHARS`: long enough to show a
 * real value, short enough that one field can't stretch a card arbitrarily
 * wide before the CSS-level `break-words`/`overflow-wrap` guard even gets a
 * chance to wrap it. */
const FIELD_VALUE_MAX_CHARS = 300;
/** Cap on a field's label — labels are usually a fixed string a renderer
 * writes itself (`"Title"`, …), but nothing stops a future renderer from
 * deriving one from the payload, so this is bounded for the same reason
 * the value is. */
const FIELD_LABEL_MAX_CHARS = 60;
/** Cap on how many options a pending decision may offer. Four, not twelve
 * like {@link FIELD_MAX_COUNT}: an option is a *button*, and a row of
 * buttons is the one part of a dispatch card the operator is meant to act
 * on under time pressure. Approve/Decline is the shape this exists for;
 * four leaves room for a "Approve once"/"Always allow" style variant
 * without ever becoming a menu, and caps how much amber a single event can
 * put on screen. */
const DECISION_MAX_OPTIONS = 4;

/** Truncates `value` to `maxChars` UTF-16 code units, appending an ellipsis
 * when anything was cut — the same display-truncation shape
 * `timelineItemView.ts`'s `replyPreviewExcerpt` already uses (cosmetic
 * only; the actual IPC-crossing bound lives in the core, see
 * `core::timeline::CUSTOM_PAYLOAD_MAX_BYTES`). */
function boundText(value: string, maxChars: number): string {
  if (value.length <= maxChars) return value;
  return `${value.slice(0, maxChars)}…`;
}

/** Caps both the number of fields and each field's label/value length —
 * applied to *every* renderer's result, not just the demo one, so a future
 * renderer that forgets to bound its own output still can't blow out the
 * layout (the class of bug `docs/matrix-events.md` and this codebase's own
 * history — the 4700px `<table>` regression `Timeline.svelte` documents —
 * both call out). */
function boundFields(fields: readonly CustomEventField[]): CustomEventField[] {
  return fields.slice(0, FIELD_MAX_COUNT).map((field) => ({
    label: boundText(field.label, FIELD_LABEL_MAX_CHARS),
    value: boundText(field.value, FIELD_VALUE_MAX_CHARS),
  }));
}

/**
 * Validates and bounds a renderer's `decision`, or returns `null` when
 * there is no trustworthy decision to show.
 *
 * The parameter is `unknown`, not {@link CustomEventDecision}, and that is
 * the point rather than an oversight: TypeScript's guarantee stops at this
 * module's edge, and the realistic mistake is a renderer that echoes
 * `content.decision` straight off an untrusted payload (`content` is
 * arbitrary JSON from anyone who can send to the room). Everything this
 * function accepts ends up in a bordered, amber, clickable object — the
 * highest-value surface in the timeline to get wrong — so nothing is
 * assumed and every level is checked explicitly:
 *
 * - not a non-null object → `null`. This arm is load-bearing and not
 *   merely defensive tidiness, which is worth stating because it is easy to
 *   talk yourself out of: the `Array.isArray(options)` check below does
 *   *not* subsume it. A **function** is `typeof "function"`, not
 *   `"object"`, and a function can carry a `prompt` string and an `options`
 *   array as ordinary properties — so without this line a function-valued
 *   `decision` would sail through every other check and render. There is a
 *   test named for exactly that;
 * - `prompt` not a `string` → `null` (never coerced: `String(value)` would
 *   happily stringify an attacker-controlled object);
 * - `options` not an array → `null`;
 * - an entry that is not an object, or whose `id` or `label` is not a
 *   `string` → that entry is dropped, its valid siblings survive;
 * - no valid entries left → `null`, so a malformed decision degrades to an
 *   ordinary card rather than to a card with a dead button (spec §7.1).
 *
 * The {@link DECISION_MAX_OPTIONS} cap counts *valid* options, not raw
 * array entries — slicing before validating would let six junk entries
 * hide two real ones.
 *
 * `prompt` and `label` are bounded exactly as fields are (the same two
 * constants, for the same layout reason). `id` is deliberately **not**
 * bounded: it is never rendered, and silently truncating an identifier
 * would turn "approve-restart-hermes-gateway…" into a value the gate
 * resolution on the other end has never heard of — a wrong answer sent
 * confidently, which is strictly worse than a long string in a callback.
 * Its length is already bounded upstream, where it matters, by
 * `core::timeline::CUSTOM_PAYLOAD_MAX_BYTES`.
 *
 * Pure and non-throwing by construction (no recursion, no coercion, fixed
 * depth) — `resolveCustomEvent`'s `try`/`catch` is a backstop for the
 * renderer, not for this.
 */
function boundDecision(decision: unknown): CustomEventDecision | null {
  if (decision === null || typeof decision !== "object") return null;
  const candidate = decision as { prompt?: unknown; options?: unknown };
  if (typeof candidate.prompt !== "string") return null;
  if (!Array.isArray(candidate.options)) return null;

  const options: CustomEventDecisionOption[] = [];
  for (const entry of candidate.options) {
    if (options.length >= DECISION_MAX_OPTIONS) break;
    if (entry === null || typeof entry !== "object") continue;
    const { id, label } = entry as { id?: unknown; label?: unknown };
    if (typeof id !== "string" || typeof label !== "string") continue;
    options.push({ id, label: boundText(label, FIELD_LABEL_MAX_CHARS) });
  }
  if (options.length === 0) return null;

  return { prompt: boundText(candidate.prompt, FIELD_VALUE_MAX_CHARS), options };
}

/**
 * Safely reads `content[key]` as a string, one level deep, capped to
 * `maxChars` — the shape every renderer should copy for reading a payload
 * field (see this module's doc comment, "Why renderers never recurse").
 * `null` when `content` isn't an object, the key is absent, or the value
 * isn't a string — a hostile or malformed payload degrades to "nothing
 * here", never a coercion (`String(value)`) that could stringify an
 * attacker-controlled object into something unexpected.
 */
export function safeStringField(content: unknown, key: string, maxChars: number): string | null {
  if (content === null || typeof content !== "object") return null;
  const value = (content as Record<string, unknown>)[key];
  return typeof value === "string" ? boundText(value, maxChars) : null;
}

/** Reads `content.schema_version` (see this module's doc comment for why
 * that field name, not `schemaVersion`) as a finite number, or `null` when
 * it's absent or not a well-formed number — treated by
 * {@link resolveCustomEvent} as "assume the baseline version", not as
 * "newer than known". */
function readSchemaVersion(content: unknown): number | null {
  if (content === null || typeof content !== "object") return null;
  const value = (content as Record<string, unknown>).schema_version;
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** {@link resolveCustomEvent}'s result — what `Timeline.svelte` actually
 * switches on. */
export type CustomEventView =
  | {
      status: "rendered";
      fields: CustomEventField[];
      newerVersion: boolean;
      /** The validated, bounded pending decision, or `null` — always
       * present on a `rendered` view so `Timeline.svelte` never has to
       * distinguish "no decision" from "this variant has no such field".
       * Only this variant can carry one: `fallbackBody` and `placeholder`
       * mean no renderer produced anything, so there is nothing that could
       * have set a decision in the first place. */
      decision: CustomEventDecision | null;
    }
  | { status: "fallbackBody"; text: string }
  | { status: "placeholder"; text: string };

/**
 * The whole fallback chain (see this module's doc comment) as one pure,
 * synchronous function: known type + a renderer that produced something →
 * render it; known type but the renderer threw or produced nothing → the
 * plain-text `body`; unknown type → the plain-text `body`; no body → the
 * generic placeholder. Never returns anything that lets `Timeline.svelte`
 * render "nothing" for a `customMessage` item.
 *
 * `registry` is a parameter, not read off the module's shared
 * {@link customEventRegistry}, specifically so this — and with it the whole
 * dispatch/fallback/version-tolerance/hostile-payload behaviour — is
 * testable with a small fixture registry and no component-mounting
 * infrastructure.
 */
export function resolveCustomEvent(
  registry: CustomEventRegistry,
  eventType: string | null,
  content: unknown,
  body: string | null,
): CustomEventView {
  const renderer = eventType != null ? registry.get(eventType) : undefined;
  if (renderer !== undefined) {
    try {
      const result = renderer.render(content, body);
      const fields = boundFields(result?.fields ?? []);
      if (fields.length > 0) {
        const schemaVersion = readSchemaVersion(content);
        const newerVersion =
          schemaVersion !== null && schemaVersion > renderer.maxKnownSchemaVersion;
        // `result?.decision` is typed `CustomEventDecision | undefined`, and
        // `boundDecision` still validates every level of it — see that
        // function's doc comment for why the static type is not the
        // guarantee it looks like here.
        return { status: "rendered", fields, newerVersion, decision: boundDecision(result?.decision) };
      }
    } catch {
      // A renderer must never be able to take the timeline down with it —
      // whatever went wrong, fall through to the same body/placeholder
      // chain an unrecognized type gets.
    }
  }

  if (body != null && body.trim() !== "") {
    return { status: "fallbackBody", text: body };
  }
  return { status: "placeholder", text: `Custom event (${eventType ?? "unknown"})` };
}

/**
 * The demo renderer shipped to prove the extension path end to end —
 * **not** a Kaambaan schema (see this module's doc comment: those are
 * co-designed with that team, never invented here). `dev.supermessage.demo.*`
 * is a namespace this app owns for exactly this purpose, distinct from any
 * real suite namespace, so it can never collide with — or be mistaken for —
 * a genuine card/run/permission-request type.
 *
 * Reads exactly one field (`title`), the minimum needed to demonstrate a
 * renderer that (a) only touches named fields at a fixed depth and (b)
 * tolerates a payload with extra, unrecognized fields (a higher
 * `schema_version`) without any special-casing — see this module's doc
 * comment on additive minor versions.
 */
export const DEMO_NOTE_EVENT_TYPE = "dev.supermessage.demo.note.v1";

export const demoNoteRenderer: CustomEventRenderer = {
  eventType: DEMO_NOTE_EVENT_TYPE,
  maxKnownSchemaVersion: 1,
  render(content) {
    const title = safeStringField(content, "title", FIELD_VALUE_MAX_CHARS);
    return { fields: title !== null ? [{ label: "Note", value: title }] : [] };
  },
};


/**
 * What an agent did during one turn — AgentPod's `dev.agentpod.turn.v1`.
 *
 * The first renderer here for a real event type rather than a demonstration.
 * It reads a bounded summary and nothing else: the wire carries at most twenty
 * tool records and a set of counts, and a card is a summary surface, not a log
 * viewer. Tool *output* never crosses the bridge at all.
 *
 * Reads named fields one level at a time, per this module's rules — but two of
 * the fields it wants are a number and an array, which `safeStringField` cannot
 * express, so it does that reading itself with the same discipline: check the
 * shape, take the value, never coerce, never recurse.
 */
export const TURN_ACTIVITY_EVENT_TYPE = "dev.agentpod.turn.v1";

/** A finite number at `content[key]`, or null. The numeric `safeStringField`. */
function safeNumberField(content: unknown, key: string): number | null {
  if (content === null || typeof content !== "object") return null;
  const value = (content as Record<string, unknown>)[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

export const turnActivityRenderer: CustomEventRenderer = {
  eventType: TURN_ACTIVITY_EVENT_TYPE,
  maxKnownSchemaVersion: 1,
  render(content) {
    if (content === null || typeof content !== "object") return { fields: [] };
    const counts = (content as Record<string, unknown>).counts;
    const total = safeNumberField(counts, "total");
    const failed = safeNumberField(counts, "failed") ?? 0;
    const omitted = safeNumberField(counts, "omitted") ?? 0;

    const rawTools = (content as Record<string, unknown>).tools;
    const tools = Array.isArray(rawTools) ? rawTools : [];

    const fields: CustomEventField[] = [];
    // The headline first: a reader scanning a conversation wants "it did seven
    // things and one failed" before it wants to know which seven.
    if (total !== null) {
      const failedNote = failed > 0 ? `, ${failed} failed` : "";
      fields.push({ label: "Did", value: `${total} ${total === 1 ? "thing" : "things"}${failedNote}` });
    }

    for (const entry of tools) {
      // `FIELD_MAX_COUNT` truncates anyway; stopping here keeps the headline
      // row from being the one that gets dropped.
      if (fields.length >= FIELD_MAX_COUNT - 1) break;
      const title = safeStringField(entry, "title", FIELD_VALUE_MAX_CHARS);
      if (title === null) continue;
      const status = safeStringField(entry, "status", FIELD_LABEL_MAX_CHARS);
      // The status is the label, so the card reads as a list of what happened
      // rather than a list of identical rows.
      fields.push({ label: status ?? "did", value: title });
    }

    if (omitted > 0) {
      fields.push({ label: "and", value: `${omitted} more not listed` });
    }
    return { fields };
  },
};

/**
 * A permission request a reader can answer — AgentPod's
 * `dev.agentpod.permission.v1`, and the first renderer anywhere to set a
 * `decision`.
 *
 * **`CustomEventDecisionOption.id` carries the option's NAME, not its
 * `option_id`.** That reads backwards until you see what `id` is for: it is
 * handed to `onDecide` verbatim and sent, and the room transcript is a shared
 * human record. The hub's own prose prints option names alongside the numbers
 * "because '1' alone would make the transcript unreadable afterwards" — and a
 * button that leaves `allow_once` in the room is the same mistake in a
 * different alphabet. `matchPermissionAnswer` on the hub accepts the number,
 * the name, or the id, so any of the three would work; the name is the one a
 * person reading the room later will understand.
 *
 * The event is sent *beside* an ordinary prose message carrying the same
 * question, so a client that never renders this — Element, or this one before
 * the renderer existed — is exactly as able to answer as it always was.
 */
export const PERMISSION_REQUEST_EVENT_TYPE = "dev.agentpod.permission.v1";

export const permissionRequestRenderer: CustomEventRenderer = {
  eventType: PERMISSION_REQUEST_EVENT_TYPE,
  maxKnownSchemaVersion: 1,
  render(content) {
    const title = safeStringField(content, "title", FIELD_VALUE_MAX_CHARS);
    if (title === null) return { fields: [] };

    const rawOptions = (content as Record<string, unknown>).options;
    const options: CustomEventDecisionOption[] = [];
    if (Array.isArray(rawOptions)) {
      for (const entry of rawOptions) {
        const name = safeStringField(entry, "name", FIELD_LABEL_MAX_CHARS);
        // An option with no name is one nothing could label or answer with.
        if (name === null) continue;
        options.push({ id: name, label: name });
      }
    }

    const fields: CustomEventField[] = [{ label: "Wants to", value: title }];
    // No options means nothing to decide — `boundDecision` would reject an
    // empty list anyway, and the card falls back to describing the request.
    if (options.length === 0) return { fields };
    return { fields, decision: { prompt: `Allow ${title}?`, options } };
  },
};

/** The registry `timelineItemView.ts`/`Timeline.svelte` render through in
 * production. Pre-populated with {@link demoNoteRenderer} — register a real
 * renderer here (see this module's doc comment for the call shape) once
 * Kaambaan's schemas land. */
export const customEventRegistry: CustomEventRegistry = createCustomEventRegistry([
  demoNoteRenderer,
  turnActivityRenderer,
  permissionRequestRenderer,
]);

/**
 * The custom event types whose mere presence as a room's *latest* event
 * means the operator owes someone an answer — the roster's counterpart to
 * this module's `decision` field, which is what the dispatch card keys its
 * amber off (see the "Decisions" section above).
 *
 * **This set is empty, and shipping it empty is the point.** It is the one
 * place `RoomSummary.lastEventType` is compared against anything, so an
 * empty set is a proof — not a promise — that
 * `$lib/components/roomPreview.ts` can never render `Approval needed`, and
 * that spec §6.1.1's amber roster row can never appear, from any event this
 * app can actually receive today. Spec §7.1's rule for the card applies
 * verbatim here: build the mechanism, cover it with a fixture, ship it
 * unreachable — never a visible signal with nothing behind it.
 *
 * The roster needs a *type* set where the card reads a rendered
 * `decision`, because the two surfaces see different things. The card has
 * the whole `content` and a renderer to translate it; the roster has one
 * string per room off the room-list diff and no payload at all.
 *
 * **Two independent reasons nothing can reach the amber path, both of which
 * must be understood before anyone adds an entry here:**
 *
 * 1. **No gate schema exists.** Kaambaan's permission-request event is
 *    co-designed with that team and has not landed. Inventing one here is
 *    the single thing this module opens by refusing to do.
 * 2. **The SDK never surfaces a custom event as a room's latest event.**
 *    `matrix-sdk`'s latest-event builder ends its message-like arm in an
 *    unqualified catch-all over `AnyMessageLikeEventContent`, and ruma's
 *    `_Custom` variant — which is what any suite event deserializes to —
 *    falls into it, so the scan walks straight past a gate to the ordinary
 *    message underneath. `RoomSummary.lastEventType` is therefore `null`
 *    for every event in production. This is the *same* gap
 *    `core::timeline::timeline_event_filter` had to patch for the timeline,
 *    except it lives inside the SDK's own background task with no builder
 *    hook to override it.
 *
 * **`dev.agentpod.permission.v1` is the obvious candidate and is deliberately
 * not here.** It landed with a renderer that sets a `decision`, so reason 1 no
 * longer applies to it — the card renders buttons today. Reason 2 does: the
 * roster still receives `lastEventType: null` for it, so adding it would change
 * nothing a reader could see while converting a *proven*-dead path into an
 * untested one that merely looks alive. The card is where the amber belongs
 * until the core can resolve its own preview event.
 *
 * Reason 2 **survives reason 1 being fixed**: landing the schema does not
 * make ruma recognize it. Adding a type here the day the schema lands would
 * therefore change nothing visible, and would quietly convert a proven-dead
 * path into an untested-and-still-dead one. The prerequisite is the core
 * resolving its own preview event (or an upstream fix), not this line —
 * `.superpowers/sdd/room-list-previews/core-report.md` records the
 * investigation.
 *
 * Typed `ReadonlySet` so nothing can mutate it at runtime, and exported
 * (rather than inlined into `roomPreview.ts`) so the decision-bearing types
 * live next to the decision contract itself.
 */
export const DECISION_BEARING_EVENT_TYPES: ReadonlySet<string> = new Set<string>();
