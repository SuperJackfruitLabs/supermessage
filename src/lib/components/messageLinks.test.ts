// Covers `handleMessageBodyClick`'s decision of when to intercept a click
// inside `{@html}`-rendered message content and route it to the system
// browser instead of letting the webview navigate — see `messageLinks.ts`'s
// doc comment for why that matters in a chrome-less SPA. No DOM: this
// project's vitest runs with `environment: "node"` (see `draftTracker.test.ts`
// for the same constraint), so every "element" here is a plain object that
// only implements the one method (`closest`) the module under test calls.

import { describe, expect, it, vi } from "vitest";
import {
  handleMessageBodyAuxClick,
  handleMessageBodyClick,
  type MessageBodyAuxClickEvent,
  type MessageBodyClickEvent,
} from "./messageLinks";

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
  it("ignores a click that landed on no anchor", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(null));

    handleMessageBodyClick(event, open);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("ignores a click whose target isn't a DOM-like element at all", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeEvent(null);

    handleMessageBodyClick(event, open);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("prevents default and opens the link's href via the system opener, not navigation", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(anchor));

    handleMessageBodyClick(event, open);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org/path");
  });

  it("still intercepts when the click landed on an element nested inside the anchor", () => {
    // `closest` is what makes this work against real markup like
    // `<a href="..."><strong>text</strong></a>` — the fake just asserts the
    // module queries with the selector that finds it.
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    handleMessageBodyClick(event, open);

    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org");
  });

  it("prevents default but does not call open when the anchor has no href value", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor(null);
    const { event, preventDefault } = fakeEvent(elementWithClosestAnchor(anchor));

    handleMessageBodyClick(event, open);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).not.toHaveBeenCalled();
  });

  it("swallows a rejected open() instead of letting it become an unhandled rejection", async () => {
    const open = vi.fn().mockRejectedValue(new Error("scheme not permitted"));
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    const anchor = fakeAnchor("matrix:u/alice:example.org");
    const { event } = fakeEvent(elementWithClosestAnchor(anchor));

    expect(() => handleMessageBodyClick(event, open)).not.toThrow();
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

  it("intercepts a middle-click (button 1) on a link exactly like a primary click", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(anchor), 1);

    handleMessageBodyAuxClick(event, open);

    expect(preventDefault).toHaveBeenCalledOnce();
    expect(open).toHaveBeenCalledExactlyOnceWith("https://example.org/path");
  });

  it("ignores a non-middle auxiliary button (e.g. the right button)", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const anchor = fakeAnchor("https://example.org/path");
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(anchor), 2);

    handleMessageBodyAuxClick(event, open);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });

  it("ignores a middle-click that landed on no anchor", () => {
    const open = vi.fn().mockResolvedValue(undefined);
    const { event, preventDefault } = fakeAuxEvent(elementWithClosestAnchor(null), 1);

    handleMessageBodyAuxClick(event, open);

    expect(preventDefault).not.toHaveBeenCalled();
    expect(open).not.toHaveBeenCalled();
  });
});
