// The three core calls the live card makes, so a story can answer them without
// a Tauri runtime. The words and the markdown parse are the core's; nothing here
// computes them.

import {
  elapsedLabel,
  richBlocksFromMarkdown,
  turnStatusWord,
  type RichBlock,
  type TurnEnding,
} from "$lib/ipc";

export interface LiveCardServices {
  /** `core::turn_label::elapsed_label`: "8s", "1m 04s". */
  elapsedLabel(ms: number): Promise<string>;
  /** `core::turn_label::turn_status_word`: "Working", "Done", "Stopped". */
  statusWord(endedBy: TurnEnding | null): Promise<string>;
  /** `core::rich` over the revealed answer text. */
  parse(markdown: string): Promise<RichBlock[]>;
  /**
   * The clock the running timer reads, in epoch milliseconds. Absent means the
   * wall clock; a story supplies a fixed one so a frame cannot depend on how
   * fast it rendered.
   */
  now?(): number;
}

/** The wall clock: what the app reads. */
export const systemClock = (): number => Date.now();

/** The clock a card should read: the services' own when they carry one, else the wall clock. */
export function clockOf(services: LiveCardServices): () => number {
  return services.now ? () => services.now!() : systemClock;
}

export const ipcServices: LiveCardServices = {
  elapsedLabel,
  statusWord: turnStatusWord,
  parse: richBlocksFromMarkdown,
};
