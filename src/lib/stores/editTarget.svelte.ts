// Per-room pending edit — what the composer shows as "Editing message" and
// what `Composer.svelte`'s `send` routes through `editMessage`. Same shape and
// the same reason as `replyTarget.svelte.ts`: Composer is not remounted on a
// room switch, so the target is keyed by room id and each write names its room.

/** The own message being edited. */
export interface PendingEdit {
  eventId: string;
  /** The body at the moment the edit started; seeds the composer text. */
  body: string;
}

/** The parts of a timeline row an edit starts from. */
export interface EditSource {
  item: { editable: boolean; eventId: string | null; body: string | null };
}

export function createEditTargetStore() {
  let targets = $state<Record<string, PendingEdit>>({});

  return {
    get(roomId: string): PendingEdit | null {
      return targets[roomId] ?? null;
    },
    set(roomId: string, edit: PendingEdit): void {
      targets = { ...targets, [roomId]: edit };
    },
    clear(roomId: string): void {
      if (!(roomId in targets)) return;
      const next = { ...targets };
      delete next[roomId];
      targets = next;
    },
    /** Drops every room's edit (sign-out). */
    clearAll(): void {
      targets = {};
    },
    /**
     * An edit starts only on an `editable` row with an event id, seeded with
     * `body ?? ""` (iOS `EditTarget.swift:38-44`). `null` otherwise.
     */
    fromRow(row: EditSource): PendingEdit | null {
      if (!row.item.editable || row.item.eventId === null) return null;
      return { eventId: row.item.eventId, body: row.item.body ?? "" };
    },
  };
}

export const editTargetStore = createEditTargetStore();
