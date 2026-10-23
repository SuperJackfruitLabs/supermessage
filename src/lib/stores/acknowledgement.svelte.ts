// The dock above the composer ("Sent to…" → "… is on it…"), as a store the page reads and the
// session reset clears. The rules are the core's; `ackView.ts` asks and guards staleness.

import type { Acknowledgement } from "$lib/ipc";
import { createAcknowledgement, type AcknowledgementDeps, type AcknowledgementFacts } from "$lib/components/composer/ackView";

export function createAcknowledgementStore(deps?: AcknowledgementDeps) {
  let current = $state<Acknowledgement | null>(null);
  const ack = createAcknowledgement(deps, () => {
    current = ack.current;
  });
  return {
    get current(): Acknowledgement | null {
      return current;
    },
    ask(facts: AcknowledgementFacts): Promise<void> {
      return ack.ask(facts);
    },
    clear(): void {
      ack.clear();
    },
  };
}

export const acknowledgementStore = createAcknowledgementStore();
