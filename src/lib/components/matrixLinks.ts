// Parses matrix.to URLs and `matrix:` URIs — per the Matrix spec's
// appendices ("matrix.to navigation" and "matrix: URI scheme") — into a
// small, uniform target type describing what they address: a room (by id or
// alias), a user, or something too malformed/unsupported to extract
// anything useful from. Pure and DOM-free, like `timelineGrouping.ts`/
// `core::item_view`/`draftTracker.ts`: no store import, no Svelte, so
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

// The grammar moved to `core::matrix_links`. What is left here is the one
// question that is this app's rather than the protocol's: which of the things
// a link can address this build can actually act on.

import type { MatrixLinkTarget } from "$lib/ipc";

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
