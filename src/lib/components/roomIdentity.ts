// Parses the structured room names this homeserver mints for agent rooms,
// plus a relative-time formatter for the same rows.
//
// Rooms on `id.agentpod.dev` are agents, and the homeserver names them with
// real structure: `<glyph> <Name> — <Role>`, e.g. "🧠 Buddhimaan — Squad
// Lead" or "🛡️ Threat Hunter Theo — Security". That structure is worth
// pulling apart — spec §5.1 sets the glyph, name and role at different
// visual ranks (roster avatar, row title, role chip) instead of truncating
// the whole string on one line and throwing it away.
//
// A room *without* that structure — a DM, a human-named room, a bridge like
// "aether-dispatches" — is not a broken room. `parseRoomIdentity` must
// degrade it silently to `{glyph: null, name: <the whole trimmed string>,
// role: null}`, never a placeholder or an error state. This module has no
// idea which rooms live on which homeserver; it just describes one string
// format and falls back safely whenever a string doesn't match it.
//
// Both functions are pure (no DOM, no store, no Svelte) so they're
// unit-testable exactly the way `timelineItemView.ts` and `draftTracker.ts`
// already are in this codebase's `environment: "node"` vitest setup. Task 3
// (`RoomList`), Task 4 (the room header) and Task 9 (the info panel) all
// consume `RoomIdentity` and `roomInitial`; the roster additionally uses
// `relativeTime` for its "· 4m" line (spec §6.1).

// The parse itself now lives in `core::room_identity`: it is the suite's
// naming convention, and iOS and Android read the same names. A room arrives
// with its `identity` already split (see `RoomRow`), as does a space and a
// room-info panel.
//
// `relativeTime` stays here because it reads a clock. It coarsens into "3h" /
// "1d" and has to re-evaluate as time passes, so shipping it on a DTO would
// send it stale.

/** Bucket boundaries for {@link relativeTime}, in milliseconds. */
const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;
const WEEK_MS = 7 * DAY_MS;

/**
 * The `>= 7 days` fallback formatter for {@link relativeTime}. `undefined`
 * locale — the system locale — matches the precedent `Timeline.svelte`
 * already sets for its own date/time formatters (`dateFormatter`/
 * `timeFormatter`, both constructed with `undefined`): a roster row whose
 * date reads `13 Aug` while the date divider six inches away in the
 * timeline reads `Aug 13` (or vice versa) would be a locale inconsistency
 * within the same screen. Built once at module scope rather than per call
 * for the same reason `Timeline.svelte` does — `Intl.DateTimeFormat`
 * construction is not cheap, and this runs once per roster row on every
 * render.
 */
const dateFallbackFormatter = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
});

/**
 * Formats `timestampMs` as a short relative age against `nowMs`: `"now"`
 * (under a minute), `"4m"`, `"2h"`, `"3d"`, then — once the gap reaches a
 * week — a short absolute date (`"Jul 14"`) rather than an ever-growing day
 * count nobody reads at a glance. Returns `null` when `timestampMs` is
 * `null` (spec §6.1: a room with no activity omits its time line entirely
 * rather than printing an empty or zero age).
 *
 * Takes `nowMs` as an explicit argument rather than reading `Date.now()`
 * itself, so callers (and this module's own tests) never depend on the
 * wall clock — the same reasoning `draftTracker.ts` documents for taking
 * `outgoingText` explicitly rather than reaching for ambient state.
 *
 * A negative delta — `timestampMs` in the future, from ordinary clock skew
 * against a homeserver's own timestamp rather than anything adversarial —
 * is clamped to zero before bucketing. Without the clamp this would print
 * a negative minute count instead of the "now" a reader actually wants to
 * see for an event that, as far as they're concerned, just happened.
 */
export function relativeTime(timestampMs: number | null, nowMs: number): string | null {
  if (timestampMs === null) return null;

  const deltaMs = Math.max(0, nowMs - timestampMs);

  if (deltaMs < MINUTE_MS) return "now";
  if (deltaMs < HOUR_MS) return `${Math.floor(deltaMs / MINUTE_MS)}m`;
  if (deltaMs < DAY_MS) return `${Math.floor(deltaMs / HOUR_MS)}h`;
  if (deltaMs < WEEK_MS) return `${Math.floor(deltaMs / DAY_MS)}d`;

  return dateFallbackFormatter.format(new Date(timestampMs));
}
