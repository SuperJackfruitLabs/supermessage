// What the live card says, as pure functions over the core's turn record.
//
// Every fact here is the core's (`core::turns`): which step is current, how
// many are done, what failed. This only picks which of them to show and fixes
// the words iOS uses (`Timeline/LiveTurnView.swift`). Nothing is recomputed.

import type { ToolPhase, TurnSummary } from "$lib/ipc";
import type { TimelineDisplayRow } from "../timelineGrouping";

/** A turn the core has ended, by whatever means. */
export function isFinished(summary: TurnSummary | null): boolean {
  return summary?.endedBy != null;
}

/**
 * Whether the card names the current step (`LiveTurnView.swift:66-68`): a turn
 * that is going, when the step is a tool call or no answer text shows yet.
 * "Writing the answer" over streaming text says nothing the text does not.
 */
export function showsCurrentStep(summary: TurnSummary | null, answerShown: boolean): boolean {
  if (isFinished(summary)) return false;
  return summary?.stepIsTool === true || !answerShown;
}

/** `LiveTurnView.swift:204-217`. */
export function stepsDoneLabel(n: number): string {
  return n === 1 ? "1 step done" : `${n} steps done`;
}

/** `LiveTurnView.swift:210`: the failed step's title, then the word. */
export function failedLabel(title: string): string {
  return `${title} failed`;
}

/** `LiveTurnView.swift:88`. */
export function stepsToggleLabel(n: number): string {
  return n === 1 ? "1 step" : `All ${n} steps`;
}

/**
 * The header's word before the core's answers (`LiveTurnView.swift:141-154`).
 * The word itself is `turnStatusWord` (it adds "Stopped"); this keeps the
 * header from being empty for the one IPC round trip.
 */
export function stateWord(summary: TurnSummary | null): "Working" | "Done" {
  return isFinished(summary) ? "Done" : "Working";
}

export type StatusTone = "working" | "ok" | "neutral";

/**
 * The word's colour (`LiveTurnView.swift:151-160`). A turn the core gave up on
 * is neither working nor a success, so it takes no success treatment.
 */
export function statusTone(summary: TurnSummary | null): StatusTone {
  switch (summary?.endedBy) {
    case undefined:
    case null:
      return "working";
    case "timedOut":
      return "neutral";
    case "done":
    case "reply":
      return "ok";
  }
}

export type ToolGlyph = "done" | "failed" | "running" | "waiting";

/** `LiveTurnView.swift` `ToolRow.icon`: queued and unknown share the clock. */
export function toolGlyph(phase: ToolPhase): ToolGlyph {
  switch (phase) {
    case "done":
      return "done";
    case "failed":
      return "failed";
    case "running":
      return "running";
    case "queued":
    case "unknown":
      return "waiting";
  }
}

/**
 * Milliseconds the header measures (`LiveTurnView.swift:156-174`): to now while
 * the turn runs, to its last event when it is still (stories) or finished.
 */
export function elapsedMs(summary: TurnSummary, nowMs: number, still: boolean): number {
  const end = still || isFinished(summary) ? summary.lastActivityMs : nowMs;
  return Math.max(0, end - summary.startedAtMs);
}

/**
 * The name when the room has no agent name (`TimelineCollectionView.swift:739`):
 * the newest message from somebody else, else "Agent". The room's own agent
 * name, when there is one, wins.
 */
export function writerName(agentName: string | null, rows: readonly TimelineDisplayRow[]): string {
  if (agentName !== null) return agentName;
  for (let i = rows.length - 1; i >= 0; i--) {
    const row = rows[i]!;
    if (row.type === "item" && !row.item.isOwn && row.item.kind === "message") return row.senderName;
  }
  return "Agent";
}
