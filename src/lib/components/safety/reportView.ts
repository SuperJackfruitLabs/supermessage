// The words of the report sheet, verbatim from iOS `Panels/ReportSheet.swift:170-228`.
//
// Kept apart from the component so each sentence is asserted exactly: these are
// promises about who sees a report, and a reworded one is a different promise.

import { blockable, type ReportSubject } from "$lib/safety";

/** Show the counter only when fewer than this many characters remain (iOS `:60-61`). */
export const COUNTER_FROM = 200;

export function reportTitle(subject: ReportSubject): string {
  switch (subject.kind) {
    case "message":
      return "Report message";
    case "room":
      return "Report room";
    case "user":
      return "Report";
  }
}

/** The name the report is about: a message's sender, a room, a person. */
export function subjectName(subject: ReportSubject): string {
  return subject.kind === "message" ? subject.senderName : subject.name;
}

/** Who receives the report, and first, who does not. */
export function reportExplanation(subject: ReportSubject): string {
  switch (subject.kind) {
    case "message":
      return `Your report goes to your homeserver's administrators, not to ${subject.senderName}. The message is hidden from you once the report is sent.`;
    case "room":
      return `Your report about ${subject.name} goes to your homeserver's administrators, not to the people in it.`;
    case "user":
      return (
        `Your report goes to your homeserver's administrators, not to ${subject.name}.` +
        (subject.isAgent ? " Reporting an agent doesn't stop it running." : "")
      );
  }
}

export function confirmationTitle(subject: ReportSubject): string {
  return subject.kind === "message" ? "Report this message?" : `Report ${subject.name}?`;
}

export function confirmationMessage(subject: ReportSubject): string {
  return `The homeserver's administrators will see it. ${subjectName(subject)} won't be told.`;
}

export function sendLabel(subject: ReportSubject, alsoBlock: boolean): string {
  return alsoBlock && blockable(subject) !== null ? "Report and block" : "Report";
}

/** What blocking does, said differently for an agent: its messages stop, its work does not. */
export function blockConsequence(isAgent: boolean): string {
  return isAgent
    ? "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told."
    : "You won't see their messages in any room, on any of your devices. They aren't told.";
}

/** The counter under the reason field, or `null` while there is plenty of room. */
export function counterLine(remaining: number): { text: string; over: boolean } | null {
  if (remaining >= COUNTER_FROM) return null;
  if (remaining < 0) return { text: `${-remaining} characters over the limit`, over: true };
  return { text: `${remaining} characters left`, over: false };
}

export function canSend(remaining: number): boolean {
  return remaining >= 0;
}
