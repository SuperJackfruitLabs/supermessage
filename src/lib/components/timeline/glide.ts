// The history's glide while an answer streams (owner decision Q5; iOS
// `TimelineCollectionView.swift`, `TimelineCollection`).
//
// While a turn streams the list lays out without animation, and then every
// visible cell is drawn where it was and eased to where it now is, together:
// "Moving everything by the same amount at the same time is what makes it one
// motion rather than the snap-and-slide the default animation produced." The
// desktop's tail-follow re-pin moves the scroller by assigning `scrollTop`
// (instantly), so the history jumps up by that much; this draws each row back
// at its old place and lets it ease up.
//
// **Transform only.** Nothing resizes, and the streaming cell's size is never
// animated: `animateList.ts:24-32` is why a height change inside `VList`
// breaks virtua's measurements. The animation is additive, as iOS's is
// (`isAdditive = true`), so a second growth mid-glide adds its own offset to
// the one still running rather than restarting it.

/** iOS `TimelineCollection.glide`, `TimelineCollectionView.swift:187`: 0.32 s. */
export const GLIDE_MS = 320;

/**
 * iOS `CAMediaTimingFunction(controlPoints: 0.25, 0.1, 0.25, 1)`,
 * `TimelineCollectionView.swift:226`.
 */
export const GLIDE_EASING = "cubic-bezier(0.25, 0.1, 0.25, 1)";

/** iOS glides only for growth over half a point (`after - before > 0.5`, `:201`). */
export const GLIDE_MIN_PX = 0.5;

/**
 * How far the content moved up in the viewport when the scroller was re-pinned
 * from `before` to `after` — the distance to draw it back by — or 0 when
 * there is nothing to glide.
 */
export function glideDistance(before: number, after: number): number {
  const moved = after - before;
  return moved > GLIDE_MIN_PX ? moved : 0;
}

export interface GlideContext {
  /** A turn is streaming: the live card is in the list. */
  streaming: boolean;
  /** The reader is at the newest end; scrolled back, the reader's place is kept as it is. */
  following: boolean;
  reducedMotion: boolean;
}

/** iOS: only while a turn streams, only at the newest message, never under Reduce Motion. */
export function shouldGlide(ctx: GlideContext, distance: number): boolean {
  return distance > 0 && ctx.streaming && ctx.following && !ctx.reducedMotion;
}

/** `translation.y` from `-delta`... in the web's orientation: from `+distance` to 0. */
export function glideKeyframes(distance: number): Keyframe[] {
  return [{ transform: `translateY(${distance}px)` }, { transform: "translateY(0)" }];
}

export function glideOptions(): KeyframeAnimationOptions {
  return { duration: GLIDE_MS, easing: GLIDE_EASING, composite: "add" };
}

/** Starts the glide on every element given. */
export function glide(elements: Iterable<HTMLElement>, distance: number): void {
  const keyframes = glideKeyframes(distance);
  const options = glideOptions();
  for (const el of elements) el.animate(keyframes, options);
}
