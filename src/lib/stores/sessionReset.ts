// What leaving an account must forget so the next one starts clean: the edit and
// reply targets (keyed by room id), the blocked list and hidden message ids, and who
// was signed in. Called on every way back to /login, not only the sign-out button.
import { accountStore } from "./account.svelte";
import { acknowledgementStore } from "./acknowledgement.svelte";
import { notificationClearAll } from "$lib/ipc";
import { attention } from "./attention.svelte";
import { castStore } from "./cast.svelte";
import { destinations } from "./destinations.svelte";
import { editTargetStore } from "./editTarget.svelte";
import { liveStore } from "./live.svelte";
import { notifier } from "$lib/notifier/notifier.svelte";
import { turnEnded } from "$lib/notifier/turnEnded";
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
  /** Withdraws every delivered notification. */
  notificationClearAll: () => Promise<void>;
  /** Forgets the roster baseline, what was shown, and the cached modes. */
  notifier: { reset(): void };
  /** Forgets every room's turn state, so nothing from this account is announced for the next. */
  turnEnded: { reset(): void };
  /** Puts the window title back to the plain app name. */
  attention: { reset(): void };
}

export function resetSessionState(
  stores: SessionStores = { editTargetStore, replyTargetStore, safetyStore, accountStore, liveStore, castStore, acknowledgementStore, destinations, notificationClearAll, notifier, turnEnded, attention },
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
  // The last account's notifications must not outlive it (iOS removes every delivered one).
  // Fire and forget: a failure to withdraw must not hold up the sign-out.
  // The next account's first roster is a baseline again, and nothing of this one is remembered.
  stores.notifier.reset();
  stores.turnEnded.reset();
  // The last account's Needs you count must not stay in the window title.
  stores.attention.reset();
  void stores.notificationClearAll().catch(() => {});
}
