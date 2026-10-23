// What /login shows and what a sign-in sets in motion (iOS `WelcomeView` and `LoginView.swift:115-126`).
// The welcome shows until it has been passed once on this install, then the form. Only an
// interactive sign-in that succeeded queues the first-run demo; a restored session never reaches
// /login's form at all, and nothing here is called for one.

import type { onboarding } from "$lib/onboarding";

export interface LoginGateDeps {
  onboarding: ReturnType<typeof onboarding>;
  markFreshSignIn: () => void;
}

export function createLoginGate({ onboarding: flags, markFreshSignIn }: LoginGateDeps) {
  return {
    get view(): "welcome" | "form" {
      return flags.welcomeSeen ? "form" : "welcome";
    },
    passWelcome(): void {
      flags.passWelcome();
    },
    /** Call after `login` succeeded, and only then. */
    signedIn(): void {
      markFreshSignIn();
      flags.queueDemo();
    },
  };
}
