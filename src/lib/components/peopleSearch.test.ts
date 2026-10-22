import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PersonDto } from "$lib/ipc";
import { createPeopleSearch, type PeopleState } from "./peopleSearch";

const person = (name: string): PersonDto => ({
  userId: `@${name}:x`,
  name,
  initial: name[0].toUpperCase(),
  runtime: null,
  avatarUrl: null,
});

describe("createPeopleSearch", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("asks once for a burst of keystrokes, with the last query", async () => {
    const load = vi.fn(async () => [person("atlas")]);
    const states: PeopleState[] = [];
    const s = createPeopleSearch(load, (st) => states.push(st), 200);
    s.search("a");
    await vi.advanceTimersByTimeAsync(100);
    s.search("at");
    await vi.advanceTimersByTimeAsync(100);
    expect(load).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(100);
    expect(load).toHaveBeenCalledTimes(1);
    expect(load).toHaveBeenCalledWith("at");
    expect(states.at(-1)).toEqual({ kind: "loaded", people: [person("atlas")], query: "at" });
  });

  it("loads the empty query as null, at once", async () => {
    const load = vi.fn(async () => []);
    createPeopleSearch(load, () => {}, 200).search("  ", true);
    await vi.advanceTimersByTimeAsync(0);
    expect(load).toHaveBeenCalledWith(null);
  });

  it("drops a late answer to an older query", async () => {
    const answers = new Map<string, (p: PersonDto[]) => void>();
    const load = vi.fn((q: string | null) => new Promise<PersonDto[]>((r) => answers.set(q ?? "", r)));
    const states: PeopleState[] = [];
    const s = createPeopleSearch(load, (st) => states.push(st), 200);
    s.search("a");
    await vi.advanceTimersByTimeAsync(200);
    s.search("ab");
    await vi.advanceTimersByTimeAsync(200);
    answers.get("ab")!([person("ab")]);
    await vi.advanceTimersByTimeAsync(0);
    answers.get("a")!([person("stale")]);
    await vi.advanceTimersByTimeAsync(0);
    expect(states.at(-1)).toEqual({ kind: "loaded", people: [person("ab")], query: "ab" });
  });

  it("says nothing after dispose", async () => {
    const load = vi.fn(async () => [person("atlas")]);
    const states: PeopleState[] = [];
    const s = createPeopleSearch(load, (st) => states.push(st), 200);
    s.search("a", true);
    s.dispose();
    await vi.advanceTimersByTimeAsync(300);
    expect(states).toEqual([{ kind: "loading" }]);
  });

  it("reports a failure of the latest query", async () => {
    const s = createPeopleSearch(
      async () => {
        throw { kind: "network", message: "offline" };
      },
      (st) => states.push(st),
    );
    const states: PeopleState[] = [];
    s.search("", true);
    await vi.advanceTimersByTimeAsync(0);
    expect(states.at(-1)?.kind).toBe("failed");
  });

  it("shows loading for the first load only, never again while refining", async () => {
    const load = vi.fn(async () => [person("atlas")]);
    const states: PeopleState[] = [];
    const s = createPeopleSearch(load, (st) => states.push(st), 200);
    s.search("", true);
    await vi.advanceTimersByTimeAsync(0);
    expect(states.map((x) => x.kind)).toEqual(["loading", "loaded"]);
    s.search("a");
    s.search("at");
    s.search("atl");
    expect(states.map((x) => x.kind)).toEqual(["loading", "loaded"]);
    await vi.advanceTimersByTimeAsync(200);
    expect(states.map((x) => x.kind)).toEqual(["loading", "loaded", "loaded"]);
    expect(states.at(-1)).toMatchObject({ query: "atl" });
  });

  it("shows loading again if the first load never answered", async () => {
    const states: PeopleState[] = [];
    const s = createPeopleSearch(() => new Promise(() => {}), (st) => states.push(st), 200);
    s.search("", true);
    s.search("a");
    expect(states.map((x) => x.kind)).toEqual(["loading", "loading"]);
  });
});
