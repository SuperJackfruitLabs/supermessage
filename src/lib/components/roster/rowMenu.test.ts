import { describe, expect, it } from "vitest";
import { menuPosition, rowMenuItems } from "./rowMenu";

describe("rowMenuItems", () => {
  it("an invitation offers one destructive Decline", () => {
    expect(rowMenuItems("respondToInvitation", null)).toEqual([{ id: "decline", label: "Decline", destructive: true }]);
  });
  it("a muted, pinned room offers Unmute and Unpin", () => {
    const items = rowMenuItems("compose", { notifications: "muted", pinned: true });
    expect(items.map((i) => i.label)).toEqual(["Mark read", "Unmute", "Unpin"]);
    expect(items.map((i) => i.id)).toEqual(["markRead", "mute", "pin"]);
    expect(items.some((i) => i.destructive)).toBe(false);
  });
  it("a room the core could not describe reads Mute and Pin", () => {
    expect(rowMenuItems("compose", null).map((i) => i.label)).toEqual(["Mark read", "Mute", "Pin"]);
  });
  it("a plain room reads Mute and Pin", () => {
    expect(rowMenuItems("compose", { notifications: "default", pinned: false }).map((i) => i.label)).toEqual(["Mark read", "Mute", "Pin"]);
  });
  it("nothing offers nothing", () => {
    expect(rowMenuItems("nothing", null)).toEqual([]);
  });
});

describe("menuPosition", () => {
  const size = { width: 160, height: 100 };
  const viewport = { width: 1000, height: 700 };
  it("keeps the menu inside the window", () => {
    expect(menuPosition({ x: 990, y: 690 }, size, viewport)).toEqual({ left: 830, top: 590 });
  });
  it("opens at the point when it fits", () => {
    expect(menuPosition({ x: 0, y: 0 }, size, viewport)).toEqual({ left: 0, top: 0 });
    expect(menuPosition({ x: 100, y: 50 }, size, viewport)).toEqual({ left: 100, top: 50 });
  });
  it("never goes negative", () => {
    expect(menuPosition({ x: 50, y: 50 }, size, { width: 100, height: 60 })).toEqual({ left: 0, top: 0 });
  });
});
