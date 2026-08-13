// Delegated click handling for the rendered message content in
// `Timeline.svelte`, which sets a bubble's HTML directly via `{@html}` (see
// that component's doc comment for why that's safe here). A link inside
// that HTML is a bare DOM `<a>` — this SPA has no browser chrome, so letting
// the webview navigate to it directly would replace the whole app with the
// target page and leave no way back. This intercepts the click instead and
// either handles it in-app (a matrix.to/`matrix:` link addressing a room the
// account is already in — see below) or hands the `href` to the OS's
// default browser through `tauri-plugin-opener`, exactly as before.
//
// This is one of two independent layers against an in-webview navigation,
// not the only one: per UI Events, a primary-button (left) click and a
// keyboard activation (Enter/Space on a focused `<a>`) both dispatch a real
// `click` event, which `handleMessageBodyClick` (wired to `onclick`)
// catches — but a non-primary-button press (middle-click, "open in new
// tab") dispatches `auxclick` instead, which a plain `onclick` handler never
// sees at all. `handleMessageBodyAuxClick` (wired to `onauxclick`, guarded
// to the middle button specifically) covers that, routing through this same
// module — including the same in-app room selection — rather than treating
// a middle-click any differently. The real backstop is
// `src-tauri/src/lib.rs`'s `on_navigation` handler on the main window, which
// refuses any navigation whose origin isn't the app's own regardless of what
// triggered it — these two click handlers only cover paths this file knows
// about today; that one covers all of them, including ones nobody has
// thought of yet. In-app room selection never touches that path at all
// (`preventDefault` already stops the anchor's own navigation; selecting a
// room is a Svelte state change, not a webview navigation), so it can't
// regress it.
//
// ## In-app matrix.to/`matrix:` links
//
// `matrixLinks.ts`'s `parseMatrixLink`/`resolveInAppRoomId` decide what, if
// anything, a link addresses that this app can act on without leaving it —
// see that module's doc comment for the exact grammar and, importantly, its
// honestly-reported limits. In short: only a room addressed **by id**, that
// the account is already a member of, is selected in-app
// (`selectRoom`/`knownRoomIds` below). Every other case — a room by alias
// (no alias -> id resolution exists in this build), a user id (no profile
// surface exists), a room the account isn't in (no join flow exists), a
// malformed/unrecognised matrix link, or an ordinary `https://`/`mailto:`
// link — falls through to the exact same system-browser `open()` call this
// file already made for everything, unchanged. A matrix.to link opened in
// the browser still works (matrix.to itself redirects into whatever Matrix
// client the OS has registered); a `matrix:` URI opened this way only works
// if some app has registered that scheme — neither is worse than this app's
// prior behavior of sending every one of these to the browser.
//
// `selectRoom`/`knownRoomIds` default to safe, real-store-free no-ops (never
// select anything, no room is ever "known") precisely so that importing or
// unit-testing this module never has to construct — or accidentally
// trigger — the real `roomsStore` singleton, whose construction talks to
// Tauri's `@tauri-apps/api/event` `listen()` immediately (see
// `rooms.svelte.ts`), which throws outside a real Tauri webview. `Timeline.svelte`,
// the sole real caller, supplies the real `roomsStore`-backed callbacks
// explicitly at its call sites instead of relying on these defaults.
//
// Pure functions, not Svelte event handlers themselves, so they're
// unit-testable without a DOM (this project's vitest runs with
// `environment: "node"`, same constraint `draftTracker.ts` and
// `timelineItemView.ts` are built around): they take the minimal shape they
// need out of the click event and element, plus injectable `open`/
// `selectRoom`/`knownRoomIds` functions, so a test can hand them plain fakes
// and assert on those instead of asserting anything about real navigation or
// a real store.

import { openUrl } from "@tauri-apps/plugin-opener";
import { parseMatrixLink, resolveInAppRoomId } from "./matrixLinks";

/** The parts of a DOM click event this module actually touches. */
export interface MessageBodyClickEvent {
  readonly target: EventTarget | null;
  preventDefault(): void;
}

/** The parts of a DOM `auxclick` event this module actually touches — a click event plus `button`. */
export interface MessageBodyAuxClickEvent extends MessageBodyClickEvent {
  readonly button: number;
}

/** The parts of a DOM element this module actually touches. */
interface ClosestCapable {
  closest(selector: string): { getAttribute(name: string): string | null } | null;
}

function isClosestCapable(value: unknown): value is ClosestCapable {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { closest?: unknown }).closest === "function"
  );
}

/** Default `selectRoom`: does nothing. See this file's top-of-module doc
 * comment for why the real `roomsStore` is never imported here. */
function noopSelectRoom(_roomId: string): void {}

/** Default `knownRoomIds`: no room is ever "known", so `resolveInAppRoomId`
 * always returns `null` and every matrix link falls through to `open()` —
 * exactly this module's pre-existing behavior. See this file's top-of-module
 * doc comment. */
function noKnownRoomIds(): readonly string[] {
  return [];
}

/**
 * Handles a click that landed somewhere inside a rendered message body.
 *
 * If the click was on, or inside, an `<a href>` (an icon/`<strong>` nested
 * in a link is still "on" it — that's what `closest` is for), the default
 * navigation is prevented. From there:
 *   - If the link addresses a room (by id) the caller reports as known
 *     (`knownRoomIds`), `selectRoom` is called with that room id instead of
 *     opening anything — see this file's top-of-module doc comment for
 *     exactly which links qualify and why every other shape doesn't.
 *   - Otherwise, `open` is called with the link's `href`, exactly as before.
 * A click anywhere else in the message body is left alone.
 *
 * `open` defaults to the real `openUrl` from `tauri-plugin-opener`;
 * `selectRoom`/`knownRoomIds` default to inert no-ops (see this file's
 * top-of-module doc comment for why). Tests pass fakes for all three so
 * nothing here actually shells out or touches a real store.
 *
 * `open`'s rejection (e.g. a `matrix:` link the opener's capability scope
 * doesn't cover, since only `http(s)`/`mailto` are granted by default — see
 * `src-tauri/capabilities/default.json`) is caught and logged rather than
 * left as an unhandled promise rejection: the click was already prevented
 * either way, so the failure mode is "nothing visibly happens", never "the
 * app navigates away".
 */
export function handleMessageBodyClick(
  event: MessageBodyClickEvent,
  open: (url: string) => Promise<void> = openUrl,
  selectRoom: (roomId: string) => void = noopSelectRoom,
  knownRoomIds: () => readonly string[] = noKnownRoomIds,
): void {
  const { target } = event;
  if (!isClosestCapable(target)) return;

  const anchor = target.closest("a[href]");
  if (!anchor) return;

  // Prevented unconditionally once an `<a href>` is found, even if the
  // `href` attribute value below turns out empty — an in-webview navigation
  // to "the current page" is still a navigation this app has no chrome to
  // recover from, so there is no safe case to fall through to the default
  // handling. Selecting a room in-app is not a navigation either way — it's
  // a Svelte state change — so preventing the anchor's own default here is
  // correct and sufficient for that path too.
  event.preventDefault();

  const href = anchor.getAttribute("href");
  if (!href) return;

  const target_ = parseMatrixLink(href);
  // `knownRoomIds()` is only ever called for a link that actually parsed as
  // a room-by-id target — never for an ordinary link, and never for an
  // alias/user/unknown one — so the common case (a plain https link) never
  // touches whatever `knownRoomIds` is wired to at all.
  if (target_?.kind === "room") {
    const roomId = resolveInAppRoomId(target_, knownRoomIds());
    if (roomId) {
      selectRoom(roomId);
      return;
    }
  }

  void open(href).catch((err) => {
    console.error("failed to open link in the system browser", href, err);
  });
}

/**
 * The `auxclick` counterpart to {@link handleMessageBodyClick} — see this
 * module's doc comment for why `click` alone isn't enough. Only acts on
 * `button === 1` (the middle button); every other auxiliary button (e.g.
 * the right button, on platforms that route it through `auxclick` rather
 * than the `contextmenu` event) is left alone. Forwards every argument
 * (including `selectRoom`/`knownRoomIds`) straight through to
 * {@link handleMessageBodyClick}, so a middle-click on an in-app-routable
 * room link is handled identically to a primary click on one.
 */
export function handleMessageBodyAuxClick(
  event: MessageBodyAuxClickEvent,
  open: (url: string) => Promise<void> = openUrl,
  selectRoom: (roomId: string) => void = noopSelectRoom,
  knownRoomIds: () => readonly string[] = noKnownRoomIds,
): void {
  if (event.button !== 1) return;
  handleMessageBodyClick(event, open, selectRoom, knownRoomIds);
}
