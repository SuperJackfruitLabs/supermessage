import { describe, expect, it } from "vitest";

import type { AgentState, AvatarRole } from "$lib/ipc";
import { avatarLabel, avatarRoleClass, dotClass, drawnState, unreadLabel } from "./avatarView";

const ROLES: AvatarRole[] = [
  "accent",
  "accentSoft",
  "accentContent",
  "ok",
  "contentMuted",
  "surface",
  "surfaceRaised",
];

describe("avatarRoleClass", () => {
  it("maps every role to a token class, as a ground and as ink", () => {
    for (const role of ROLES) {
      expect(avatarRoleClass(role, "bg")).toMatch(/^bg-[a-z-]+$/);
      expect(avatarRoleClass(role, "text")).toMatch(/^text-[a-z-]+$/);
    }
    expect(avatarRoleClass("accentSoft", "bg")).toBe("bg-accent-soft");
    expect(avatarRoleClass("contentMuted", "text")).toBe("text-content-muted");
  });

  it("never paints a tile amber or red", () => {
    for (const role of ROLES) {
      for (const use of ["bg", "text"] as const) {
        expect(avatarRoleClass(role, use)).not.toMatch(/signal|danger/);
      }
    }
  });
});

describe("dotClass", () => {
  it("colours the three states that have a dot", () => {
    expect(dotClass("needsYou")).toBe("bg-signal");
    expect(dotClass("active")).toBe("bg-ok");
    expect(dotClass("idle")).toBe("bg-content-faint");
  });

  it("quiet draws no dot", () => {
    expect(dotClass("quiet")).toBeNull();
    expect(dotClass(null)).toBeNull();
  });
});

describe("avatarLabel", () => {
  it("the label says Agent and the state", () => {
    expect(avatarLabel(true, "active")).toBe("Agent, active");
    expect(avatarLabel(true, "needsYou")).toBe("Agent, needs you");
  });

  it("says only what is drawn", () => {
    expect(avatarLabel(true, "quiet")).toBe("Agent");
    expect(avatarLabel(true, null)).toBe("Agent");
    expect(avatarLabel(false, "needsYou")).toBe("needs you");
    expect(avatarLabel(false, null)).toBe("");
  });
});

describe("drawnState", () => {
  const draws = (s: AgentState, agent: boolean, shows: boolean) => drawnState(s, agent, shows);

  it("an agent's room draws its state", () => {
    expect(draws("idle", true, true)).toBe("idle");
  });

  it("a room of people draws only a debt", () => {
    expect(draws("idle", false, true)).toBeNull();
    expect(draws("needsYou", false, true)).toBe("needsYou");
  });

  it("hides every state when the reader turned it off", () => {
    expect(draws("needsYou", true, false)).toBeNull();
    expect(draws("active", true, false)).toBeNull();
  });
});

describe("unreadLabel", () => {
  it("caps at 99+", () => {
    expect(unreadLabel(7)).toBe("7");
    expect(unreadLabel(99)).toBe("99");
    expect(unreadLabel(100)).toBe("99+");
  });
});
