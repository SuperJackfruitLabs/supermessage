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
