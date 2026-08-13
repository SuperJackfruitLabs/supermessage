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

/** The parsed structure of a room name, per spec §5.1. */
export interface RoomIdentity {
  /** The leading emoji/symbol, or `null` when the name doesn't start with one. */
  glyph: string | null;
  /**
   * The room's display name. Never empty — a name that would otherwise be
   * empty (blank input, or a string that's nothing but the separator)
   * becomes the literal `"Unnamed room"` instead, so a caller can always
   * render this directly as a roster row title or an avatar fallback source
   * without an extra empty-string check.
   */
  name: string;
  /** The role/team half after the em dash, or `null` when there isn't one. */
  role: string | null;
}

/**
 * Hard caps on the parsed halves, applied after trimming and before
 * returning — layout safety against a hostile homeserver, consistent with
 * every other sender-controlled string surface in this codebase (e.g.
 * `timelineItemView.ts`'s `REPLY_PREVIEW_MAX_CHARS`,
 * `customEvents.ts`'s `FIELD_VALUE_MAX_CHARS`). A room's display name is
 * exactly that: attacker-controlled text from a Matrix homeserver, and
 * nothing stops it from being megabytes long.
 */
const MAX_NAME_CHARS = 120;
const MAX_ROLE_CHARS = 40;

/** The literal string every caller (roster row, header, avatar) can render for a name that would otherwise be empty. */
const UNNAMED_ROOM = "Unnamed room";

/**
 * Splits `<Name> — <Role>` on the *first* em dash (U+2014) that has
 * whitespace before it. Whitespace is required before the dash but
 * optional after — two separate, deliberate asymmetries:
 *
 * - **Required before**: without it, a plain hyphen inside a name like
 *   "aether-dispatches" would split into a bogus name/role pair. An em
 *   dash preceded by whitespace is specific enough to this homeserver's
 *   naming convention that nothing else on Matrix produces it by accident.
 * - **Optional after**: a trailing "Buddhimaan —" with nothing following
 *   the dash must still split (into name "Buddhimaan", role `null`) rather
 *   than being treated as *not* a separator and left as a name with a
 *   dangling dash. Making the trailing whitespace optional is what lets
 *   the empty-role case fall out of the normal "trim then null-if-empty"
 *   handling below instead of needing its own branch.
 *
 * Matches once and slices around that match, rather than using
 * `String.prototype.split` or a global regex — a global match would find
 * *every* qualifying dash, and `"Coder Kai — Code — Build"` must keep its
 * second dash as part of the role ("Code — Build"), not split there too.
 */
const SEPARATOR = /\s+—\s*/;

/** The separator's own character, U+2014. Excluded below from ever being read as a glyph — see {@link looksLikeGlyph}. */
const EM_DASH_CODEPOINT = 0x2014;

/**
 * The leading glyph candidate: every code point up to (not including) the
 * first whitespace run, captured as its own group so the caller can test
 * whether it's actually glyph-shaped before committing to it.
 *
 * Iterating "up to the first whitespace" rather than taking a single
 * `[...s][0]` code point is what keeps a multi-code-point grapheme (a
 * variation sequence, a ZWJ sequence) intact: "🛡️" is U+1F6E1 SHIELD
 * followed by U+FE0F VARIATION SELECTOR-16, two separate code points that
 * must travel together or the glyph renders as a bare, unstyled shield.
 */
const LEADING_TOKEN = /^(\S+)\s/;

function bound(s: string, max: number): string {
  return s.length > max ? s.slice(0, max) : s;
}

/**
 * Whether `token` (the run of non-whitespace characters before the first
 * space) is shaped like a glyph rather than an ordinary word: its first
 * code point must be outside the ASCII range. Iterates code points
 * (`[...token]`), not UTF-16 code units, so a check against an astral
 * character (anything above U+FFFF, encoded as a surrogate pair) reads its
 * whole first code point rather than half of it — the exact bug this
 * module exists to not repeat. `RoomList.svelte`'s pre-existing `initials`
 * helper indexed `name[0]` directly and rendered a lone surrogate as tofu
 * on every emoji-named row in this deployment; see that function's comment
 * for the fix this module generalizes.
 *
 * The em dash itself is explicitly excluded even though its code point
 * (U+2014) is `> 0x7f`: it is this format's own separator punctuation, not
 * a glyph, and a name with nothing meaningful before a leading dash (e.g.
 * `"— Squad Lead"`, which has no whitespace *before* the dash and so never
 * matches {@link SEPARATOR} either) must come back as that whole string
 * verbatim rather than having the dash misread as a one-character glyph.
 */
function looksLikeGlyph(token: string): boolean {
  const first = [...token][0];
  if (first === undefined) return false;
  const codePoint = first.codePointAt(0)!;
  return codePoint > 0x7f && codePoint !== EM_DASH_CODEPOINT;
}

/**
 * Parses a raw Matrix room name into {@link RoomIdentity}. See this
 * module's doc comment for the format and why a non-matching name is a
 * normal, expected outcome rather than an error.
 */
export function parseRoomIdentity(rawName: string): RoomIdentity {
  if (rawName.trim() === "") {
    return { glyph: null, name: UNNAMED_ROOM, role: null };
  }

  // Deliberately *not* pre-trimmed before this search: `SEPARATOR` needs to
  // see the raw whitespace around the dash to decide whether it's a
  // separator at all, and a name/role half that turns out to be nothing
  // but that surrounding whitespace (e.g. `" — "`, matched whole) must
  // collapse to an empty half — trimming first would already have eaten
  // the evidence `SEPARATOR` needs.
  const sepMatch = SEPARATOR.exec(rawName);
  const nameHalf = (sepMatch ? rawName.slice(0, sepMatch.index) : rawName).trim();
  const roleHalf = sepMatch ? rawName.slice(sepMatch.index + sepMatch[0].length).trim() : "";

  const glyphMatch = LEADING_TOKEN.exec(nameHalf);
  const hasGlyph = glyphMatch !== null && looksLikeGlyph(glyphMatch[1]!);
  const glyph = hasGlyph ? glyphMatch![1]! : null;
  const nameWithoutGlyph = hasGlyph ? nameHalf.slice(glyphMatch![0].length).trim() : nameHalf;

  const name = nameWithoutGlyph === "" ? UNNAMED_ROOM : bound(nameWithoutGlyph, MAX_NAME_CHARS);
  const role = roleHalf === "" ? null : bound(roleHalf, MAX_ROLE_CHARS);

  return { glyph, name, role };
}

/**
 * The single character to show in a roster/header avatar's fallback slot:
 * the parsed glyph when there is one, otherwise the first code point of the
 * parsed `name`, uppercased. Always derived from the *parsed* `name`, never
 * the raw room-name string directly — spec §6.1 is explicit that the
 * fallback must never be the raw first character of the full room name,
 * because for a structured room that character is the glyph itself
 * (already surfaced via `identity.glyph`), and for an unstructured one it
 * could just as easily be leading whitespace or punctuation the parse
 * already stripped.
 */
export function roomInitial(identity: RoomIdentity): string {
  if (identity.glyph !== null) return identity.glyph;
  const first = [...identity.name][0];
  return first === undefined ? "?" : first.toUpperCase();
}

/** Bucket boundaries for {@link relativeTime}, in milliseconds. */
const MINUTE_MS = 60_000;
const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;
const WEEK_MS = 7 * DAY_MS;

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

  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(
    new Date(timestampMs),
  );
}
