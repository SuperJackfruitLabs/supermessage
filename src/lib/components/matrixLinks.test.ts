// The grammar moved to `core::matrix_links`, with its 32 cases. What is
// left is the one question that is this app's rather than the protocol's:
// which of the things a link can address this build can act on.
// Covers `parseMatrixLink`/`resolveInAppRoomId`'s parsing of matrix.to URLs
// and `matrix:` URIs — malformed input, percent-encoding, the `?via=`
// parameters matrix.to appends, event-id fragments, and the "everything
// else keeps going to the browser" contract. See `matrixLinks.ts`'s doc
// comment for the grammar this is verified against.

import { describe, expect, it } from "vitest";
import { resolveInAppRoomId } from "./matrixLinks";
import type { MatrixLinkTarget } from "$lib/ipc";




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
