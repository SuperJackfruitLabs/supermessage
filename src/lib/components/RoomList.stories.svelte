<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { fleetRosterRows } from "$lib/fixtures";
  import type { RosterFilter, RosterFilterChip, RosterSection } from "$lib/ipc";

  import RoomList, { type Source } from "./RoomList.svelte";
  import { createRosterFilter } from "./roster/rosterFilter";

  // A stand-in core. The real rules are `core::roster`'s and are tested there; these only give the
  // list something to draw: Unread keeps the rows with an unread count, and the words are the core's.
  const kept = (sections: RosterSection[], filter: RosterFilter) =>
    sections
      .map((s) => ({ ...s, rows: s.rows.filter((r) => filter === "all" || r.row.room.unread > 0) }))
      .filter((s) => s.rows.length > 0);

  function memory(initial: Record<string, string> = {}) {
    const map = new Map(Object.entries(initial));
    return { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v) };
  }

  function standIn(sections: RosterSection[], stored = "", titles: Record<string, string> = {}): Partial<Source> {
    return {
      rooms: sections.flatMap((s) => s.rows.map((r) => r.row)),
      selectedId: null,
      rosterSections: async () => sections,
      rosterHiddenInvitations: async () => 0,
      spaceDisplayName: async (name) => titles[name] ?? name,
      roomAvatar: async () => null,
      filter: createRosterFilter({
        storage: memory(stored ? { "supermessage.roster.filter": stored } : {}),
        rosterFilterFromKey: async (key) => (key === "unread" ? "unread" : "all"),
        rosterFilterKey: async (filter) => filter,
        rosterFilterSections: async (s, filter) => kept(s, filter),
        rosterFilterChips: async (s): Promise<RosterFilterChip[]> => [
          { filter: "all", title: "All", count: s.flatMap((x) => x.rows).length },
          { filter: "unread", title: "Unread", count: kept(s, "unread").flatMap((x) => x.rows).length },
        ],
        rosterFilterEmptyState: async () => ({ title: "Nothing unread", description: "Every room is read." }),
      }),
    };
  }

  const one = (id: string, title: string | null, rows = fleetRosterRows.filter((r) => r.row.room.unread > 0)): RosterSection => ({
    id,
    title,
    detail: null,
    attention: false,
    rows,
  });

  const allRead = fleetRosterRows
    .filter((r) => r.row.affordance === "compose")
    .map((r) => ({ ...r, row: { ...r.row, room: { ...r.row.room, unread: 0 } } }));

  const { Story } = defineMeta({ title: "Roster/RoomList", component: RoomList });

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const q = (root: HTMLElement, sel: string) => root.querySelector<HTMLElement>(sel);
  const rowCount = (root: HTMLElement) => root.querySelectorAll("button:has([data-testid='row-second-line'])").length;
</script>

{#snippet frame(source: Partial<Source>, extra = "")}
  <div class="flex h-[460px] w-[288px] flex-col bg-surface font-sans text-content" style={extra}>
    <RoomList {source} />
  </div>
{/snippet}

<Story
  name="No rooms yet"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => q(root, "[data-testid='no-rooms']"), "the empty state");
    const text = q(root, "[data-testid='no-rooms']")!.textContent ?? "";
    if (!text.includes("No rooms yet") || !text.includes("Rooms appear here as they sync.")) throw new Error(`copy: ${text}`);
    if (q(root, "[data-testid='filter-chips']")) throw new Error("no chips without rooms");
    if (rowCount(root) !== 0) throw new Error("no rows");
  }}
>
  {#snippet template()}{@render frame(standIn([]))}{/snippet}
</Story>

<Story
  name="Chips over the roster"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => q(root, "[data-testid='filter-chips']"), "the chips");
    const nav = q(root, "nav[aria-label='Rooms']")!;
    const kids = [...nav.children];
    if (kids[0]?.getAttribute("aria-label") !== "Roster arrangement") throw new Error("the arrangement menu stays first");
    if (kids[1]?.getAttribute("data-testid") !== "filter-chips") throw new Error("the chips are under it");
    await settled(() => q(root, "[data-testid='chip-count-unread']"), "the count");
    if (q(root, "[data-testid='chip-count-unread']")!.textContent?.trim() !== "3") throw new Error("Unread counts the whole arrangement");
  }}
>
  {#snippet template()}{@render frame(standIn([one("recent", null, fleetRosterRows)]))}{/snippet}
</Story>

<!-- A roster taller than its column: the chip row keeps its height (it is a flex child of a scrolling column). -->
<Story
  name="Chips stay whole in a long roster"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => rowCount(root) >= 20, "twenty rooms");
    const chips = q(root, "[data-testid='filter-chips']")!;
    if (chips.getBoundingClientRect().height < 30) throw new Error(`the chip row is squashed to ${chips.getBoundingClientRect().height}px`);
    const nav = q(root, "nav[aria-label='Rooms']")!;
    if (nav.scrollHeight <= nav.clientHeight) throw new Error("the roster should scroll");
  }}
>
  {#snippet template()}
    {@render frame(
      standIn([
        one(
          "recent",
          null,
          Array.from({ length: 4 }, (_, n) =>
            fleetRosterRows.map((r) => ({ ...r, row: { ...r.row, room: { ...r.row.room, id: `${r.row.room.id}-${n}` } } })),
          ).flat(),
        ),
      ]),
    )}
  {/snippet}
</Story>

<Story
  name="Unread narrows, never reorders"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => q(root, "[data-testid='chip-unread']"), "the chips");
    q(root, "[data-testid='chip-unread']")!.click();
    await settled(() => q(root, "[data-testid='chip-unread']")?.getAttribute("aria-pressed") === "true", "Unread pressed");
    await settled(() => rowCount(root) === 3, "three unread rooms");
    // The count did not shrink with the list.
    if (q(root, "[data-testid='chip-all']")!.getAttribute("aria-label") !== "All, 6") throw new Error("All keeps its count");
  }}
>
  {#snippet template()}{@render frame(standIn([one("recent", null, fleetRosterRows)], "unread"))}{/snippet}
</Story>

<Story
  name="Nothing unread with Show all"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => q(root, "[data-testid='roster-empty']"), "the empty state");
    const text = q(root, "[data-testid='roster-empty']")!.textContent ?? "";
    if (!text.includes("Nothing unread") || !text.includes("Every room is read.") || !text.includes("Show all")) {
      throw new Error(`copy: ${text}`);
    }
    if (q(root, "[data-testid='chip-unread']")!.getAttribute("aria-pressed") !== "true") throw new Error("Unread is in force");
  }}
>
  {#snippet template()}{@render frame(standIn([one("recent", null, allRead)], "unread"))}{/snippet}
</Story>

<Story
  name="Show all returns to the list"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => q(root, "[data-testid='show-all']"), "Show all");
    q(root, "[data-testid='show-all']")!.click();
    await settled(() => rowCount(root) === allRead.length, "every room back");
    // The list animates a removal, so the old node may linger for a moment.
    await settled(() => !q(root, "[data-testid='roster-empty']"), "the empty state to leave");
    if (q(root, "[data-testid='chip-all']")!.getAttribute("aria-pressed") !== "true") throw new Error("All is back");
  }}
>
  {#snippet template()}{@render frame(standIn([one("recent", null, allRead)], "unread"))}{/snippet}
</Story>

<Story
  name="Readable machine sections"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => root.querySelectorAll("h2").length === 2, "two section headings");
    const heads = [...root.querySelectorAll("h2")].map((h) => h.textContent?.trim());
    if (heads[0] !== "Rakeshs-MacBook-Pro" || heads[1] !== "Runtime 9247e5") throw new Error(`headings: ${heads}`);
  }}
>
  {#snippet template()}
    {@render frame(
      standIn(
        [
          one("m1", "Rakeshs-MacBook-Pro.local", fleetRosterRows.slice(0, 2)),
          one("m2", "9247e5b1-3c2d-4e8f-a1b2-0123456789ab", fleetRosterRows.slice(2, 4)),
        ],
        "",
        {
          "Rakeshs-MacBook-Pro.local": "Rakeshs-MacBook-Pro",
          "9247e5b1-3c2d-4e8f-a1b2-0123456789ab": "Runtime 9247e5",
        },
      ),
    )}
  {/snippet}
</Story>
