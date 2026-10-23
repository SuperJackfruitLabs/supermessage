// RF5: the first-run demo reaches the core's first-run commands and nothing else. It is a sample
// conversation; a command that sends, answers a decision or marks anything read would put a demo
// into a real room or a real account.
//
// This project's vitest runs in plain node, with no DOM to mount a component in, so the guard is in
// two halves: the demo's logic is driven end to end through the real IPC wrappers over a recording
// `invoke`, and the component's source is checked for what it may import.

import { readFileSync } from "node:fs";
import { beforeEach, describe, expect, it, vi } from "vitest";

const invoke = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

import { agentAvatarStyle, firstRunAdvance, firstRunAnswer, firstRunScript, type FirstRunStep } from "$lib/ipc";
import { firstRunScriptFixture } from "$lib/fixtures";
import { createFirstRunDemo } from "./firstRun";

const ALLOWED = new Set(["first_run_script", "first_run_advance", "first_run_answer", "agent_avatar_style"]);

/** The core's answers, as `core::first_run` gives them. */
function coreAnswers(command: string, args?: { step?: FirstRunStep }): unknown {
  switch (command) {
    case "first_run_script":
      return firstRunScriptFixture;
    case "first_run_advance":
      return args!.step === "greeting" ? "asking" : args!.step === "asking" ? "waiting" : args!.step;
    case "first_run_answer":
      return args!.step === "waiting" ? "approved" : null;
    case "agent_avatar_style":
      return null;
    default:
      return undefined;
  }
}

beforeEach(() => {
  invoke.mockReset();
  invoke.mockImplementation(async (command: string, args?: { step?: FirstRunStep }) => coreAnswers(command, args));
});

describe("the first-run demo invokes nothing but the first-run commands", () => {
  it("from the greeting, to the card, to Approve", async () => {
    const script = await firstRunScript();
    await agentAvatarStyle(script.roomId);
    const demo = createFirstRunDemo({ script, advance: firstRunAdvance, answer: firstRunAnswer, sleep: async () => {} });
    await demo.run();
    expect(demo.step).toBe("waiting");
    expect(await demo.answer("approve")).toBe(true);
    expect(demo.step).toBe("approved");

    const commands = invoke.mock.calls.map(([name]) => name as string);
    expect(commands.length).toBeGreaterThan(0);
    for (const name of commands) expect(ALLOWED.has(name), `${name} is not a first-run command`).toBe(true);
    for (const name of commands) {
      expect(name).not.toMatch(/^send_/);
      expect(name).not.toMatch(/decision/);
      expect(name).not.toBe("send_permission_answer");
      expect(name).not.toMatch(/^mark_/);
    }
  });
});

describe("FirstRunDemo.svelte", () => {
  const source = readFileSync("src/lib/components/FirstRunDemo.svelte", "utf8");

  it("imports only first-run commands and the avatar tile from the IPC layer", () => {
    const block = /import \{([^}]*)\} from "\$lib\/ipc";/s.exec(source);
    expect(block, "the component imports from $lib/ipc").not.toBeNull();
    const names = block![1]!
      .split(",")
      .map((n) => n.trim().replace(/^type\s+/, ""))
      .filter(Boolean);
    const commands = names.filter((n) => /^[a-z]/.test(n));
    expect(commands.sort()).toEqual(["agentAvatarStyle", "firstRunAdvance", "firstRunAnswer", "firstRunScript"]);
  });

  it("takes no session, room or decision sender: no store, no decision-reply module", () => {
    expect(source).not.toMatch(/\$lib\/stores\//);
    expect(source).not.toMatch(/decisionReply/);
    expect(source).not.toMatch(/answeredDecisions/);
    expect(source).not.toMatch(/roomsStore|timelineStore/);
  });

  it("answers the card with the local step machine, with no answeredHere or onAnswered", () => {
    expect(source).toMatch(/demo\.answer\(optionId\)/);
    expect(source).not.toMatch(/answeredHere|onAnswered/);
  });
});
