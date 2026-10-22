// One place for "a mute or pin write is in flight for this room", shared by the row menu and the
// room-info panel. Two writes for the same room cannot overlap, and when one finishes (however it
// ends) the room's revision moves, so the other surface knows to re-read what the core now says.

import { SvelteMap, SvelteSet } from "svelte/reactivity";
import { exclusively } from "$lib/components/roomSettings";

const writing = new SvelteSet<string>();
const revisions = new SvelteMap<string, number>();

export const roomSettingsWrites = {
  /** A write for this room is in flight. */
  busy(roomId: string): boolean {
    return writing.has(roomId);
  },
  /** Moves each time a write for this room finishes; read it in an effect to reload. */
  revision(roomId: string): number {
    return revisions.get(roomId) ?? 0;
  },
  /** Runs `work` unless a write for this room is already running. */
  async run<T>(roomId: string, work: () => Promise<T>): Promise<{ ran: true; value: T } | { ran: false }> {
    let started = false;
    try {
      return await exclusively(writing, roomId, () => {
        started = true;
        return work();
      });
    } finally {
      // A refused call changed nothing, so it announces nothing.
      if (started) revisions.set(roomId, (revisions.get(roomId) ?? 0) + 1);
    }
  },
};
