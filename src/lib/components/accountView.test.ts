import { describe, expect, it } from "vitest";
import { ACCOUNT_LINKS, signOutTitle, versionLine } from "./accountView";

describe("signOutTitle", () => {
  it("names the account when it is known", () => {
    expect(signOutTitle("rakesh")).toBe("Sign out of rakesh?");
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
