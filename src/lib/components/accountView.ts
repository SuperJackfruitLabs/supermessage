// Copy and constants for the Account panel (iOS `AccountPanel.swift`).

import type { RoomMember } from "$lib/ipc";

/** The sign-out confirm's title (`:178-187`); plain only while the account's name is unknown. */
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

/** The Blocked section's footer (`:112-130`), the iOS sentence verbatim. */
export const BLOCKED_FOOTER =
  "Their messages are hidden in every room, on all your devices. A blocked agent keeps running. Block someone from a room's member list.";

/** Under the links (`:142-144`). iOS says "long-press a message"; that is not a desktop gesture. */
export const REPORT_FOOTER =
  "To report abuse, open a message's More menu or a room's info. Reports go to your homeserver's administrators.";

/** The Blocked section appears only when someone is in it; an unknown list shows nothing. */
export function blockedSectionShown(blocked: RoomMember[] | null): boolean {
  return blocked !== null && blocked.length > 0;
}
