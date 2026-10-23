// What the dock above the composer says: "Sent to {agent}", then "{agent} is on it…"
// (iOS `TimelineView.swift:177-195`). The words and the rule are the core's
// (`acknowledgement`, which reads the timeline rows itself); this asks with the room's
// facts and drops the answer of an ask the reader has moved on from.

import {
  acknowledgement as defaultAcknowledgement,
  type Acknowledgement,
  type RoomCast,
} from "$lib/ipc";

export interface AcknowledgementDeps {
  acknowledgement: typeof defaultAcknowledgement;
}

const DEFAULT_DEPS: AcknowledgementDeps = { acknowledgement: defaultAcknowledgement };

/** What the dock is asked with. */
export interface AcknowledgementFacts {
  roomId: string;
  /** The cast of the focused room; one that belongs to another room is ignored. */
  cast: RoomCast | null;
  /** The agents typing now (`typingView`'s `agents`). */
  typingAgents: readonly string[];
  /** The focused room's turn is running. */
  inProgress: boolean;
}

export function createAcknowledgement(deps: AcknowledgementDeps = DEFAULT_DEPS, onChange?: () => void) {
  let current: Acknowledgement | null = null;
  let currentRoom: string | null = null;
  // Bumped by every ask and by clear(): an answer that finds it moved is stale.
  let counter = 0;

  function set(next: Acknowledgement | null): void {
    const same =
      current === next ||
      (current !== null &&
        next !== null &&
        current.kind === next.kind &&
        current.text === next.text);
    if (same) return;
    current = next;
    onChange?.();
  }

  return {
    get current(): Acknowledgement | null {
      return current;
    },
    /**
     * Ask the core. Leaving a room forgets its dock at once; the same room keeps its dock until
     * the new answer lands, so a refresh does not flicker. A refusal or any failure is no dock.
     */
    async ask(facts: AcknowledgementFacts): Promise<void> {
      const mine = ++counter;
      if (currentRoom !== facts.roomId) {
        currentRoom = facts.roomId;
        set(null);
      }
      // A cast from another room would name somebody who is not here.
      const cast = facts.cast?.roomId === facts.roomId ? facts.cast : null;
      try {
        const answer = await deps.acknowledgement(
          facts.roomId,
          cast?.addressee ?? null,
          cast?.agentIds ?? [],
          facts.typingAgents,
          facts.inProgress,
        );
        if (mine !== counter) return;
        set(answer);
      } catch {
        if (mine !== counter) return;
        set(null);
      }
    },
    clear(): void {
      counter++;
      currentRoom = null;
      set(null);
    },
  };
}

/** The glyph's colour and shape for a dock kind (iOS `TimelineView.swift:198-236`). */
export function dockGlyph(kind: Acknowledgement["kind"]): { glyph: "check" | "ellipsis"; tone: string } {
  return kind === "sent"
    ? { glyph: "check", tone: "text-ok" }
    : { glyph: "ellipsis", tone: "text-accent" };
}
