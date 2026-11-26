// What the call bar and the Call button say and when they show (design: "SuperMessage desktop
// (Linux)"). Pure, so the stories and the page agree.

import type { CallSnapshot, CallView, Counterpart } from "$lib/ipc";

export const CALL_BUTTON_LABEL = "Call";
export const HANG_UP_LABEL = "Hang up";
export const MUTE_LABEL = "Mute";
export const UNMUTE_LABEL = "Unmute";

/** The bar's lead: "Call with ✦ Ada — Researcher". */
export function callTitle(call: CallView): string {
  return `Call with ${call.agentName}`;
}

/**
 * The agent's state, from its own participant's `superdial.state` (the core reads it from the
 * agent's identity only). Until we are in and the agent has said, it is connecting.
 */
export function stateWord(call: CallView): string {
  if (call.phase === "connecting") return "Connecting…";
  switch (call.agentState) {
    case "listening":
      return "Listening";
    case "thinking":
      return "Thinking…";
    case "speaking":
      return "Speaking";
    default:
      return "Connecting…";
  }
}

const pad = (n: number) => String(n).padStart(2, "0");

/** Time in the call, `mm:ss`, and `h:mm:ss` past an hour. */
export function elapsedLabel(startedAtMs: number | null, nowMs: number): string {
  if (startedAtMs === null) return "00:00";
  const total = Math.max(0, Math.floor((nowMs - startedAtMs) / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/**
 * The Call button: the setting on, a room the core says is one agent's (`RoomCast.counterpart`,
 * the room's single agent as its header names it), a room this account is in, and no call
 * running anywhere (one call at a time).
 */
export function callButtonShown(
  enabled: boolean,
  counterpart: Counterpart | null,
  joined: boolean,
  call: CallView | null,
): boolean {
  return enabled && counterpart !== null && joined && call === null;
}

/** The notice to show in `roomId`, if it is that room's. */
export function noticeFor(snapshot: CallSnapshot, roomId: string | null): string | null {
  const notice = snapshot.notice;
  return roomId !== null && notice !== null && notice.roomId === roomId ? notice.text : null;
}
