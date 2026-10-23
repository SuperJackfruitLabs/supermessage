// What leaving an account must forget so the next one starts clean: the edit and
// reply targets (keyed by room id), the blocked list and hidden message ids, and who
// was signed in. Called on every way back to /login, not only the sign-out button.
import { accountStore } from "./account.svelte";
import { acknowledgementStore } from "./acknowledgement.svelte";
import { castStore } from "./cast.svelte";
import { destinations } from "./destinations.svelte";
import { editTargetStore } from "./editTarget.svelte";
import { liveStore } from "./live.svelte";
import { replyTargetStore } from "./replyTarget.svelte";
import { safetyStore } from "./safety.svelte";

export interface SessionStores {
  editTargetStore: { clearAll(): void };
  replyTargetStore: { clearAll(): void };
  safetyStore: { clear(): void };
  accountStore: { clear(): void };
  liveStore: { clear(): void };
  castStore: { clear(): void };
  acknowledgementStore: { clear(): void };
  destinations: { reset(): void };
}

export function resetSessionState(
  stores: SessionStores = { editTargetStore, replyTargetStore, safetyStore, accountStore, liveStore, castStore, acknowledgementStore, destinations },
): void {
  stores.editTargetStore.clearAll();
  stores.replyTargetStore.clearAll();
  // Blocked and hidden belong to the account that is leaving (iOS clears them at sign-out too).
  stores.safetyStore.clear();
  stores.accountStore.clear();
  // The last account's turn and cast must not show in the next one.
  stores.liveStore.clear();
  stores.castStore.clear();
  // The last account's "Sent to" must not greet the next one.
  stores.acknowledgementStore.clear();
  // The last account's inbox, badge and agents must not greet the next one; back on Chats.
  stores.destinations.reset();
}
