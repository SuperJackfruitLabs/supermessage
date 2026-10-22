import { describe, expect, it } from "vitest";
import {
  ACCOUNT_LINKS,
  BLOCKED_FOOTER,
  REPORT_FOOTER,
  blockedSectionShown,
  signOutTitle,
  versionLine,
} from "./accountView";
import type { RoomMember } from "$lib/ipc";

describe("signOutTitle", () => {
  it("names the account when it is known", () => {
    expect(signOutTitle("rakesh")).toBe("Sign out of rakesh?");
  });
  it("names a known account", () => {
    expect(signOutTitle("Rakesh")).toBe("Sign out of Rakesh?");
  });
  it("is plain until the account name is available", () => {
    expect(signOutTitle(null)).toBe("Sign out?");
  });
});

describe("ACCOUNT_LINKS", () => {
  it("is iOS's three links, in order", () => {
    expect(ACCOUNT_LINKS).toEqual([
      { label: "Help & support", url: "https://supermessage.dev/support/" },
      { label: "Terms of use", url: "https://supermessage.dev/terms/" },
      { label: "Privacy", url: "https://supermessage.dev/privacy/" },
    ]);
  });
});

describe("versionLine", () => {
  it("prefixes the version", () => {
    expect(versionLine("0.0.15")).toBe("Version 0.0.15");
  });
  it("is absent when the version is unknown", () => {
    expect(versionLine(null)).toBeNull();
  });
});

const someone: RoomMember = { userId: "@a:x", displayName: "A", avatarUrl: null, isAgent: false, isIgnored: true };

describe("footers", () => {
  it("REPORT_FOOTER is the desktop wording", () => {
    expect(REPORT_FOOTER).toBe(
      "To report abuse, open a message's More menu or a room's info. Reports go to your homeserver's administrators.",
    );
  });
  it("BLOCKED_FOOTER is the iOS sentence", () => {
    expect(BLOCKED_FOOTER).toBe(
      "Their messages are hidden in every room, on all your devices. A blocked agent keeps running. Block someone from a room's member list.",
    );
  });
});

describe("blockedSectionShown", () => {
  it("is hidden while unknown or empty", () => {
    expect(blockedSectionShown(null)).toBe(false);
    expect(blockedSectionShown([])).toBe(false);
  });
  it("is shown for one member", () => {
    expect(blockedSectionShown([someone])).toBe(true);
  });
});
