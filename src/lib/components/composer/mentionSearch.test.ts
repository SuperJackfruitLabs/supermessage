import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PersonDto } from "$lib/ipc";
import { createMentionSearch, type MentionSearchDeps } from "./mentionSearch";

const person = (id: string, name: string, agent = false): PersonDto => ({
  userId: id,
  name,
  initial: name[0]!,
  runtime: agent ? { harness: "Claude Code", host: "Ashram" } : null,
  avatarUrl: null,
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function make(fn: (roomId: string, query: string) => Promise<PersonDto[]>) {
  const roomMentionCandidates = vi.fn(fn);
  const deps = { roomMentionCandidates, delayMs: 80 } as unknown as MentionSearchDeps;
  return { search: createMentionSearch(deps), roomMentionCandidates };
}

describe("the @ menu's search", () => {
  it("a fast typist makes one call, for the last query", async () => {
    const { search, roomMentionCandidates } = make(async () => [person("@a:x", "Atlas", true)]);
    search.search("!r:x", "");
    search.search("!r:x", "a");
    search.search("!r:x", "at");
    expect(roomMentionCandidates).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(80);
    expect(roomMentionCandidates).toHaveBeenCalledTimes(1);
    expect(roomMentionCandidates).toHaveBeenCalledWith("!r:x", "at");
  });

  it("people become candidates with the agent marked and its runtime kept", async () => {
    const s = make(async () => [person("@a:x", "Atlas", true), person("@k:x", "Krishna")]);
    s.search.search("!r:x", "");
    await vi.advanceTimersByTimeAsync(80);
    const [atlas, krishna] = s.search.results;
    expect(atlas).toMatchObject({ userId: "@a:x", displayName: "Atlas", isAgent: true, initial: "A" });
    expect(atlas!.runtime).toEqual({ harness: "Claude Code", host: "Ashram" });
    expect(krishna).toMatchObject({ isAgent: false, runtime: null });
  });

  it("says which query its results answer, so a pick never uses an earlier one", async () => {
    const s = make(async (_r, q) => (q === "" ? [person("@a:x", "Atlas")] : [person("@q:x", "Quill")]));
    s.search.search("!r:x", "");
    await vi.advanceTimersByTimeAsync(80);
    expect(s.search.resultQuery).toBe("");
    s.search.search("!r:x", "q");
    expect(s.search.resultQuery).toBe("");
    await vi.advanceTimersByTimeAsync(80);
    expect(s.search.resultQuery).toBe("q");
    s.search.close();
    expect(s.search.resultQuery).toBeNull();
  });

  it("a slow answer to an old query is dropped", async () => {
    const slow = deferred<PersonDto[]>();
    const s = make(async (_r, q) => (q === "a" ? slow.promise : [person("@q:x", "Quill")]));
    s.search.search("!r:x", "a");
    await vi.advanceTimersByTimeAsync(80);
    s.search.search("!r:x", "q");
    await vi.advanceTimersByTimeAsync(80);
    expect(s.search.results.map((c) => c.displayName)).toEqual(["Quill"]);
    slow.resolve([person("@a:x", "Atlas")]);
    await vi.advanceTimersByTimeAsync(0);
    expect(s.search.results.map((c) => c.displayName)).toEqual(["Quill"]);
  });

  it("closing drops an answer on its way and empties the list", async () => {
    const slow = deferred<PersonDto[]>();
    const s = make(() => slow.promise);
    s.search.search("!r:x", "a");
    await vi.advanceTimersByTimeAsync(80);
    s.search.close();
    slow.resolve([person("@a:x", "Atlas")]);
    await vi.advanceTimersByTimeAsync(0);
    expect(s.search.results).toEqual([]);
  });

  it("closing before the pause ends cancels the call", async () => {
    const s = make(async () => []);
    s.search.search("!r:x", "a");
    s.search.close();
    await vi.advanceTimersByTimeAsync(200);
    expect(s.roomMentionCandidates).not.toHaveBeenCalled();
  });

  it("a refusal is an empty list", async () => {
    const s = make(async () => {
      throw { kind: "notReady" };
    });
    s.search.search("!r:x", "a");
    await vi.advanceTimersByTimeAsync(80);
    expect(s.search.results).toEqual([]);
  });
});
