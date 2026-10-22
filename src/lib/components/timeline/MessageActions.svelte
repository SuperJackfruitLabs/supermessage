<script lang="ts">
  import { tick } from "svelte";
  import { QUICK_REACTIONS } from "../emojiPicker";
  import { menuActions, menuOpensUp } from "./messageMenu";

  import type { ItemRow } from "./rowMeta";

  /**
   * The message action pill: Reply, the quick reactions, "+", and "More".
   *
   * **The pill is `absolute top-0 -translate-y-1/2`**, straddling the top
   * edge of its block, so it needs no room below the message. Before, it sat
   * in the gap below (`top-full`) and the newest message's bar was clipped by
   * the scroller; measured 2026-10-22 at 150% text size, the bar wrapped to
   * two lines (47px) under a 32px reserve and ended 22px past the scroller's
   * bottom. Rows reserve the half-pill overhang at both ends instead
   * (`rowMeta.ts`).
   *
   * It resolves against its nearest POSITIONED ancestor. Any wrapper marked
   * `group` — which drives the `group-hover` reveal — must therefore also be
   * `relative`, or the pill escapes to whatever is positioned further up (the
   * dispatch card once did). `timelineActionAnchor.test.ts` enforces both
   * halves.
   */
  export interface Props {
    row: ItemRow;
    /** Which item's reaction picker is open, if any. */
    pickingReactionFor: string | null;
    onStartReply: (row: ItemRow) => void;
    onToggleReaction: (eventId: string | null, key: string) => void;
    onPickReaction: (itemId: string | null) => void;
    onCopy: (row: ItemRow) => void;
    onEdit: (row: ItemRow) => void;
    onDelete: (row: ItemRow) => void;
    /**
     * Report is handed the More button as well: the menu has closed by the time the report sheet
     * opens, so the sheet could not otherwise know where to return focus.
     */
    onReport: (row: ItemRow, opener: HTMLElement | null) => void;
  }

  let {
    row,
    pickingReactionFor,
    onStartReply,
    onToggleReaction,
    onPickReaction,
    onCopy,
    onEdit,
    onDelete,
    onReport,
  }: Props = $props();

  const item = $derived(row.item);
  const actions = $derived(menuActions(row));

  let menuOpen = $state(false);
  let openUp = $state(false);
  let measured = $state(false);
  let moreButton = $state<HTMLButtonElement | null>(null);
  let menuEl = $state<HTMLDivElement | null>(null);

  function closeMenu() {
    menuOpen = false;
    measured = false;
    openUp = false;
  }

  // Place the menu once it is in the DOM: upward when it would run past the
  // scroller's bottom (the newest message), downward otherwise.
  $effect(() => {
    if (!menuOpen || !menuEl || !moreButton) return;
    const scroller = moreButton.closest(".timeline-scroller");
    if (scroller) {
      const s = scroller.getBoundingClientRect();
      const a = moreButton.getBoundingClientRect();
      openUp = menuOpensUp(
        { top: a.top, bottom: a.bottom },
        { top: s.top, bottom: s.bottom },
        menuEl.getBoundingClientRect().height,
      );
    }
    measured = true;
    // After the re-render that drops `invisible`: a `visibility: hidden`
    // element cannot take focus, so focusing now left it on the button.
    const el = menuEl;
    void tick().then(() => el.querySelector<HTMLElement>("[role=menuitem]")?.focus());
  });

  function choose(fn: (row: ItemRow, opener: HTMLElement | null) => void) {
    closeMenu();
    fn(row, moreButton);
  }

  function onWindowKeydown(e: KeyboardEvent) {
    // Another layer's Escape (a panel, a dialog) is not ours to take.
    if (menuOpen && e.key === "Escape" && !e.defaultPrevented) {
      e.preventDefault();
      closeMenu();
      moreButton?.focus();
    }
  }

  // Tabbing away leaves no stale menu to steal a later Escape. A null
  // `relatedTarget` (a click on a non-focusable spot, which pointerdown
  // handles, or a browser that does not focus buttons) is not "left".
  function onMenuFocusOut(e: FocusEvent) {
    const next = e.relatedTarget as Node | null;
    if (!menuOpen || !next) return;
    if ((e.currentTarget as HTMLElement).contains(next)) return;
    closeMenu();
  }

  function onWindowPointerDown(e: PointerEvent) {
    if (!menuOpen) return;
    const t = e.target as Node | null;
    if (t && (menuEl?.contains(t) || moreButton?.contains(t))) return;
    closeMenu();
  }
</script>

<svelte:window onkeydown={onWindowKeydown} onpointerdown={onWindowPointerDown} />

{#if actions.bar}
  <!--
    Chrome, not content: no `.selectable`, and outside the message container
    on the sheet ground, like `reactionsRow`.

    Faded out until the row is hovered or a button inside has focus
    (`focus-within`, not `hover` alone), so tabbing still reaches every
    button: opacity, never `display: none`. `pointer-events-none` until
    revealed so an invisible pill cannot eat a click meant for the message.
    It stays revealed while the More menu is open, because focus is inside.

    `font-sans`: this is chrome, and the column is set in the reading serif.
  -->
  <div
    class="pointer-events-none absolute top-0 right-0 z-10 flex -translate-y-1/2 flex-nowrap items-center gap-0.5 rounded-control border border-border bg-surface-raised px-1 font-sans opacity-0 shadow-overlay transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100"
  >
    {#if actions.react}
      <button
        type="button"
        onclick={() => onStartReply(row)}
        class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        Reply
      </button>
      {#each QUICK_REACTIONS as emoji (emoji)}
        <button
          type="button"
          onclick={() => onToggleReaction(item.eventId, emoji)}
          aria-label={`React with ${emoji}`}
          class="rounded px-1 py-0.5 text-ui transition-colors hover:bg-surface-sunken"
        >
          {emoji}
        </button>
      {/each}
      <!-- The six above stay the fast path; "+" opens the full picker. -->
      <button
        type="button"
        onclick={() => onPickReaction(item.id)}
        aria-label="React with another emoji"
        class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        +
      </button>
    {/if}
    {#if actions.more}
      <!-- svelte-ignore a11y_no_static_element_interactions -->
      <div class="relative" onfocusout={onMenuFocusOut}>
        <button
          bind:this={moreButton}
          type="button"
          aria-label="More actions"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onclick={() => (menuOpen ? closeMenu() : (menuOpen = true))}
          class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
        >
          <span aria-hidden="true">⋯</span>
        </button>
        {#if menuOpen}
          <!-- Hidden for the one frame it takes to measure which way it opens. -->
          <div
            bind:this={menuEl}
            role="menu"
            class="absolute right-0 z-20 flex min-w-32 flex-col rounded-control border border-border bg-surface-raised p-1 shadow-overlay {openUp
              ? 'bottom-full mb-1'
              : 'top-full mt-1'} {measured ? '' : 'invisible'}"
          >
            {#if actions.copy}
              <button
                type="button"
                role="menuitem"
                onclick={() => choose(onCopy)}
                class="rounded px-2 py-1 text-left text-ui text-content transition-colors hover:bg-surface-sunken"
              >
                Copy
              </button>
            {/if}
            {#if actions.edit}
              <button
                type="button"
                role="menuitem"
                onclick={() => choose(onEdit)}
                class="rounded px-2 py-1 text-left text-ui text-content transition-colors hover:bg-surface-sunken"
              >
                Edit
              </button>
            {/if}
            {#if actions.del}
              <button
                type="button"
                role="menuitem"
                onclick={() => choose(onDelete)}
                class="rounded px-2 py-1 text-left text-ui text-danger transition-colors hover:bg-surface-sunken"
              >
                Delete
              </button>
            {/if}
            {#if actions.report}
              {#if actions.copy || actions.edit || actions.del}
                <div role="separator" class="my-1 border-t border-border"></div>
              {/if}
              <button
                type="button"
                role="menuitem"
                onclick={() => choose(onReport)}
                class="rounded px-2 py-1 text-left text-ui text-danger transition-colors hover:bg-surface-sunken"
              >
                Report…
              </button>
            {/if}
          </div>
        {/if}
      </div>
    {/if}
  </div>
{/if}
