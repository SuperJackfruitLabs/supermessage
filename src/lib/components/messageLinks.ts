// Delegated click handling for the rendered message content in
// `Timeline.svelte`, which sets a bubble's HTML directly via `{@html}` (see
// that component's doc comment for why that's safe here). A link inside
// that HTML is a bare DOM `<a>` — this SPA has no browser chrome, so letting
// the webview navigate to it directly would replace the whole app with the
// target page and leave no way back. This intercepts the click instead and
// hands the `href` to the OS's default browser through `tauri-plugin-opener`.
//
// This is one of two independent layers against that outcome, not the only
// one: per UI Events, a primary-button (left) click and a keyboard
// activation (Enter/Space on a focused `<a>`) both dispatch a real `click`
// event, which `handleMessageBodyClick` (wired to `onclick`) catches — but a
// non-primary-button press (middle-click, "open in new tab") dispatches
// `auxclick` instead, which a plain `onclick` handler never sees at all.
// `handleMessageBodyAuxClick` (wired to `onauxclick`, guarded to the middle
// button specifically) covers that. The real backstop is
// `src-tauri/src/lib.rs`'s `on_navigation` handler on the main window, which
// refuses any navigation whose origin isn't the app's own regardless of what
// triggered it — these two click handlers only cover paths this file knows
// about today; that one covers all of them, including ones nobody has
// thought of yet.
//
// Pure functions, not Svelte event handlers themselves, so they're
// unit-testable without a DOM (this project's vitest runs with
// `environment: "node"`, same constraint `draftTracker.ts` and
// `timelineItemView.ts` are built around): they take the minimal shape they
// need out of the click event and element, and an injectable `open`
// function, so a test can hand them plain fakes and assert the opener was
// called instead of asserting anything about real navigation.

import { openUrl } from "@tauri-apps/plugin-opener";

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

/**
 * Handles a click that landed somewhere inside a rendered message body.
 *
 * If the click was on, or inside, an `<a href>` (an icon/`<strong>` nested
 * in a link is still "on" it — that's what `closest` is for), the default
 * navigation is prevented and `open` is called with the link's `href`
 * instead. A click anywhere else in the message body is left alone.
 *
 * `open` defaults to the real `openUrl` from `tauri-plugin-opener`; tests
 * pass a fake so nothing here actually shells out. Its rejection (e.g. a
 * `matrix:` link the opener's capability scope doesn't cover, since only
 * `http(s)`/`mailto` are granted by default — see
 * `src-tauri/capabilities/default.json`) is caught and logged rather than
 * left as an unhandled promise rejection: the click was already prevented
 * either way, so the failure mode is "nothing visibly happens", never "the
 * app navigates away".
 */
export function handleMessageBodyClick(
  event: MessageBodyClickEvent,
  open: (url: string) => Promise<void> = openUrl,
): void {
  const { target } = event;
  if (!isClosestCapable(target)) return;

  const anchor = target.closest("a[href]");
  if (!anchor) return;

  // Prevented unconditionally once an `<a href>` is found, even if the
  // `href` attribute value below turns out empty — an in-webview navigation
  // to "the current page" is still a navigation this app has no chrome to
  // recover from, so there is no safe case to fall through to the default
  // handling.
  event.preventDefault();

  const href = anchor.getAttribute("href");
  if (!href) return;

  void open(href).catch((err) => {
    console.error("failed to open link in the system browser", href, err);
  });
}

/**
 * The `auxclick` counterpart to {@link handleMessageBodyClick} — see this
 * module's doc comment for why `click` alone isn't enough. Only acts on
 * `button === 1` (the middle button); every other auxiliary button (e.g.
 * the right button, on platforms that route it through `auxclick` rather
 * than the `contextmenu` event) is left alone.
 */
export function handleMessageBodyAuxClick(
  event: MessageBodyAuxClickEvent,
  open: (url: string) => Promise<void> = openUrl,
): void {
  if (event.button !== 1) return;
  handleMessageBodyClick(event, open);
}
