// Copy and constants for the Account panel (iOS `AccountPanel.swift`).

/** The sign-out confirm's title (`:178-187`); plain until M3 brings `account`. */
export function signOutTitle(name: string | null): string {
  return name ? `Sign out of ${name}?` : "Sign out?";
}

/** `:125-138`, in order. */
export const ACCOUNT_LINKS: readonly { label: string; url: string }[] = [
  { label: "Help & support", url: "https://supermessage.dev/support/" },
  { label: "Terms of use", url: "https://supermessage.dev/terms/" },
  { label: "Privacy", url: "https://supermessage.dev/privacy/" },
];

/** Desktop shows the app version; iOS does not. */
export function versionLine(version: string | null): string | null {
  return version === null ? null : `Version ${version}`;
}

/** The Appearance section's footer (`:93-95`). */
export const APPEARANCE_FOOTER =
  "Black is true black in dark mode, for OLED screens. The accent colours buttons, links and your own highlights.";

/** The Sign out section's footer (`:148-157`). */
export const SIGN_OUT_FOOTER = "Signing out removes this account and its messages from this device.";
