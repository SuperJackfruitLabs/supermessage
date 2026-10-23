import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { onboarding } from "$lib/onboarding";
import { createLoginGate } from "./loginGate";

function memory() {
  const map = new Map<string, string>();
  return { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v) };
}

function gateOver(store = memory()) {
  const marks: string[] = [];
  const flags = onboarding(store);
  return { store, flags, marks, gate: createLoginGate({ onboarding: flags, markFreshSignIn: () => marks.push("fresh") }) };
}

describe("the login gate", () => {
  it("a first launch welcomes, then the form", () => {
    const { gate } = gateOver();
    expect(gate.view).toBe("welcome");
    gate.passWelcome();
    expect(gate.view).toBe("form");
  });

  it("passing the welcome is remembered", () => {
    const first = gateOver();
    first.gate.passWelcome();
    const later = gateOver(first.store);
    expect(later.gate.view).toBe("form");
  });

  it("passing the welcome queues nothing", () => {
    const { gate, flags, marks } = gateOver();
    gate.passWelcome();
    expect([flags.demoPending, marks]).toEqual([false, []]);
  });

  it("an interactive sign-in queues the demo and marks the sign-in fresh", () => {
    const { gate, flags, marks } = gateOver();
    gate.signedIn();
    expect(flags.demoPending).toBe(true);
    expect(marks).toEqual(["fresh"]);
  });
});

/** Every non-test source file under src, so "who may queue the demo" is checked against the code. */
function sources(dir: string, found: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) sources(path, found);
    else if (/\.(ts|svelte)$/.test(name) && !/\.(test|stories)\./.test(name)) found.push(path);
  }
  return found;
}

describe("restoring a session", () => {
  it("never queues the demo: only the sign-in gate calls queueDemo", () => {
    const callers = sources("src")
      .filter((path) => /queueDemo\(/.test(readFileSync(path, "utf8")))
      .map((path) => path.replaceAll("\\", "/"));
    // The definition, and the one interactive path. The page that restores must not appear.
    expect(callers.sort()).toEqual(["src/lib/onboarding.ts", "src/routes/login/loginGate.ts"]);
  });
});
