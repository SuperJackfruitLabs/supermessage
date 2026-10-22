import { sendGateDecision, sendMessage, type CustomEventDecision } from "$lib/ipc";

/** Send the answer selected on a dispatch card. */
export async function sendDecisionReply(
  roomId: string,
  eventId: string | null,
  decision: CustomEventDecision,
  optionId: string,
): Promise<void> {
  if (decision.subject !== null) {
    if (eventId === null) throw new Error("A gate decision needs a confirmed event");
    await sendGateDecision(roomId, decision.subject, optionId, null, eventId, decision.prompt);
  } else {
    // Permission option ids are the human-readable answer accepted by the
    // AgentPod bridge. They intentionally remain ordinary room messages.
    await sendMessage(roomId, optionId);
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
): Promise<boolean> {
  try {
    await sendDecisionReply(roomId, eventId, decision, optionId);
    return true;
  } catch (err) {
    console.error("failed to send a decision", { eventId, optionId, err });
    return false;
  }
}
