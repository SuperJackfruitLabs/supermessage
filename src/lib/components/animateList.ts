// A Svelte action for lists whose items appear, vanish and change order under
// the reader.
//
// `@formkit/auto-animate` ships adapters for React, Vue, Solid, Preact, Angular
// and Marko, but not Svelte — the core `autoAnimate(el)` is framework-agnostic
// though, and a Svelte action is exactly the shape it wants: give it the parent
// element once, and it takes a FLIP measurement of every child before each DOM
// mutation and animates the difference.

import autoAnimate, { type AutoAnimateOptions } from "@formkit/auto-animate";
import type { Action } from "svelte/action";

/**
 * How long a row takes to move, appear or leave.
 *
 * Short. This is a roster reordering itself because a message arrived, not a
 * transition between screens — the reader's eye is usually somewhere else
 * entirely, and the animation exists so that a row moving does not read as a
 * row being replaced.
 */
const DURATION_MS = 180;

/**
 * Animates additions, removals and reordering among an element's children.
 *
 * **Never put this on a virtualized list.** virtua mounts and unmounts rows
 * continuously as you scroll, so every scroll would become a cascade of
 * insert/remove animations — and worse, it caches each row's measured size by
 * key with no way to ask for a remeasure, which is the fault that clipped a
 * reaction chip on 2026-08-17. An animation that changes a row's height while
 * that cache is authoritative reintroduces it several times a second. The
 * timeline, its reaction rows, and anything else inside `VList` are out of
 * bounds; `RoomList` and `SpacesRail` are not, because they are ordinary
 * scrolling lists of a few dozen items.
 *
 * `prefers-reduced-motion` is honoured by the library itself
 * (`respectUserMotionPreference`, on by default), so there is no branch here.
 */
export const animateList: Action<HTMLElement, Partial<AutoAnimateOptions> | undefined> = (
  node,
  options,
) => {
  const controller = autoAnimate(node, { duration: DURATION_MS, ...options });
  return {
    destroy() {
      // The controller has no teardown of its own — it holds a MutationObserver
      // on `node`, which the browser collects with the node. Disabling it stops
      // any animation still in flight from touching a detached tree.
      controller.disable();
    },
  };
};
