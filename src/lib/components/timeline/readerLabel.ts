/**
 * The label under an own message's reader faces: "Read by " plus the core's
 * `peopleLabel` for the readers (iOS `TimelineChrome.swift:172-173`).
 *
 * `label` is called from a render, so it asks the core once per set of readers
 * and answers `null` until the answer is in; `onChange` tells the host to
 * render again. A failed ask is remembered as unanswered, so a render never
 * repeats it. `clear` forgets everything, including answers still on the way.
 */
export interface ReaderLabels {
  label(readerIds: readonly string[]): string | null;
  clear(): void;
}

export function createReaderLabels(
  peopleLabel: (userIds: string[]) => Promise<string>,
  onChange: () => void = () => {},
): ReaderLabels {
  // null: asked, no answer (yet or ever).
  let known = new Map<string, string | null>();
  return {
    label(readerIds) {
      if (readerIds.length === 0) return null;
      const key = readerIds.join("\n");
      if (known.has(key)) return known.get(key)!;
      known.set(key, null);
      const mine = known;
      void peopleLabel([...readerIds]).then(
        (named) => {
          if (mine !== known) return;
          known.set(key, `Read by ${named}`);
          onChange();
        },
        (err) => {
          console.error("readerLabel: could not name the readers", err);
        },
      );
      return null;
    },
    clear() {
      known = new Map();
    },
  };
}
