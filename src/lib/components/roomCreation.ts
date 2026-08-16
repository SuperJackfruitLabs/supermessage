// Starting a conversation, rather than waiting to be invited to one.
//
// The client could only ever be used with rooms somebody else created and
// somebody else invited you to, from some other client. That is a strange
// shape for the client meant to be the place you talk to your fleet — you
// could not open a mission room, or bring a second agent into one.
//
// What is here is the checking, which is the part that can be wrong: a Matrix
// id and a room alias have exact shapes, and a request built from a typo comes
// back as an opaque homeserver error rather than "that is not a user id".

/** `@localpart:server`, the shape every Matrix user id has. */
const USER_ID = /^@[^\s:]+:[^\s:]+(:\d+)?$/;
/** `#alias:server`, or a `!roomid:server` — both are things to join. */
const ROOM_TARGET = /^[#!][^\s:]+:[^\s:]+(:\d+)?$/;

export function isUserId(value: string): boolean {
  return USER_ID.test(value.trim());
}

export function isRoomTarget(value: string): boolean {
  return ROOM_TARGET.test(value.trim());
}

/**
 * Splits what was typed into invitees.
 *
 * Commas, spaces or newlines, because somebody pasting a list has no reason to
 * know which one this expects.
 */
export function parseInvitees(value: string): string[] {
  return value
    .split(/[\s,]+/)
    .map((part) => part.trim())
    .filter((part) => part !== "");
}

export interface CreationProblem {
  /** What to say, in the operator's terms. */
  message: string;
}

/**
 * Why this room cannot be created yet, or null when it can.
 *
 * A room with no name and nobody in it is not refused for being useless — it
 * is refused because there is nothing to show in a roster and nobody to talk
 * to, which the operator would discover only after making it.
 */
export function creationProblem(name: string, invitees: string[]): CreationProblem | null {
  if (name.trim() === "" && invitees.length === 0) {
    return { message: "Give the room a name, or somebody to invite." };
  }

  const bad = invitees.filter((id) => !isUserId(id));
  if (bad.length > 0) {
    return {
      message: `Not a user id: ${bad.join(", ")}. They look like @agent:id.agentpod.dev.`,
    };
  }

  return null;
}

/**
 * Whether a new room should be a DM.
 *
 * One other person is a conversation with somebody; two or more is a room.
 * Deciding this from the invitee count rather than asking is what keeps the
 * decision out of the operator's way — and it matches how the AgentPod bridge
 * already files its own per-agent rooms.
 */
export function shouldBeDirect(invitees: string[]): boolean {
  return invitees.length === 1;
}
