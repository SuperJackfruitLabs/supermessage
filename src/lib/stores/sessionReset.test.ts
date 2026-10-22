import { describe, expect, it } from "vitest";
import { createEditTargetStore } from "./editTarget.svelte";
import { createReplyTargetStore } from "./replyTarget.svelte";
import { resetComposeState } from "./sessionReset";

describe("resetComposeState", () => {
  it("resetComposeState clears edit and reply targets", () => {
    const editTargetStore = createEditTargetStore();
    const replyTargetStore = createReplyTargetStore();
    for (const room of ["!a:x", "!b:x"]) {
      editTargetStore.set(room, { eventId: "$e", body: "hi" });
      replyTargetStore.set(room, { eventId: "$r", sender: "Ann", excerpt: null });
    }
    resetComposeState({ editTargetStore, replyTargetStore });
    for (const room of ["!a:x", "!b:x"]) {
      expect(editTargetStore.get(room)).toBeNull();
      expect(replyTargetStore.get(room)).toBeNull();
    }
  });
});
