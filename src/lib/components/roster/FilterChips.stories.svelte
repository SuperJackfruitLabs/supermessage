<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { RosterFilterChip } from "$lib/ipc";
  import FilterChips, { type Props } from "./FilterChips.svelte";

  const noop = () => {};

  // iOS `RosterFilterChips.swift:60-71`: All 5, Unread 2.
  const chips = (unread: number): RosterFilterChip[] => [
    { filter: "all", title: "All", count: 5 },
    { filter: "unread", title: "Unread", count: unread },
  ];

  const { Story } = defineMeta({
    title: "Roster/FilterChips",
    component: FilterChips,
    args: { chips: chips(2), selected: "all", onSelect: noop },
  });

  const button = (root: HTMLElement, key: string) =>
    root.querySelector<HTMLButtonElement>(`[data-testid='chip-${key}']`)!;
  const text = (el: Element | null) => el?.textContent?.trim() ?? null;

  function check(root: HTMLElement, selected: "all" | "unread", unreadCount: string | null, unreadName: string) {
    if (button(root, "all").getAttribute("aria-pressed") !== String(selected === "all")) throw new Error("All pressed");
    if (button(root, "unread").getAttribute("aria-pressed") !== String(selected === "unread")) throw new Error("Unread pressed");
    if (root.querySelector("[data-testid='chip-count-all']")) throw new Error("All has no count");
    const shown = text(root.querySelector("[data-testid='chip-count-unread']"));
    if (shown !== unreadCount) throw new Error(`Unread count: ${shown}`);
    if (button(root, "all").getAttribute("aria-label") !== "All, 5") throw new Error("All's name");
    if (button(root, "unread").getAttribute("aria-label") !== unreadName) {
      throw new Error(`Unread's name: ${button(root, "unread").getAttribute("aria-label")}`);
    }
    const tops = new Set([...root.querySelectorAll("button")].map((b) => Math.round(b.getBoundingClientRect().top)));
    if (tops.size !== 1) throw new Error("the chips share one row");
  }
</script>

{#snippet frame(args: Props, width: string, style = "")}
  <div class="bg-surface font-sans text-content" style="width: {width}; {style}">
    <FilterChips {...args} />
  </div>
{/snippet}

<Story name="All and Unread" play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "all", "2", "Unread, 2")}>
  {#snippet template(args)}{@render frame(args, "288px")}{/snippet}
</Story>

<Story
  name="Unread selected"
  args={{ selected: "unread" }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "unread", "2", "Unread, 2")}
>
  {#snippet template(args)}{@render frame(args, "288px")}{/snippet}
</Story>

<!-- A chip with nothing behind it shows no count, but still says "0" to a screen reader. -->
<Story
  name="Unread with none"
  args={{ chips: chips(0) }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "all", null, "Unread, 0")}
>
  {#snippet template(args)}{@render frame(args, "288px")}{/snippet}
</Story>

<Story
  name="Unread selected, dark"
  args={{ selected: "unread" }}
  play={async ({ canvasElement }) => check(canvasElement as HTMLElement, "unread", "2", "Unread, 2")}
>
  {#snippet template(args)}
    <div data-appearance="dark" class="bg-surface">{@render frame(args, "288px")}</div>
  {/snippet}
</Story>

<!-- 150 % text in a narrow sidebar: the row scrolls sideways and no chip wraps. -->
<Story
  name="Largest text at 600px"
  args={{ selected: "unread" }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    check(root, "unread", "2", "Unread, 2");
    for (const b of root.querySelectorAll("button")) {
      if (b.getBoundingClientRect().height > 40 * 1.5) throw new Error("a chip wrapped");
    }
    const row = root.querySelector<HTMLElement>("[data-testid='filter-chips']")!;
    if (row.scrollWidth <= row.clientWidth) throw new Error("the row should scroll at this width");
  }}
>
  {#snippet template(args)}{@render frame(args, "150px", "zoom: 1.5;")}{/snippet}
</Story>
