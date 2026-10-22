// What a sign-out must forget so the next account starts clean: the edit and
// reply targets are keyed by room id and would otherwise survive in memory.
import { editTargetStore } from "./editTarget.svelte";
import { replyTargetStore } from "./replyTarget.svelte";

export function resetComposeState(
  stores: {
    editTargetStore: { clearAll(): void };
    replyTargetStore: { clearAll(): void };
  } = { editTargetStore, replyTargetStore },
): void {
  stores.editTargetStore.clearAll();
  stores.replyTargetStore.clearAll();
}
