import { describe, expect, it, vi } from "vitest";
import { createAccountStore } from "./account.svelte";

const acct = { userId: "@rakesh:id.agentpod.dev", homeserver: "https://id.agentpod.dev/" };

describe("account store", () => {
  it("load names the account through people_label", async () => {
    const peopleLabel = vi.fn(async () => "Rakesh");
    const store = createAccountStore({ account: async () => acct, peopleLabel });
    await store.load();
    expect(peopleLabel).toHaveBeenCalledWith(["@rakesh:id.agentpod.dev"]);
    expect(store.account).toEqual(acct);
    expect(store.name).toBe("Rakesh");
  });

  it("a failed load leaves nothing", async () => {
    const store = createAccountStore({
      account: async () => { throw { kind: "notReady", message: "" }; },
      peopleLabel: async () => "x",
    });
    await store.load();
    expect(store.account).toBeNull();
    expect(store.name).toBeNull();
  });

  it("clear forgets both", async () => {
    const store = createAccountStore({ account: async () => acct, peopleLabel: async () => "Rakesh" });
    await store.load();
    store.clear();
    expect(store.account).toBeNull();
    expect(store.name).toBeNull();
  });

  it("a load in flight when clear runs does not repopulate the store", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const store = createAccountStore({
      account: async () => {
        await gate;
        return acct;
      },
      peopleLabel: async () => "Rakesh",
    });
    const loading = store.load();
    store.clear();
    release();
    await loading;
    expect(store.account).toBeNull();
    expect(store.name).toBeNull();
  });
});
