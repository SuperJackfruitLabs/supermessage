// A request from outside the page (the tray) to go to a sidebar destination. Only a destination
// the sidebar has is honoured, so a stale or foreign name moves nothing.

import { DESTINATIONS, type Destination } from "$lib/stores/destinations.svelte";

/** Chooses `to` through `choose` (the page's own destination change) when it names one. */
export function navigateTo(to: string, choose: (destination: Destination) => void): void {
  const destination = DESTINATIONS.find((d) => d.id === to);
  if (destination) choose(destination.id);
}
