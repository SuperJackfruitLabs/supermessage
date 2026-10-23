// What the first run remembers (iOS `WelcomeView.swift:4-17`, `@AppStorage`): per install, not per
// account, under iOS's own key names. They are conveniences: a cleared store only shows the welcome
// again, so every read and write tolerates storage that is missing or refuses.

export const WELCOME_SEEN_KEY = "onboarding.welcomeSeen";
export const DEMO_PENDING_KEY = "onboarding.demoPending";
export const DEMO_SEEN_KEY = "onboarding.demoSeen";

export type OnboardingStorage = Pick<Storage, "getItem" | "setItem">;

export function onboarding(storage: OnboardingStorage) {
  const read = (key: string): boolean => {
    try {
      return storage.getItem(key) === "true";
    } catch {
      return false;
    }
  };
  const write = (key: string, value: boolean): void => {
    try {
      storage.setItem(key, String(value));
    } catch {
      // A store that refuses leaves the flag unset; the screen shows again, which is harmless.
    }
  };
  return {
    get welcomeSeen(): boolean {
      return read(WELCOME_SEEN_KEY);
    },
    get demoPending(): boolean {
      return read(DEMO_PENDING_KEY);
    },
    get demoSeen(): boolean {
      return read(DEMO_SEEN_KEY);
    },
    passWelcome(): void {
      write(WELCOME_SEEN_KEY, true);
    },
    /** After an interactive sign-in that succeeded, and nowhere else. */
    queueDemo(): void {
      write(DEMO_PENDING_KEY, true);
    },
    /** Finishing and skipping are the same: it is seen and no longer pending. */
    finishDemo(): void {
      write(DEMO_SEEN_KEY, true);
      write(DEMO_PENDING_KEY, false);
    },
  };
}

/**
 * Whether the demo should be over the app now (iOS `RootView.swift:89-139`): queued, not yet seen,
 * and not while a new recovery key is being shown or is still being set up (it opens when that
 * panel closes).
 */
export function demoDue(flags: { demoPending: boolean; demoSeen: boolean }, holdForRecovery: boolean): boolean {
  return flags.demoPending && !flags.demoSeen && !holdForRecovery;
}

/** The browser's storage, or one that stores nothing when there is none. */
export function browserOnboarding() {
  try {
    return onboarding(localStorage);
  } catch {
    return onboarding({ getItem: () => null, setItem: () => {} });
  }
}
