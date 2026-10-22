// What leaving an account must forget so the next one starts clean: the edit and
// reply targets (keyed by room id), the blocked list and hidden message ids, and who
// was signed in. Called on every way back to /login, not only the sign-out button.
import { accountStore } from "./account.svelte";
import { editTargetStore } from "./editTarget.svelte";
import { replyTargetStore } from "./replyTarget.svelte";
import { safetyStore } from "./safety.svelte";

export interface SessionStores {
  editTargetStore: { clearAll(): void };
  replyTargetStore: { clearAll(): void };
  safetyStore: { clear(): void };
  accountStore: { clear(): void };
}

export function resetSessionState(
  stores: SessionStores = { editTargetStore, replyTargetStore, safetyStore, accountStore },
): void {
  stores.editTargetStore.clearAll();
  stores.replyTargetStore.clearAll();
  // Blocked and hidden belong to the account that is leaving (iOS clears them at sign-out too).
  stores.safetyStore.clear();
  stores.accountStore.clear();
}
