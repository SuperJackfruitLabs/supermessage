// What the signed-in page does once, when it starts: notification permission, then
// (for the caller) recovery. `consumeFreshSignIn` clears the mark as it reads it, so
// it is read exactly once here and handed to both; a second read would find it gone
// and one of the two would be skipped.
import { consumeFreshSignIn } from "./freshSignIn";
import type { NotificationPermission } from "$lib/ipc";

export interface SignInSetupDeps {
  notificationPermission: (prompt: boolean) => Promise<NotificationPermission>;
}

/**
 * Asks about notification permission and returns whether this start is a fresh
 * sign-in. The OS is asked (`prompt`) only after a fresh sign-in, never at launch
 * or on a restore, as iOS does (`PlatformCoordinator.swift:124-131`). A failure is
 * silent: the reader is not interrupted for a notification.
 */
export function setUpAfterSignIn(deps: SignInSetupDeps): boolean {
  const fresh = consumeFreshSignIn();
  void deps.notificationPermission(fresh).catch(() => {});
  return fresh;
}
