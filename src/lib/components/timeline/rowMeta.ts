/**
 * Padding that keeps the action pill inside the scroller at both ends.
 *
 * The pill straddles the top edge of its message block (half of it above),
 * so the first loaded row needs room above for that half, and the last row
 * needs room below because its block can be shorter than the pill. 16px is
 * more than the half-pill (about 14px at the default text size).
 */
export function rowEdgeClass(index: number, count: number): string {
  const edges: string[] = [];
  if (index === 0) edges.push("pt-4");
  if (index === count - 1) edges.push("pb-4");
  return edges.join(" ");
}
