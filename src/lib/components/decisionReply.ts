import { sendGateDecision, sendPermissionAnswer, type CustomEventDecision } from "$lib/ipc";

/** Send the answer selected on a dispatch card. */
export async function sendDecisionReply(
  roomId: string,
  eventId: string | null,
  decision: CustomEventDecision,
  optionId: string,
  comment: string | null = null,
): Promise<void> {
  if (decision.subject !== null) {
    if (eventId === null) throw new Error("A gate decision needs a confirmed event");
    await sendGateDecision(roomId, decision.subject, optionId, comment, eventId, decision.prompt);
  } else {
    // A permission request has no place for a comment, so `comment` is dropped.
    // The content is still the option's name as a plain room message, which
    // is what the AgentPod bridge accepts (`core::timeline::permission_answer_content`).
    // What changed is the sender: it is addressed by room and awaited, so it
    // resolves only once the homeserver has the event, and a failure rejects
    // here and leaves the card answerable.
    await sendPermissionAnswer(roomId, optionId);
  }
}

/**
 * Answer a decision and say whether it went out. Never rejects: the card
 * stays answerable after a failed send, so the caller needs a verdict, not an
 * exception to catch.
 */
export async function answerDecision(
  roomId: string,
  eventId: string | null,
  decision: CustomEventDecision,
  optionId: string,
  comment: string | null = null,
): Promise<boolean> {
  try {
    await sendDecisionReply(roomId, eventId, decision, optionId, comment);
    return true;
  } catch (err) {
    console.error("failed to send a decision", { eventId, optionId, err });
    return false;
  }
}
