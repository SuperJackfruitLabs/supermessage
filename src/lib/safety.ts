// Block and report, over injected deps so the order of effects is testable without Tauri.
//
// Ports iOS `ReportSubject` (SupermessageKit/Stores/SafetyStore.swift:11-37) and
// `Session.block/unblock/report` (SupermessageKit/Session.swift:332-392).

import {
  ignoreUser,
  reportEvent,
  reportRoom,
  reportUser,
  roomInfo,
  unignoreUser,
  type TimelineRow,
} from "$lib/ipc";
import { errorMessage } from "$lib/errorMessage";
import { safetyStore, type createSafetyStore } from "$lib/stores/safety.svelte";

type SafetyStore = ReturnType<typeof createSafetyStore>;

export type ReportSubject =
  | { kind: "message"; roomId: string; eventId: string; senderId: string; senderName: string; isAgent: boolean }
  | { kind: "room"; roomId: string; name: string }
  | { kind: "user"; userId: string; name: string; isAgent: boolean };

/** Who reporting this subject can also block: a message's sender, a user, nobody for a room. */
export function blockable(
  subject: ReportSubject,
): { userId: string; name: string; isAgent: boolean } | null {
  switch (subject.kind) {
    case "message":
      return { userId: subject.senderId, name: subject.senderName, isAgent: subject.isAgent };
    case "room":
      return null;
    case "user":
      return { userId: subject.userId, name: subject.name, isAgent: subject.isAgent };
  }
}

export interface SafetyDeps {
  ignoreUser: typeof ignoreUser;
  unignoreUser: typeof unignoreUser;
  reportEvent: typeof reportEvent;
  reportRoom: typeof reportRoom;
  reportUser: typeof reportUser;
  store: Pick<SafetyStore, "isBlocked" | "noteBlocked" | "noteUnblocked" | "hide">;
}

const defaultDeps: SafetyDeps = {
  ignoreUser,
  unignoreUser,
  reportEvent,
  reportRoom,
  reportUser,
  store: safetyStore,
};

/** A core refusal in the presenter's words; anything that is not a core error is "That didn't work." */
function refusal(err: unknown): string {
  const isCoreError = typeof err === "object" && err !== null && typeof (err as { kind?: unknown }).kind === "string";
  return isCoreError ? errorMessage(err) : "That didn't work.";
}

/** Blocks `userId`. Returns the refusal text, or `null` when it landed (and is then noted). */
export async function block(userId: string, deps: SafetyDeps = defaultDeps): Promise<string | null> {
  try {
    await deps.ignoreUser(userId);
  } catch (err) {
    return refusal(err);
  }
  deps.store.noteBlocked(userId);
  return null;
}

/** Unblocks `userId`. Returns the refusal text, or `null` when it landed (and is then noted). */
export async function unblock(userId: string, deps: SafetyDeps = defaultDeps): Promise<string | null> {
  try {
    await deps.unignoreUser(userId);
  } catch (err) {
    return refusal(err);
  }
  deps.store.noteUnblocked(userId);
  return null;
}

/**
 * How a report ended. `reportedNotBlocked` means the report landed and the block did not, so the
 * report must never be offered again.
 */
export type ReportResult =
  | { status: "sent" }
  | { status: "refused"; message: string }
  | { status: "reportedNotBlocked"; message: string };

/**
 * Sends a report, then hides the message and blocks the sender if asked.
 *
 * The report goes first and alone decides success: a message is hidden only
 * once the homeserver has it. Blocking is a second request; if it fails the
 * report still stands and the text says which half did not happen.
 */
export async function report(
  subject: ReportSubject,
  reason: string,
  alsoBlock: boolean,
  deps: SafetyDeps = defaultDeps,
): Promise<ReportResult> {
  try {
    switch (subject.kind) {
      case "message":
        await deps.reportEvent(subject.roomId, subject.eventId, reason);
        break;
      case "room":
        await deps.reportRoom(subject.roomId, reason);
        break;
      case "user":
        await deps.reportUser(subject.userId, reason);
        break;
    }
  } catch (err) {
    return { status: "refused", message: refusal(err) };
  }
  if (subject.kind === "message") deps.store.hide(subject.eventId);
  const target = blockable(subject);
  if (alsoBlock && target !== null && !deps.store.isBlocked(target.userId)) {
    const blockRefused = await block(target.userId, deps);
    if (blockRefused !== null) {
      return { status: "reportedNotBlocked", message: `Reported, but blocking didn't work: ${blockRefused}` };
    }
  }
  return { status: "sent" };
}

/**
 * Whether `senderId` is an agent in `roomId`, by the core's own flag on the
 * room's members (`RoomMemberDto.is_agent`). `false` when the room cannot be
 * read or the sender is not a member.
 */
export async function memberIsAgent(
  roomId: string,
  senderId: string,
  load: typeof roomInfo = roomInfo,
): Promise<boolean> {
  try {
    const info = await load(roomId);
    return info.members.find((m) => m.userId === senderId)?.isAgent ?? false;
  } catch {
    return false;
  }
}

/**
 * What a report of this row names, or `null` when the row has no event id or
 * no sender (a report against half an address is one the homeserver refuses).
 * The name is the short attribution (iOS `TimelineCollectionView.swift:1000-1007`).
 */
export async function reportSubjectFor(
  row: Pick<TimelineRow, "senderShort"> & { item: Pick<TimelineRow["item"], "eventId" | "sender"> },
  roomId: string,
  isAgent: (senderId: string) => Promise<boolean> = (senderId) => memberIsAgent(roomId, senderId),
): Promise<ReportSubject | null> {
  const { eventId, sender } = row.item;
  if (eventId === null || sender === null) return null;
  return {
    kind: "message",
    roomId,
    eventId,
    senderId: sender,
    senderName: row.senderShort,
    isAgent: await isAgent(sender),
  };
}
