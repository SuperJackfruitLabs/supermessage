<script lang="ts">
  import { onMount } from "svelte";
  import { menuPosition, type RowMenuItem } from "./rowMenu";

  /**
   * The roster row's menu: an HTML `role="menu"` opened at a point, drawn in tokens. It opens at
   * `at`, then moves inside the window once measured. Focus lands on the first item; Arrow keys
   * move, Escape and a click outside close.
   */
  export interface Props {
    items: RowMenuItem[];
    at: { x: number; y: number };
    onChoose: (id: RowMenuItem["id"]) => void;
    onClose: () => void;
  }

  let { items, at, onChoose, onClose }: Props = $props();

  let menu = $state<HTMLDivElement | null>(null);
  let position = $state<{ left: number; top: number } | null>(null);

  // Placed once measured: opened at the point, moved inside the window if it would leave it.
  onMount(() => {
    if (!menu) return;
    const box = menu.getBoundingClientRect();
    position = menuPosition(at, { width: box.width, height: box.height }, {
      width: window.innerWidth,
      height: window.innerHeight,
    });
    // Transparent until placed, not `invisible`: a hidden item cannot take focus.
    menu.querySelector<HTMLElement>("[role=menuitem]")?.focus();
  });

  function onKeydown(e: KeyboardEvent): void {
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
      return;
    }
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const entries = [...(menu?.querySelectorAll<HTMLElement>("[role=menuitem]") ?? [])];
    const current = entries.indexOf(document.activeElement as HTMLElement);
    const step = e.key === "ArrowDown" ? 1 : -1;
    entries[(current + step + entries.length) % entries.length]?.focus();
    e.preventDefault();
  }

  function onPointerDown(e: PointerEvent): void {
    if (!menu?.contains(e.target as Node)) onClose();
  }
</script>

<svelte:window onkeydown={onKeydown} onpointerdown={onPointerDown} />

<div
  bind:this={menu}
  role="menu"
  aria-label="Room actions"
  class="fixed z-50 flex min-w-36 flex-col rounded-control border border-border bg-surface-raised p-1 font-sans shadow-overlay {position ===
  null
    ? 'opacity-0'
    : ''}"
  style="left: {position?.left ?? at.x}px; top: {position?.top ?? at.y}px;"
>
  {#each items as item (item.id)}
    <button
      type="button"
      role="menuitem"
      onclick={() => onChoose(item.id)}
      class="rounded px-2 py-1 text-left text-ui transition-colors hover:bg-surface-sunken {item.destructive
        ? 'text-danger'
        : 'text-content'}"
    >
      {item.label}
    </button>
  {/each}
</div>
