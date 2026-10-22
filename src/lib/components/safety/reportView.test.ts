import { describe, expect, it } from "vitest";
import type { ReportSubject } from "$lib/safety";
import {
  blockConsequence,
  canSend,
  confirmationMessage,
  confirmationTitle,
  counterLine,
  reportExplanation,
  reportTitle,
  sendLabel,
  subjectName,
} from "./reportView";

const message = (isAgent = false): ReportSubject => ({
  kind: "message",
  roomId: "!r:x",
  eventId: "$e",
  senderId: "@a:x",
  senderName: "Atlas",
  isAgent,
});
const room: ReportSubject = { kind: "room", roomId: "!r:x", name: "Cloudchamber" };
const person: ReportSubject = { kind: "user", userId: "@p:x", name: "Priya", isAgent: false };
const agent: ReportSubject = { kind: "user", userId: "@agent_a:x", name: "Atlas", isAgent: true };

describe("report words", () => {
  it("titles", () => {
    expect(reportTitle(message())).toBe("Report message");
    expect(reportTitle(room)).toBe("Report room");
    expect(reportTitle(person)).toBe("Report");
  });
  it("explanations", () => {
    expect(reportExplanation(message())).toBe(
      "Your report goes to your homeserver's administrators, not to Atlas. The message is hidden from you once the report is sent.",
    );
    expect(reportExplanation(room)).toBe(
      "Your report about Cloudchamber goes to your homeserver's administrators, not to the people in it.",
    );
    expect(reportExplanation(person)).toBe("Your report goes to your homeserver's administrators, not to Priya.");
  });
  it("the agent sentence is on an agent user and not on a person", () => {
    expect(reportExplanation(agent)).toBe(
      "Your report goes to your homeserver's administrators, not to Atlas. Reporting an agent doesn't stop it running.",
    );
    expect(reportExplanation(person)).not.toContain("agent");
  });
  it("confirmation title and message", () => {
    expect(confirmationTitle(message())).toBe("Report this message?");
    expect(confirmationTitle(room)).toBe("Report Cloudchamber?");
    expect(confirmationTitle(person)).toBe("Report Priya?");
    expect(confirmationMessage(message())).toBe("The homeserver's administrators will see it. Atlas won't be told.");
    expect(confirmationMessage(room)).toBe("The homeserver's administrators will see it. Cloudchamber won't be told.");
  });
  it("subjectName", () => {
    expect(subjectName(message())).toBe("Atlas");
    expect(subjectName(room)).toBe("Cloudchamber");
    expect(subjectName(person)).toBe("Priya");
  });
  it("sendLabel", () => {
    expect(sendLabel(message(), true)).toBe("Report and block");
    expect(sendLabel(message(), false)).toBe("Report");
    expect(sendLabel(person, true)).toBe("Report and block");
    // A room has nobody to block.
    expect(sendLabel(room, true)).toBe("Report");
  });
  it("blockConsequence", () => {
    expect(blockConsequence(true)).toBe(
      "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told.",
    );
    expect(blockConsequence(false)).toBe(
      "You won't see their messages in any room, on any of your devices. They aren't told.",
    );
  });
});

describe("counter", () => {
  it("is null while there is room", () => {
    expect(counterLine(200)).toBeNull();
    expect(counterLine(2000)).toBeNull();
  });
  it("counts down below 200", () => {
    expect(counterLine(199)).toEqual({ text: "199 characters left", over: false });
    expect(counterLine(0)).toEqual({ text: "0 characters left", over: false });
  });
  it("says how far over", () => {
    expect(counterLine(-5)).toEqual({ text: "5 characters over the limit", over: true });
  });
  it("canSend", () => {
    expect(canSend(0)).toBe(true);
    expect(canSend(-1)).toBe(false);
  });
});
