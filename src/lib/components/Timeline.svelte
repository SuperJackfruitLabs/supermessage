<script lang="ts">
  // The message pane for the focused room. Virtualized with virtua's
  // `VList` (`shift: true`) so that back-pagination — which prepends older
  // history at the top of an already-inverted (newest-at-bottom) list —
  // never jerks the scroll position. See virtua's own docs on the `shift`
  // prop: "scroll position will be maintained from the end ... when items
  // are added to/removed from start", which is exactly the prepend case
  // here. `getKey` is `item.id`, never the array index — virtua's README
  // calls out index keys as broken specifically when `shift` is on, since
  // prepending renumbers every existing index.
  //
  // Two independent local guards live here, not in the store:
  //  - `paginating` + `reachedStart` stop back-pagination from firing a
  //    burst of overlapping requests as scroll events fire continuously,
  //    and stop asking once `timelineStore.paginateBack` reports the start
  //    of history has been reached.
  //  - `followBottom` decides whether a newly *appended* item (a live
  //    message, not a paginated-in older one) should pull the view down to
  //    it. Only true when the reader was already at/near the bottom, so
  //    scrolling up to read history is never yanked back down by an
  //    incoming message.
  //
  // Every item renders something or is deliberately silent — there is no
  // "falls through the template" case. `timelineItemView.ts` classifies each
  // item into a render decision (bubble / emote / system line / placeholder
  // / nothing); this component only switches on that decision, it never
  // inspects the wire `kind` itself beyond `dateDivider`, which renders real
  // content the classifier's vocabulary doesn't cover. See that module's
  // doc comment for why suppression happens here and not in the core.
  //
  // Never optimistically appends: `timelineStore.items` is driven entirely
  // by the diff stream (see `timeline.svelte.ts`), including the local echo
  // of a just-sent message. This component only ever reads that store.
  //
  // Per-room reset: `+page.svelte` wraps this component in `{#key
  // roomsStore.selectedId}`, so switching rooms remounts it fresh rather
  // than requiring an internal "did the room change" effect — `paginating`,
  // `reachedStart` and `followBottom` all start clean for every room,
  // and virtua's own internal size cache doesn't get reused across two
  // unrelated item sets either.

  import { tick } from "svelte";
  import { VList, type VListHandle } from "virtua/svelte";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { viewFor } from "./timelineItemView";
  import type { TimelineItem } from "$lib/ipc";

  /** Page size for `timelineStore.paginateBack`, per the task brief. */
  const PAGE_SIZE = 20;
  /** How close to the top (px) triggers a back-pagination request. */
  const TOP_THRESHOLD = 200;
  /** How close to the bottom (px) counts as "still following" the tail. */
  const BOTTOM_THRESHOLD = 120;

  let vlist: VListHandle | undefined = $state();
  let paginating = $state(false);
  let reachedStart = $state(false);
  let followBottom = true;

  // Tracked outside `$state` on purpose: bookkeeping for the effect below,
  // not a value the template reads.
  let previousLastId: string | null = null;

  const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium" });
  const timeFormatter = new Intl.DateTimeFormat(undefined, { timeStyle: "short" });

  function formatDate(ms: number | null): string {
    return ms == null ? "" : dateFormatter.format(new Date(ms));
  }

  function formatTime(ms: number | null): string {
    return ms == null ? "" : timeFormatter.format(new Date(ms));
  }

  /**
   * Scrolls to the newest item whenever the tail actually grew (a new
   * message arrived) and the reader was following it — never on a prepend,
   * since a prepend leaves the last item's id unchanged.
   */
  $effect(() => {
    const items = timelineStore.items;
    const lastId = items.length > 0 ? items[items.length - 1]!.id : null;
    if (lastId === previousLastId) return;

    const isFirstLoadForRoom = previousLastId === null;
    previousLastId = lastId;
    if (items.length === 0 || !(isFirstLoadForRoom || followBottom)) return;

    const targetIndex = items.length - 1;
    void tick().then(() => vlist?.scrollToIndex(targetIndex, { align: "end" }));
  });

  async function requestOlderMessages(): Promise<void> {
    paginating = true;
    try {
      reachedStart = await timelineStore.paginateBack(PAGE_SIZE);
    } catch (err) {
      console.error("timeline paginateBack failed", err);
    } finally {
      paginating = false;
    }
  }

  function handleScroll(offset: number): void {
    if (!vlist) return;
    const distanceFromBottom = vlist.getScrollSize() - vlist.getViewportSize() - offset;
    followBottom = distanceFromBottom < BOTTOM_THRESHOLD;

    if (!paginating && !reachedStart && offset < TOP_THRESHOLD) {
      void requestOlderMessages();
    }
  }
</script>

<div class="min-h-0 flex-1">
  {#if timelineStore.items.length === 0}
    <div class="flex h-full items-center justify-center">
      <p class="text-sm text-content-muted">No messages yet</p>
    </div>
  {:else}
    <VList
      bind:this={vlist}
      data={timelineStore.items}
      getKey={(item: TimelineItem) => item.id}
      shift
      onscroll={handleScroll}
      class="px-4"
    >
      {#snippet children(item: TimelineItem, _index: number)}
        {#if item.kind === "dateDivider"}
          <div class="flex items-center justify-center py-3" role="separator">
            <span class="rounded-full bg-surface-raised px-3 py-1 text-xs font-medium text-content-muted">
              {formatDate(item.timestampMs)}
            </span>
          </div>
        {:else}
          {@const view = viewFor(item)}
          {#if view.render === "bubble"}
            <div class="flex py-1 {item.isOwn ? 'justify-end' : 'justify-start'}">
              <div
                class="max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                  ? 'bg-accent text-accent-content'
                  : 'border border-border bg-surface-raised text-content'}"
              >
                {#if !item.isOwn}
                  <p class="mb-0.5 text-xs font-medium text-content-muted">
                    {item.senderDisplayName ?? item.sender ?? "Unknown"}
                  </p>
                {/if}
                <p
                  class="selectable text-sm whitespace-pre-wrap break-words {view.muted &&
                  !item.isOwn
                    ? 'text-content-muted'
                    : ''}"
                >
                  {item.body}
                </p>
                <p
                  class="mt-1 text-right text-[10px] {item.isOwn
                    ? 'text-accent-content/70'
                    : 'text-content-muted'}"
                >
                  {#if item.isOwn && item.sendState === "sendingFailed"}
                    Failed to send
                  {:else if item.isOwn && item.sendState === "notSentYet"}
                    Sending…
                  {:else}
                    {formatTime(item.timestampMs)}
                  {/if}
                </p>
              </div>
            </div>
          {:else if view.render === "emote"}
            <div class="flex justify-center py-1 px-4">
              <p class="selectable text-center text-xs text-content-muted italic">
                {item.senderDisplayName ?? item.sender ?? "Someone"}
                {item.body}
              </p>
            </div>
          {:else if view.render === "system"}
            <!--
              Membership lines, room creation, encryption enabled, room
              replaced. Centred and muted so a history full of them reads as
              a quiet log rather than as messages.
            -->
            <div class="flex justify-center py-1.5">
              <span class="text-xs text-content-muted">{view.text}</span>
            </div>
          {:else if view.render === "placeholder"}
            <!--
              Anything the reader must be told about but this build can't
              render fully yet: undecryptable events on a fresh device (the
              common case in a real encrypted room), redactions, media,
              stickers, polls, custom suite events. Never the bare empty
              bubble that rendering nothing used to produce — see
              `timelineItemView.ts`.
            -->
            <div class="flex justify-center py-1.5">
              <span class="text-xs text-content-muted italic">{view.text}</span>
            </div>
          {/if}
          <!-- view.render === "none": deliberately silent, see `timelineItemView.ts`. -->
        {/if}
      {/snippet}
    </VList>
  {/if}
</div>
