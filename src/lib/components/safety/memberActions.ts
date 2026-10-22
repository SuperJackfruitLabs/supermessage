// The words and rules of a room member's actions (iOS `RoomInfoPanel.swift:238-259, 310-323`).

import type { RoomMember } from "$lib/ipc";
import { blockConsequence } from "./reportView";

export function memberName(member: Pick<RoomMember, "displayName" | "userId">): string {
  return member.displayName ?? member.userId;
}

/**
 * Whether to show this member as blocked: the core's list when it has them, otherwise the
 * member's own `isIgnored`, less whatever this panel has just unblocked (the flag lags the echo).
 */
export function memberIsBlocked(
  member: Pick<RoomMember, "userId" | "isIgnored">,
  blocked: ReadonlySet<string>,
  unblockedHere: ReadonlySet<string>,
): boolean {
  if (blocked.has(member.userId)) return true;
  return member.isIgnored && !unblockedHere.has(member.userId);
}

export function memberActionsMessage(blocked: boolean, isAgent: boolean): string {
  return blocked
    ? "Unblocking shows their messages again, including ones sent while they were blocked."
    : blockConsequence(isAgent);
}

export const blockLabel = (name: string): string => `Block ${name}`;
export const unblockLabel = (name: string): string => `Unblock ${name}`;
export const reportLabel = (name: string): string => `Report ${name}…`;
