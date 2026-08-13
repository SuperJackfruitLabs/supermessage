// Covers `parseMatrixLink`/`resolveInAppRoomId`'s parsing of matrix.to URLs
// and `matrix:` URIs — malformed input, percent-encoding, the `?via=`
// parameters matrix.to appends, event-id fragments, and the "everything
// else keeps going to the browser" contract. See `matrixLinks.ts`'s doc
// comment for the grammar this is verified against.

import { describe, expect, it } from "vitest";
import { parseMatrixLink, resolveInAppRoomId, type MatrixLinkTarget } from "./matrixLinks";

describe("parseMatrixLink: not a matrix link at all", () => {
  it("returns null for a plain https:// URL", () => {
    expect(parseMatrixLink("https://example.org/path")).toBeNull();
  });

  it("returns null for a mailto: link", () => {
    expect(parseMatrixLink("mailto:alice@example.org")).toBeNull();
  });

  it("returns null for a string that isn't a URL at all", () => {
    expect(parseMatrixLink("not a url at all")).toBeNull();
  });

  it("returns null for an https URL that merely looks similar to matrix.to", () => {
    // A different host is not matrix.to, however close the label looks.
    expect(parseMatrixLink("https://matrix.to.evil.example/#/!room:x.org")).toBeNull();
    expect(parseMatrixLink("https://notmatrix.to/#/!room:x.org")).toBeNull();
  });
});

describe("parseMatrixLink: matrix.to URLs", () => {
  it("parses a room id", () => {
    expect(parseMatrixLink("https://matrix.to/#/!room:example.org")).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: null,
    });
  });

  it("parses a percent-encoded room id sigil", () => {
    expect(parseMatrixLink("https://matrix.to/#/%21room:example.org")).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: null,
    });
  });

  it("parses a room alias, percent-encoded (the spec's recommended form)", () => {
    expect(parseMatrixLink("https://matrix.to/#/%23somewhere:example.org")).toEqual({
      kind: "roomAlias",
      alias: "#somewhere:example.org",
      eventId: null,
    });
  });

  it("parses a literal, non-percent-encoded room alias too", () => {
    // A second literal '#' inside the fragment is not itself a second
    // fragment delimiter — `url.hash` already captures everything after the
    // *first* '#' verbatim, including this one.
    expect(parseMatrixLink("https://matrix.to/#/#somewhere:example.org")).toEqual({
      kind: "roomAlias",
      alias: "#somewhere:example.org",
      eventId: null,
    });
  });

  it("parses a user id", () => {
    expect(parseMatrixLink("https://matrix.to/#/@alice:example.org")).toEqual({
      kind: "user",
      userId: "@alice:example.org",
    });
  });

  it("parses an event id following a room id", () => {
    expect(
      parseMatrixLink("https://matrix.to/#/!room:example.org/$event:example.org"),
    ).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: "$event:example.org",
    });
  });

  it("parses a percent-encoded event id", () => {
    expect(
      parseMatrixLink("https://matrix.to/#/!room:example.org/%24event%3Aexample.org"),
    ).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: "$event:example.org",
    });
  });

  it("ignores a single ?via= parameter appended to a room id", () => {
    expect(
      parseMatrixLink("https://matrix.to/#/!room:example.org?via=elsewhere.ca"),
    ).toEqual({ kind: "room", roomId: "!room:example.org", eventId: null });
  });

  it("ignores repeated ?via= parameters", () => {
    expect(
      parseMatrixLink(
        "https://matrix.to/#/!room:example.org?via=one.example&via=two.example",
      ),
    ).toEqual({ kind: "room", roomId: "!room:example.org", eventId: null });
  });

  it("ignores ?via= appended after an event id too", () => {
    expect(
      parseMatrixLink(
        "https://matrix.to/#/!room:example.org/$event:example.org?via=elsewhere.ca",
      ),
    ).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: "$event:example.org",
    });
  });

  it("treats an empty fragment as unknown rather than throwing", () => {
    expect(parseMatrixLink("https://matrix.to/")).toEqual({ kind: "unknown" });
    expect(parseMatrixLink("https://matrix.to/#/")).toEqual({ kind: "unknown" });
  });

  it("treats a sigil-less identifier as unknown", () => {
    expect(parseMatrixLink("https://matrix.to/#/room-with-no-sigil:example.org")).toEqual({
      kind: "unknown",
    });
  });

  it("treats a bare sigil with no id after it as unknown", () => {
    expect(parseMatrixLink("https://matrix.to/#/!")).toEqual({ kind: "unknown" });
  });

  it("treats malformed percent-encoding as unknown instead of throwing", () => {
    expect(parseMatrixLink("https://matrix.to/#/%")).toEqual({ kind: "unknown" });
  });

  it("is case-insensitive on the matrix.to host", () => {
    expect(parseMatrixLink("https://MATRIX.TO/#/!room:example.org")).toEqual({
      kind: "room",
      roomId: "!room:example.org",
      eventId: null,
    });
  });
});

describe("parseMatrixLink: matrix: URIs", () => {
  it("parses a user", () => {
    expect(parseMatrixLink("matrix:u/alice:example.org")).toEqual({
      kind: "user",
      userId: "@alice:example.org",
    });
  });

  it("parses a room alias", () => {
    expect(parseMatrixLink("matrix:r/somewhere:example.org")).toEqual({
      kind: "roomAlias",
      alias: "#somewhere:example.org",
      eventId: null,
    });
  });

  it("parses a room id", () => {
    expect(parseMatrixLink("matrix:roomid/abc123:example.org")).toEqual({
      kind: "room",
      roomId: "!abc123:example.org",
      eventId: null,
    });
  });

  it("parses an event id as a path segment after a room id, per the spec's own example", () => {
    // matrix:roomid/somewhere:example.org/e/event?via=elsewhere.ca —
    // spec.matrix.org/latest/appendices/#uri-examples
    expect(
      parseMatrixLink("matrix:roomid/somewhere:example.org/e/event?via=elsewhere.ca"),
    ).toEqual({
      kind: "room",
      roomId: "!somewhere:example.org",
      eventId: "$event",
    });
  });

  it("ignores a ?via= query string that has nothing to do with the path", () => {
    expect(parseMatrixLink("matrix:roomid/abc:example.org?via=one.example&via=two.example")).toEqual(
      { kind: "room", roomId: "!abc:example.org", eventId: null },
    );
  });

  it("decodes a percent-encoded id", () => {
    expect(parseMatrixLink("matrix:u/alice%3Aexample.org")).toEqual({
      kind: "user",
      userId: "@alice:example.org",
    });
  });

  it("treats an unknown type qualifier as unknown", () => {
    expect(parseMatrixLink("matrix:group/somewhere:example.org")).toEqual({ kind: "unknown" });
  });

  it("treats a path with no id segment as unknown", () => {
    expect(parseMatrixLink("matrix:u")).toEqual({ kind: "unknown" });
  });

  it("treats a path with an empty id segment as unknown", () => {
    expect(parseMatrixLink("matrix:u/")).toEqual({ kind: "unknown" });
  });

  it("treats malformed percent-encoding as unknown instead of throwing", () => {
    expect(parseMatrixLink("matrix:u/%")).toEqual({ kind: "unknown" });
  });

  it("does not treat a bare event marker with no id after it as an event reference", () => {
    expect(parseMatrixLink("matrix:roomid/abc:example.org/e")).toEqual({
      kind: "room",
      roomId: "!abc:example.org",
      eventId: null,
    });
  });
});

describe("resolveInAppRoomId", () => {
  const knownRooms = ["!known:example.org", "!other:example.org"];

  it("resolves a room-id target that is in the known list", () => {
    const target: MatrixLinkTarget = { kind: "room", roomId: "!known:example.org", eventId: null };
    expect(resolveInAppRoomId(target, knownRooms)).toBe("!known:example.org");
  });

  it("returns null for a room-id target that is not in the known list — can't join a room we're not in", () => {
    const target: MatrixLinkTarget = {
      kind: "room",
      roomId: "!unknown:example.org",
      eventId: null,
    };
    expect(resolveInAppRoomId(target, knownRooms)).toBeNull();
  });

  it("returns null for a room-id target with an event id we're in, still selecting nothing extra", () => {
    // Selecting the room is as far as this goes — there is no "scroll to
    // event" capability, so the event id itself changes nothing about the
    // resolution here (Timeline.svelte's own room-selection effect is what
    // actually opens the room).
    const target: MatrixLinkTarget = {
      kind: "room",
      roomId: "!known:example.org",
      eventId: "$event:example.org",
    };
    expect(resolveInAppRoomId(target, knownRooms)).toBe("!known:example.org");
  });

  it("returns null for a room alias — no alias -> id resolution is wired up", () => {
    const target: MatrixLinkTarget = {
      kind: "roomAlias",
      alias: "#known:example.org",
      eventId: null,
    };
    expect(resolveInAppRoomId(target, knownRooms)).toBeNull();
  });

  it("returns null for a user — no profile surface exists", () => {
    const target: MatrixLinkTarget = { kind: "user", userId: "@alice:example.org" };
    expect(resolveInAppRoomId(target, knownRooms)).toBeNull();
  });

  it("returns null for an unknown target", () => {
    expect(resolveInAppRoomId({ kind: "unknown" }, knownRooms)).toBeNull();
  });

  it("returns null for a null target (a non-matrix link), without a separate null check at call sites", () => {
    expect(resolveInAppRoomId(null, knownRooms)).toBeNull();
  });

  it("returns null against an empty known-room list", () => {
    const target: MatrixLinkTarget = { kind: "room", roomId: "!known:example.org", eventId: null };
    expect(resolveInAppRoomId(target, [])).toBeNull();
  });
});
