// Delegated click handling for the rendered message content in
// `Timeline.svelte`, which sets a bubble's HTML directly via `{@html}` (see
// that component's doc comment for why that's safe here). A link inside
// that HTML is a bare DOM `<a>` — this SPA has no browser chrome, so letting
// the webview navigate to it directly would replace the whole app with the
// target page and leave no way back. This intercepts the click instead and
// hands the `href` to the OS's default browser through `tauri-plugin-opener`.
//
// A pure function, not a Svelte event handler itself, so it's unit-testable
// without a DOM (this project's vitest runs with `environment: "node"`, same
// constraint `draftTracker.ts` and `timelineItemView.ts` are built around):
// it takes the minimal shape it needs out of the click event and element,
// and an injectable `open` function, so a test can hand it plain fakes and
// assert the opener was called instead of asserting anything about real
// navigation.

import { openUrl } from "@tauri-apps/plugin-opener";

/** The parts of a DOM click event this module actually touches. */
export interface MessageBodyClickEvent {
  readonly target: EventTarget | null;
  preventDefault(): void;
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
