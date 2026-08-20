// Covers `handleMessageBodyClick`'s decision of when to intercept a click
// inside `{@html}`-rendered message content and route it to the system
// browser instead of letting the webview navigate — see `messageLinks.ts`'s
// doc comment for why that matters in a chrome-less SPA. No DOM: this
// project's vitest runs with `environment: "node"` (see `draftTracker.test.ts`
// for the same constraint), so every "element" here is a plain object that
// only implements the one method (`closest`) the module under test calls.

import { describe, expect, it, vi } from "vitest";
import type { MatrixLinkTarget } from "$lib/ipc";
import {
  handleMessageBodyAuxClick,
  handleMessageBodyClick,
  type MessageBodyAuxClickEvent,
  type MessageBodyClickEvent,
} from "./messageLinks";

/**
 * Stands in for the core's parse.
 *
 * The grammar itself is `core::matrix_links`' and is tested there — 32 cases
 * of it. What these tests own is what the *handler* does with a result:
 * prevent the default, route in-app, or fall back to the system browser. So
 * this recognises just enough shape to drive those three paths.
 */
async function parse(href: string): Promise<MatrixLinkTarget | null> {
  const viaTo = /^https:\/\/matrix\.to\/#\/(![^/?]+)/.exec(href);
  if (viaTo) return { kind: "room", roomId: viaTo[1]!, eventId: null };
  const viaUri = /^matrix:roomid\/([^/?]+)/.exec(href);
  if (viaUri) return { kind: "room", roomId: `!${viaUri[1]!}`, eventId: null };
  const user = /^matrix:u\/([^/?]+)/.exec(href);
  if (user) return { kind: "user", userId: `@${user[1]!}` };
  if (href.startsWith("https://matrix.to/") || href.startsWith("matrix:")) {
    return { kind: "unknown" };
  }
  return null;
}

/** A fake DOM element exposing only `closest`, resolving to `anchor` (or nothing). */
function elementWithClosestAnchor(anchor: { getAttribute(name: string): string | null } | null) {
  return { closest: (selector: string) => (selector === "a[href]" ? anchor : null) };
}

function fakeAnchor(href: string | null) {
  return { getAttribute: (name: string) => (name === "href" ? href : null) };
}

/** A fake click event plus a directly-typed handle to its `preventDefault` mock, since
 * intersecting `MessageBodyClickEvent` with vitest's `Mock` type directly confuses inference. */
function fakeEvent(target: unknown): {
  event: MessageBodyClickEvent;
  preventDefault: ReturnType<typeof vi.fn>;
} {
  const preventDefault = vi.fn();
  const event: MessageBodyClickEvent = { target: target as EventTarget | null, preventDefault };
  return { event, preventDefault };
}

function fakeAuxEvent(
  target: unknown,
  button: number,
): {
  event: MessageBodyAuxClickEvent;
  preventDefault: ReturnType<typeof vi.fn>;
} {
  const preventDefault = vi.fn();
  const event: MessageBodyAuxClickEvent = {
    target: target as EventTarget | null,
    button,
    preventDefault,
  };
  return { event, preventDefault };
}

describe("handleMessageBodyClick", () => {
  it("ignores a click that landed on no anchor", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(null));

    await handleMessageBodyClick(event, open, undefined, undefined, parse);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("ignores a click whose target isn't a DOM-like element at all", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeEvent(null);

    await handleMessageBodyClick(event, open, undefined, undefined, parse);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("prevents default and opens the link's href via the system opener, not navigation", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, undefined, undefined, parse);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org/path");
  });

  it("still intercepts when the click landed on an element nested inside the anchor", async () => {
    // `closest` is what makes this work against real markup like
    // `<a href="..."><strong>text</strong></a>` — the fake just asserts the
    // module queries with the selector that finds it.
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, undefined, undefined, parse);

    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org");
  });

  it("prevents default but does not call open when the anchor has no href value", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor(null);
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, undefined, undefined, parse);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).not.toHaveBeenCalled();
  });

  it("swallows a rejected open() instead of letting it become an unhandled rejection", async () => {
    const open = vi.fn().mockRejectedValue(new Error("scheme not permitted"));
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const anchor = fakeAnchor("matrix:u/alice:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await expect(handleMessageBodyClick(event, open, undefined, undefined, parse)).resolves.toBeUndefined();
    // Let the rejected promise's `.catch` microtask run.
    await Promise.resolve();
    await Promise.resolve();

    expect(consoleError).toHaveBeenCalled();
    consoleError.mockRestore();
  });
});

describe("handleMessageBodyAuxClick", () => {
  // Per UI Events, a non-primary-button press dispatches `auxclick`, not
  // `click` — so this is the only thing standing between a middle-click on
  // a rendered link and an in-webview navigation. See `messageLinks.ts`'s
  // module doc comment for the second, independent (Rust-side) layer this
  // backs up.

  it("intercepts a middle-click (button 1) on a link exactly like a primary click", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(anchor), 1);

    await handleMessageBodyAuxClick(event, open, undefined, undefined, parse);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org/path");
  });

  it("ignores a non-middle auxiliary button (e.g. the right button)", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(anchor), 2);

    await handleMessageBodyAuxClick(event, open, undefined, undefined, parse);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("ignores a middle-click that landed on no anchor", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(null), 1);

    await handleMessageBodyAuxClick(event, open, undefined, undefined, parse);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("routes an in-app-routable room link the same way a primary click would", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!room:example.org"]);
    const anchor = fakeAnchor("https://matrix.to/#/!room:example.org");
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(anchor), 1);

    await handleMessageBodyAuxClick(event, open, selectRoom, knownRoomIds, parse);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(selectRoom).toHaveBeenCalledExactlyOnceWith("!room:example.org");
    expect(open).not.toHaveBeenCalled();
  });
});

describe("handleMessageBodyClick: matrix.to/matrix: links", () => {
  it("selects the room in-app instead of opening it, for a matrix.to room-id link the account is already in", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!room:example.org", "!other:example.org"]);
    const anchor = fakeAnchor("https://matrix.to/#/!room:example.org");
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, knownRoomIds, parse);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(selectRoom).toHaveBeenCalledExactlyOnceWith("!room:example.org");
    expect(open).not.toHaveBeenCalled();
  });

  it("selects the room in-app for a matrix: roomid URI too", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!room:example.org"]);
    const anchor = fakeAnchor("matrix:roomid/room:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, knownRoomIds, parse);

    expect(selectRoom).toHaveBeenCalledExactlyOnceWith("!room:example.org");
    expect(open).not.toHaveBeenCalled();
  });

  it("falls back to the system browser for a room-id link the account is not in — there is no join flow", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!other:example.org"]);
    const anchor = fakeAnchor("https://matrix.to/#/!unknown:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, knownRoomIds, parse);

    expect(selectRoom).not.toHaveBeenCalled();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://matrix.to/#/!unknown:example.org");
  });

  it("falls back to the system browser for a room-alias link — no alias -> id resolution exists", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!room:example.org"]);
    const anchor = fakeAnchor("https://matrix.to/#/%23somewhere:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, knownRoomIds, parse);

    expect(selectRoom).not.toHaveBeenCalled();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://matrix.to/#/%23somewhere:example.org");
  });

  it("falls back to the system browser for a user link — no profile surface exists", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const anchor = fakeAnchor("matrix:u/alice:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, undefined, parse);

    expect(selectRoom).not.toHaveBeenCalled();
    expect(open).toHaveBeenCalledExactlyOnceWith("matrix:u/alice:example.org");
  });

  it("falls back to the system browser for a plain https:// link, never calling knownRoomIds at all", async () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const selectRoom = vi.fn();
    const knownRoomIds = vi.fn(() => ["!room:example.org"]);
    const anchor = fakeAnchor("https://example.org/path");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await handleMessageBodyClick(event, open, selectRoom, knownRoomIds, parse);

    expect(selectRoom).not.toHaveBeenCalled();
    expect(knownRoomIds).not.toHaveBeenCalled();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org/path");
  });

  it("without selectRoom/knownRoomIds supplied, still falls back to the browser for a room link (safe defaults)", async () => {
    // The production `onclick={handleMessageBodyClick}` binding (no extra
    // args) must never silently select a room using some default store —
    // see messageLinks.ts's top-of-module doc comment for why the defaults
    // are inert no-ops rather than the real `roomsStore`.
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://matrix.to/#/!room:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    await expect(handleMessageBodyClick(event, open, undefined, undefined, parse)).resolves.toBeUndefined();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://matrix.to/#/!room:example.org");
  });
});
