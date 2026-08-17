// The spaces store's two jobs: issue exactly one command per selection, and
// recover from the one refusal that leaves the rail and the roster
// disagreeing.
//
// The `unknownSpace` case is the one worth the most care. The core refuses
// *without filtering anything* — deliberately, rather than quietly widening
// the roster to every room while the rail still highlights a space that has
// gone. That makes the recovery the frontend's obligation, and it has two
// halves that are easy to ship as one: re-fetching the list (so the dead
// entry stops being offered) and actually moving the roster back to "All
// rooms" (so what the reader sees matches the highlight). A store that only
// did the first would leave "All rooms" lit above a roster still scoped to
// the space the reader was on before the failed click.

import { describe, expect, it, vi } from "vitest";
import { createSpacesStore } from "./spaces.svelte";
import type { CoreError, SpaceSummary } from "$lib/ipc";

function space(id: string, name = id): SpaceSummary {
  return { id, name, avatarUrl: null, childCount: 2, membership: "joined" };
}

function invitedSpace(id: string, name = id): SpaceSummary {
  return { id, name, avatarUrl: null, childCount: 0, membership: "invited" };
}

function coreError(kind: CoreError["kind"]): CoreError {
  return { kind, message: `${kind} from the core` };
}

function makeStore(
  spacesList: () => Promise<SpaceSummary[]>,
  spaceSelect: (spaceId: string | null) => Promise<void> = vi.fn().mockResolvedValue(undefined),
) {
  const deps = { spacesList: vi.fn(spacesList), spaceSelect: vi.fn(spaceSelect) };
  return { store: createSpacesStore(deps), deps };
}

describe("spacesStore.load", () => {
  it("publishes the core's list and defaults to All rooms", async () => {
    const { store } = makeStore(async () => [space("!a:x.org"), space("!b:x.org")]);

    await store.load();

    expect(store.spaces.map((s) => s.id)).toEqual(["!a:x.org", "!b:x.org"]);
    expect(store.selectedId).toBeNull();
  });

  it("empties the list when the core refuses, rather than leaving a dead rail up", async () => {
    const { store } = makeStore(async () => {
      throw coreError("notReady");
    });
    vi.spyOn(console, "error").mockImplementation(() => {});

    await store.load();

    expect(store.spaces).toEqual([]);
  });

  it("drops a highlight whose space is no longer in the list", async () => {
    let listed = [space("!a:x.org"), space("!gone:x.org")];
    const { store } = makeStore(async () => listed);
    await store.load();
    await store.select("!gone:x.org");
    expect(store.selectedId).toBe("!gone:x.org");

    listed = [space("!a:x.org")];
    await store.load();

    expect(store.selectedId).toBeNull();
  });

  it("leaves a still-present selection alone", async () => {
    const { store } = makeStore(async () => [space("!a:x.org"), space("!b:x.org")]);
    await store.load();
    await store.select("!b:x.org");

    await store.load();

    expect(store.selectedId).toBe("!b:x.org");
  });
});

describe("spacesStore.select", () => {
  it("scopes the roster with one command and nothing else", async () => {
    // "Nothing else" is the contract that matters: the re-filtered roster
    // arrives on the existing rooms diff channel as a Reset at the next
    // sequence number, so a resync or a tracker re-arm here would be the
    // corruption hazard `rooms.svelte.ts` documents. This store is given no
    // way to do either — what this asserts is that it does not go looking
    // for more work, including re-listing the spaces.
    const { store, deps } = makeStore(async () => [space("!a:x.org")]);
    await store.load();
    deps.spacesList.mockClear();

    await store.select("!a:x.org");

    expect(deps.spaceSelect.mock.calls).toEqual([["!a:x.org"]]);
    expect(deps.spacesList).not.toHaveBeenCalled();
    expect(store.selectedId).toBe("!a:x.org");
  });

  it("restores every room with a null selection", async () => {
    const { store, deps } = makeStore(async () => [space("!a:x.org")]);
    await store.load();
    await store.select("!a:x.org");

    await store.select(null);

    expect(deps.spaceSelect).toHaveBeenLastCalledWith(null);
    expect(store.selectedId).toBeNull();
  });

  it("re-lists the spaces and moves the roster to All rooms when the space has gone", async () => {
    // The `unknownSpace` recovery, both halves. The reader is on !a and
    // clicks !gone, which the core has since lost: the highlight must end up
    // on All rooms *and* the roster must actually be widened, because the
    // refused command left it scoped to !a.
    let listed = [space("!a:x.org"), space("!gone:x.org")];
    const spaceSelect = vi.fn(async (spaceId: string | null) => {
      if (spaceId === "!gone:x.org") {
        listed = [space("!a:x.org")];
        throw coreError("unknownSpace");
      }
    });
    const { store, deps } = makeStore(async () => listed, spaceSelect);
    await store.load();
    await store.select("!a:x.org");

    await store.select("!gone:x.org");

    expect(store.selectedId).toBeNull();
    expect(store.spaces.map((s) => s.id)).toEqual(["!a:x.org"]);
    expect(deps.spaceSelect.mock.calls).toEqual([["!a:x.org"], ["!gone:x.org"], [null]]);
  });

  it("does not recurse when even the All-rooms selection reports unknownSpace", async () => {
    // The core cannot produce this — a null selection never looks a space up
    // — but the recovery calls itself, and a recovery that can call itself
    // forever on a malformed error is a hang, in the one code path that only
    // runs when something is already wrong.
    const { store, deps } = makeStore(
      async () => [space("!a:x.org")],
      vi.fn().mockRejectedValue(coreError("unknownSpace")),
    );
    vi.spyOn(console, "error").mockImplementation(() => {});
    await store.load();
    deps.spacesList.mockClear();

    await store.select(null);

    expect(deps.spaceSelect.mock.calls).toEqual([[null]]);
    expect(deps.spacesList).not.toHaveBeenCalled();
    expect(store.selectedId).toBeNull();
  });

  it("puts the highlight back when the command fails for any other reason", async () => {
    // Nothing was filtered, so the rail must not claim a scope the roster
    // does not have.
    let fail = false;
    const { store } = makeStore(
      async () => [space("!a:x.org"), space("!b:x.org")],
      vi.fn(async () => {
        if (fail) throw coreError("notReady");
      }),
    );
    vi.spyOn(console, "error").mockImplementation(() => {});
    await store.load();
    await store.select("!a:x.org");

    fail = true;
    await store.select("!b:x.org");

    expect(store.selectedId).toBe("!a:x.org");
  });
});

describe("spacesStore.clear", () => {
  it("drops the list and the selection on logout", async () => {
    const { store } = makeStore(async () => [space("!a:x.org")]);
    await store.load();
    await store.select("!a:x.org");

    store.clear();

    expect(store.spaces).toEqual([]);
    expect(store.selectedId).toBeNull();
  });
});

describe("an invitation to a space", () => {
  it("is listed separately, so the panel has something to offer", async () => {
    const { store } = makeStore(async () => [
      space("!joined:x.org"),
      invitedSpace("!invited:x.org", "guild"),
    ]);

    await store.load();

    expect(store.pending.map((s) => s.id)).toEqual(["!invited:x.org"]);
  });

  it("is never selected, because there is no subtree to scope the roster to", async () => {
    // The core would answer `unknownSpace` and the store would recover by
    // resetting to All rooms — a correct but pointless round trip that also
    // clears a filter the reader never asked to clear. Refusing locally
    // keeps the click harmless.
    const { store, deps } = makeStore(async () => [
      space("!joined:x.org"),
      invitedSpace("!invited:x.org", "guild"),
    ]);
    await store.load();
    await store.select("!joined:x.org");
    deps.spaceSelect.mockClear();

    await store.select("!invited:x.org");

    expect(deps.spaceSelect).not.toHaveBeenCalled();
    expect(store.selectedId).toBe("!joined:x.org");
  });
});
