import { describe, expect, it } from "vitest";
import { createAccountStore } from "./account.svelte";
import { createEditTargetStore } from "./editTarget.svelte";
import { createReplyTargetStore } from "./replyTarget.svelte";
import { createSafetyStore } from "./safety.svelte";
import { resetSessionState } from "./sessionReset";

describe("resetSessionState", () => {
  it("resetSessionState clears edit and reply targets", () => {
    const editTargetStore = createEditTargetStore();
    const replyTargetStore = createReplyTargetStore();
    const safetyStore = createSafetyStore(null);
    const accountStore = createAccountStore();
    for (const room of ["!a:x", "!b:x"]) {
      editTargetStore.set(room, { eventId: "$e", body: "hi" });
      replyTargetStore.set(room, { eventId: "$r", sender: "Ann", excerpt: null });
    }
    resetSessionState({ editTargetStore, replyTargetStore, safetyStore, accountStore });
    for (const room of ["!a:x", "!b:x"]) {
      expect(editTargetStore.get(room)).toBeNull();
      expect(replyTargetStore.get(room)).toBeNull();
    }
  });

  it("resetSessionState forgets the account and its safety state", async () => {
    const storage = new Map<string, string>();
    const safetyStore = createSafetyStore({
      getItem: (k) => storage.get(k) ?? null,
      setItem: (k, v) => void storage.set(k, v),
      removeItem: (k) => void storage.delete(k),
    });
    safetyStore.noteBlocked("@a:x");
    safetyStore.hide("$e1");
    const accountStore = createAccountStore({
      account: async () => ({ userId: "@me:x", homeserver: "https://x/" }),
      peopleLabel: async () => "Me",
    });
    await accountStore.load();
    resetSessionState({
      editTargetStore: createEditTargetStore(),
      replyTargetStore: createReplyTargetStore(),
      safetyStore,
      accountStore,
    });
    expect(safetyStore.isBlocked("@a:x")).toBe(false);
    expect(safetyStore.hidden.has("$e1")).toBe(false);
    expect(storage.size).toBe(0);
    expect(accountStore.account).toBeNull();
  });
});
