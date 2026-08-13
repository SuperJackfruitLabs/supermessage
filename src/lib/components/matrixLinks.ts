// Parses matrix.to URLs and `matrix:` URIs — per the Matrix spec's
// appendices ("matrix.to navigation" and "matrix: URI scheme") — into a
// small, uniform target type describing what they address: a room (by id or
// alias), a user, or something too malformed/unsupported to extract
// anything useful from. Pure and DOM-free, like `timelineGrouping.ts`/
// `timelineItemView.ts`/`draftTracker.ts`: no store import, no Svelte, so
// it's unit-testable without a DOM (this project's vitest runs with
// `environment: "node"`).
//
// Grammar verified against the spec (spec.matrix.org/latest/appendices),
// not guessed:
//   - matrix.to: `https://matrix.to/#/<identifier>[/<event-id>][?via=...]`,
//     where `<identifier>` carries its sigil (`!room:x`, `#alias:x`,
//     `@user:x`) and everything after the `#` — including a second, nested
//     `?via=` query string — is the URL's own `hash`, not its `search`
//     (`matrix.to`'s query lives *inside* the fragment, since the whole
//     address has to survive being pasted as a single opaque fragment on a
//     static redirector page).
//   - `matrix:`: `matrix:<type>/<id-without-sigil>[/e/<event-id-without-sigil>][?via=...]`,
//     `type` one of `u` (user), `r` (room alias), `roomid` (room id), with
//     an event reference only ever valid after `roomid` — a *path* segment
//     (`/e/<id>`), not a query parameter (a detail easy to get wrong; there
//     is no `?event=` in the real grammar). Unlike matrix.to, `matrix:` is a
//     non-special URI scheme the WHATWG `URL` parser already splits
//     correctly on its own: `url.pathname` is the opaque `<type>/<id>/...`
//     string and `url.search` is the real query string, verified against
//     this project's Node runtime.
//
// This app can act on exactly one of these forms: a room addressed **by
// id** that the account is already in (`resolveInAppRoomId`, driven by
// whatever room ids the caller currently knows about). Every other shape —
// a room by *alias* (there is no alias -> id resolution wired up anywhere in
// this build; `RoomSummary`, what the room list is built from, carries no
// alias field at all), a user id (no profile surface exists), a room the
// account isn't in (no join flow exists), or an event id (no "jump to
// event" capability exists in the timeline) — is a deliberate, reported
// limitation, not a silent gap. See `messageLinks.ts`'s doc comment for how
// each of those is actually disposed of (falling back to the system
// browser).

/** What a parsed matrix.to/`matrix:` link addresses. */
export type MatrixLinkTarget =
  | { kind: "room"; roomId: string; eventId: string | null }
  | { kind: "roomAlias"; alias: string; eventId: string | null }
  | { kind: "user"; userId: string }
  /**
   * Recognisably a matrix.to/`matrix:` link — so it must never silently
   * fall through as though {@link parseMatrixLink} didn't understand *any*
   * matrix link at all — but too malformed, or an address form this
   * grammar doesn't define (an unknown type qualifier, an empty
   * identifier), to extract anything from. Distinct from `null`, which
   * means "not a matrix.to/`matrix:` link in the first place".
   */
  | { kind: "unknown" };

/**
 * Parses `href` as a matrix.to URL or a `matrix:` URI. Returns `null` when
 * it's neither (an ordinary `https://`/`http://`/`mailto:` link, or
 * anything else) — the signal `messageLinks.ts` uses to leave its existing
 * system-browser fallback completely unchanged for those.
 *
 * Never throws: an `href` that isn't parseable as a URL at all (`new URL`
 * throwing — e.g. a bare word with no scheme) is treated as "not a matrix
 * link", the same as any other string this module doesn't recognise.
 */
export function parseMatrixLink(href: string): MatrixLinkTarget | null {
  let url: URL;
  try {
    url = new URL(href);
  } catch {
    return null;
  }

  if (url.protocol === "matrix:") return parseMatrixUri(url);
  if (isMatrixToUrl(url)) return parseMatrixToUrl(url);
  return null;
}

function isMatrixToUrl(url: URL): boolean {
  return (
    (url.protocol === "https:" || url.protocol === "http:") &&
    url.hostname.toLowerCase() === "matrix.to"
  );
}

/** Decodes a percent-encoded path segment; `null` on malformed encoding
 * (e.g. a lone trailing `%`) rather than letting `decodeURIComponent` throw. */
function decodeSegment(segment: string): string | null {
  try {
    const decoded = decodeURIComponent(segment);
    return decoded.length > 0 ? decoded : null;
  } catch {
    return null;
  }
}

/**
 * `https://matrix.to/#/<identifier>[/<event-id>][?via=...]`. Everything
 * after the URL's own `#` is `url.hash` (e.g. `"#/!room:x.org?via=y.org"`),
 * including the nested `?via=` query the WHATWG `URL` parser does *not*
 * split out on its own — the outer URL's `search` is empty for every real
 * matrix.to link, since matrix.to itself takes no query string of its
 * own — so this splits the hash by hand instead of reading `url.search`.
 */
function parseMatrixToUrl(url: URL): MatrixLinkTarget {
  const hash = url.hash.startsWith("#") ? url.hash.slice(1) : url.hash;
  const path = hash.startsWith("/") ? hash.slice(1) : hash;
  const pathPart = path.split("?")[0] ?? "";
  const segments = pathPart.split("/").filter((segment) => segment.length > 0);
  if (segments.length === 0) return { kind: "unknown" };

  const identifier = decodeSegment(segments[0]!);
  if (!identifier) return { kind: "unknown" };

  const eventId = segments[1] ? decodeSegment(segments[1]) : null;
  return targetFromSigil(identifier, eventId);
}

function targetFromSigil(identifier: string, eventId: string | null): MatrixLinkTarget {
  const sigil = identifier[0];
  const rest = identifier.slice(1);
  if (!rest) return { kind: "unknown" };

  switch (sigil) {
    case "!":
      return { kind: "room", roomId: identifier, eventId };
    case "#":
      // Per the spec: "Referencing event IDs within a room identified by
      // room alias rather than room ID is now deprecated." Still parsed
      // (never silently dropped), just never actionable — see this
      // module's doc comment.
      return { kind: "roomAlias", alias: identifier, eventId };
    case "@":
      return { kind: "user", userId: identifier };
    default:
      return { kind: "unknown" };
  }
}

/**
 * `matrix:<type>/<id-without-sigil>[/e/<event-id-without-sigil>][?...]`.
 * `url.pathname` is the whole opaque `<type>/<id>/...` string for a
 * non-special scheme like `matrix:`; `url.search` is a real, independently
 * parsed query string here (unlike matrix.to's `hash`-embedded one) — this
 * module doesn't need anything out of it (no `?event=` in the real
 * grammar — see this module's doc comment), so it's simply left unread.
 */
function parseMatrixUri(url: URL): MatrixLinkTarget {
  const segments = url.pathname.split("/").filter((segment) => segment.length > 0);
  if (segments.length < 2) return { kind: "unknown" };

  const [type, rawId, eventMarker, rawEventId] = segments;
  const id = decodeSegment(rawId!);
  if (!id) return { kind: "unknown" };

  const rawEvent = eventMarker === "e" && rawEventId ? decodeSegment(rawEventId) : null;
  const eventId = rawEvent ? `$${rawEvent}` : null;

  switch (type) {
    case "u":
      return { kind: "user", userId: `@${id}` };
    case "r":
      return { kind: "roomAlias", alias: `#${id}`, eventId };
    case "roomid":
      return { kind: "room", roomId: `!${id}`, eventId };
    default:
      return { kind: "unknown" };
  }
}

/**
 * The room id to select in-app for `target`, or `null` when there is
 * nothing in-app to do with it — every case *except* "a room, addressed by
 * id, that `knownRoomIds` already contains" resolves to `null` here
 * (including `target === null`, so callers can pass `parseMatrixLink`'s
 * result straight through without a separate null check). See this
 * module's doc comment for why each of the other cases is a deliberate
 * limit, not a bug.
 */
export function resolveInAppRoomId(
  target: MatrixLinkTarget | null,
  knownRoomIds: readonly string[],
): string | null {
  if (target?.kind !== "room") return null;
  return knownRoomIds.includes(target.roomId) ? target.roomId : null;
}
