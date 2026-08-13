<script lang="ts">
  // The message pane for the focused room. Virtualized with virtua's
  // `VList` (`shift: true`) so that back-pagination — which prepends older
  // history at the top of an already-inverted (newest-at-bottom) list —
  // never jerks the scroll position. See virtua's own docs on the `shift`
  // prop: "scroll position will be maintained from the end ... when items
  // are added to/removed from start", which is exactly the prepend case
  // here. `getKey` is `row.key`, never the array index — virtua's README
  // calls out index keys as broken specifically when `shift` is on, since
  // prepending renumbers every existing index.
  //
  // `VList` is driven by `displayRows`, a `$derived` of
  // `timelineStore.items` through `timelineGrouping.ts`'s
  // `groupTimelineItems`, not by `timelineStore.items` directly. That
  // recomputes automatically on every diff (append/prepend/edit) the store
  // folds in, since `$derived` re-runs whenever the reactive values it reads
  // change and the store publishes a genuinely new array on every update
  // (see `diff.ts`'s `applyOps` — it never mutates in place). Grouping is
  // presentation-only: it never mutates, reorders or filters
  // `timelineStore.items` itself, so nothing downstream that still reads
  // that array directly (the scroll-to-bottom effect below, the empty-state
  // check) is affected by it. See `timelineGrouping.ts`'s doc comment for
  // the run-boundary rules and why grouped rows key off the first item in a
  // run rather than something that reshapes on every append.
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
  // content the classifier's vocabulary doesn't cover, and `membershipGroup`
  // rows, which `groupTimelineItems` already reduced to display text. See
  // that module's doc comment for why suppression happens here and not in
  // the core.
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
  // unrelated item sets either. That remount also drops and recreates
  // `mediaCache` below, so a room switch never carries stale in-flight
  // fetches into the new room's item ids.
  //
  // Inline images (`view.render === "image"`): `TimelineItem.media` never
  // carries bytes, only metadata (see its doc comment) — `mediaCache`,
  // mirroring `avatarCache`'s lazy/deduplicated/failure-remembered shape,
  // fetches the actual `data:` URI through `ipc.mediaFetch` the first time
  // a given item is rendered, keyed on the item's event id. `imageBoxStyle`
  // computes the exact same reserved box — from `view.width`/`view.height`,
  // capped to `IMAGE_MAX_WIDTH`/`IMAGE_MAX_HEIGHT` so one huge image can
  // never widen the bubble (the same class of bug the security review found
  // with an unconstrained `<table>`, noted below on `.message-html table`)
  // — for both the loading skeleton and the loaded `<img>`, so the swap
  // between them changes nothing about the row's size. That matters more
  // here than an ordinary reflow would: the list is virtualized by `virtua`
  // with `shift` (see the top of this comment), and a row resizing out from
  // under it fights that same scroll-anchoring. Any failure — the core
  // reporting nothing renderable, a rejected fetch, or the `<img>` itself
  // failing to decode — converges on `mediaCache.hasFailed`, which falls
  // back to the plain-text placeholder row, never a broken-image icon.
  //
  // A bubble renders `item.formattedBody` with `{@html}` when present,
  // falling back to the plain `item.body` otherwise (the `{#if
  // item.formattedBody}` branch in the bubble markup below). `{@html}` is
  // otherwise a red flag in a Svelte app — it is safe here **only** because
  // of guarantees made entirely on the Rust side, before this string ever
  // crosses IPC:
  //   1. `core::timeline::formatted_html_body` only populates `formattedBody`
  //      for a `format: "org.matrix.custom.html"` body, and only after
  //      `matrix_sdk_ui::timeline::Message::from_event` has already run
  //      ruma's `HtmlSanitizerMode::Compat` allowlist sanitiser over it
  //      (`matrix-sdk-ui`'s own `DEFAULT_SANITIZER_MODE`) — that pass is
  //      reliable for *element*/*attribute* allowlisting (no `<script>`, no
  //      `on*` handler, no `style` attribute survives it, on any element).
  //   2. `core::timeline::harden_formatted_body` then runs a second,
  //      narrower pass. This is **not** belt-and-braces on top of a working
  //      upstream check: ruma-html 0.8.0 has a real bug in the loop that
  //      checks `<a href>`/`<img src>` *schemes* (see that function's own
  //      doc comment for the exact mechanism), and without this second
  //      pass, `<a class="x" href="javascript:alert(1)">` and `<img
  //      alt="a" src="https://evil.example/beacon.png">` both reach this
  //      component's `{@html}` unchanged. This pass is what actually
  //      removes `<img>`/`<mx-reply>` outright and restricts `<a href>` to
  //      `http`/`https`/`mailto`/`matrix`.
  // If a future change needs to render more of the timeline as HTML, run it
  // through that same core-side path — never pipe a fresh string through
  // `{@html}` here just because this precedent exists; the guarantee lives
  // in the Rust code that produced the string, not in this file.
  //
  // Links inside that HTML are still plain `<a>` tags in a webview with no
  // browser chrome, so a click on one would otherwise replace this whole
  // app with the target page and leave no way back. Two independent layers
  // guard against that:
  //   1. `messageLinks.ts`'s `handleMessageBodyClick`/`handleMessageBodyAuxClick`,
  //      wired below on every bubble's content for both `onclick` (primary
  //      button, and any keyboard-activated link — both dispatch a real
  //      `click`) and `onauxclick` (middle-click specifically — per UI
  //      Events, a non-primary-button press dispatches `auxclick`, *not*
  //      `click`, so a plain `onclick` handler alone never sees it), route
  //      the click to the system browser via `tauri-plugin-opener` instead.
  //   2. `src-tauri/src/lib.rs`'s `on_navigation` handler on the main
  //      window is the backstop: it refuses any navigation whose origin
  //      isn't the app's own, regardless of what triggered it. That is the
  //      layer that actually makes "the SPA navigates away and the app is
  //      gone" unreachable — layer 1 only covers the click paths this file
  //      knows about today.

  import { tick } from "svelte";
  import { VList, type VListHandle } from "virtua/svelte";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import { viewFor } from "./timelineItemView";
  import { groupTimelineItems, type TimelineDisplayRow } from "./timelineGrouping";
  import { handleMessageBodyAuxClick, handleMessageBodyClick } from "./messageLinks";
  import { createMediaCache } from "$lib/stores/mediaCache.svelte";

  /** Page size for `timelineStore.paginateBack`, per the task brief. */
  const PAGE_SIZE = 20;
  /** How close to the top (px) triggers a back-pagination request. */
  const TOP_THRESHOLD = 200;
  /** How close to the bottom (px) counts as "still following" the tail. */
  const BOTTOM_THRESHOLD = 120;

  /**
   * The cap (px) an inline image thumbnail is allowed to occupy, regardless
   * of the bubble's own `max-w-[70%]` — a large image must not blow the
   * bubble, and with it the whole layout, out past this box. See this
   * file's top-of-script doc comment.
   */
  const IMAGE_MAX_WIDTH = 320;
  const IMAGE_MAX_HEIGHT = 320;
  /** Fallback box shape when the sender's client never reported dimensions. */
  const IMAGE_DEFAULT_ASPECT = 4 / 3;

  const mediaCache = createMediaCache();

  let vlist: VListHandle | undefined = $state();
  let paginating = $state(false);
  let reachedStart = $state(false);
  let followBottom = true;

  /**
   * The list `VList` actually renders — `timelineStore.items` with
   * consecutive membership changes collapsed. Recomputes whenever the store
   * publishes a new `items` array; see this file's top-of-script doc
   * comment for why that's automatic and why it never disturbs the raw
   * array itself.
   */
  let displayRows = $derived(groupTimelineItems(timelineStore.items));

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
   * The exact CSS `width`/`height` (plus a `max-width: 100%` safety net for
   * a narrow window) to reserve for an image thumbnail. Called identically
   * for the loading skeleton and the loaded `<img>` — see this file's
   * top-of-script doc comment for why that identity is load-bearing, not
   * cosmetic.
   *
   * Missing dimensions (`width`/`height` both `null`, or non-positive —
   * defensive against a malformed `ImageInfo`) fall back to a generic
   * `IMAGE_DEFAULT_ASPECT` box rather than collapsing to zero size, which
   * would reserve no space at all and reintroduce the exact reflow this
   * function exists to prevent.
   */
  function imageBoxStyle(width: number | null, height: number | null): string {
    const hasDimensions = width != null && height != null && width > 0 && height > 0;
    const ratio = hasDimensions ? width / height : IMAGE_DEFAULT_ASPECT;
    let boxWidth = hasDimensions ? Math.min(width, IMAGE_MAX_WIDTH) : IMAGE_MAX_WIDTH;
    let boxHeight = boxWidth / ratio;
    if (boxHeight > IMAGE_MAX_HEIGHT) {
      boxHeight = IMAGE_MAX_HEIGHT;
      boxWidth = boxHeight * ratio;
    }
    return `width: ${Math.round(boxWidth)}px; height: ${Math.round(boxHeight)}px; max-width: 100%;`;
  }

  /** A human-readable file size, e.g. `"1.2 MB"`. */
  function formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let value = bytes / 1024;
    let unitIndex = 0;
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024;
      unitIndex += 1;
    }
    return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unitIndex]}`;
  }

  /**
   * Scrolls to the newest item whenever the tail actually grew (a new
   * message arrived) and the reader was following it — never on a prepend,
   * since a prepend leaves the last item's id unchanged.
   *
   * The trigger check is against `timelineStore.items` (the raw, ungrouped
   * array) — grouping never adds or removes an underlying item, only
   * changes how many *rows* represent them, so the raw last-item id is
   * still the correct "did the tail actually grow" signal. The scroll
   * target index, though, must be `displayRows.length - 1`: `VList` is
   * bound to `displayRows`, and when the newest item just extended an
   * existing membership group, that group is one row, not one row per
   * member — indexing by `items.length` would overshoot past the end of
   * what `VList` actually has.
   */
  $effect(() => {
    const items = timelineStore.items;
    const lastId = items.length > 0 ? items[items.length - 1]!.id : null;
    if (lastId === previousLastId) return;

    const isFirstLoadForRoom = previousLastId === null;
    previousLastId = lastId;
    if (items.length === 0 || !(isFirstLoadForRoom || followBottom)) return;

    const targetIndex = displayRows.length - 1;
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
      data={displayRows}
      getKey={(row: TimelineDisplayRow) => row.key}
      shift
      onscroll={handleScroll}
      class="px-4"
    >
      {#snippet children(row: TimelineDisplayRow, _index: number)}
        {#if row.type === "membershipGroup"}
          <!--
            A collapsed run of consecutive membership changes — see
            `timelineGrouping.ts`. Same markup as an ordinary `system` line
            below (`view.render === "system"`) so a collapsed line reads no
            differently from an ungrouped one.
          -->
          <div class="flex justify-center py-1.5">
            <span class="text-xs text-content-muted">{row.text}</span>
          </div>
        {:else}
          {@const item = row.item}
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
                  class="min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  {#if item.formattedBody}
                    <!--
                      `{@html}` — safe only because of the guarantees this
                      file's top-of-script doc comment spells out in full
                      (core-side sanitisation + hardening, never redone here).
                      Do not copy this pattern onto any other field.

                      `onclick`/`onauxclick` here are delegated link handling,
                      not a control of their own — `handleMessageBodyClick`/
                      `handleMessageBodyAuxClick` only act when the click
                      bubbled up from a nested `<a href>`, and an `<a>`'s own
                      native keyboard activation (Enter/Space) already
                      dispatches a bubbling `click` the same way a primary
                      mouse click does, so there is no extra keyboard handler
                      this div itself needs to add. `onauxclick` specifically
                      exists for the middle-click case `onclick` alone cannot
                      see — see `messageLinks.ts`'s doc comment.
                    -->
                    <!-- svelte-ignore a11y_click_events_have_key_events -->
                    <!-- svelte-ignore a11y_no_static_element_interactions -->
                    <div
                      class="message-html selectable text-sm {view.muted && !item.isOwn
                        ? 'text-content-muted'
                        : ''}"
                      onclick={handleMessageBodyClick}
                      onauxclick={handleMessageBodyAuxClick}
                    >
                      {@html item.formattedBody}
                    </div>
                  {:else}
                    <p
                      class="selectable text-sm whitespace-pre-wrap break-words {view.muted &&
                      !item.isOwn
                        ? 'text-content-muted'
                        : ''}"
                    >
                      {item.body}
                    </p>
                  {/if}
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
            {:else if view.render === "image"}
              {@const src = mediaCache.get(item.id)}
              {@const failed = mediaCache.hasFailed(item.id)}
              <div class="flex py-1 {item.isOwn ? 'justify-end' : 'justify-start'}">
                <div
                  class="min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  {#if failed}
                    <!-- Never a broken-image icon: any failure — nothing
                         renderable, a rejected fetch, or the <img> itself
                         failing to decode — lands here. -->
                    <p class="selectable text-sm text-content-muted italic">{view.alt}</p>
                  {:else if src}
                    <!--
                      Content, not decoration — real `alt` text from the
                      message (unlike the room list's decorative avatars,
                      this is never `aria-hidden`).
                    -->
                    <img
                      {src}
                      alt={view.alt}
                      class="block rounded-lg object-cover"
                      style={imageBoxStyle(view.width, view.height)}
                      onerror={() => mediaCache.markFailed(item.id)}
                    />
                  {:else}
                    <!-- Still fetching: reserves the identical box the
                         loaded <img> above will occupy — see this file's
                         top-of-script doc comment. -->
                    <div
                      class="animate-pulse rounded-lg bg-surface-sunken"
                      style={imageBoxStyle(view.width, view.height)}
                    ></div>
                  {/if}
                  <p
                    class="mt-1 text-right text-[10px] {item.isOwn
                      ? 'text-accent-content/70'
                      : 'text-content-muted'}"
                  >
                    {formatTime(item.timestampMs)}
                  </p>
                </div>
              </div>
            {:else if view.render === "mediaFile"}
              <!--
                `m.file`/`m.audio`/`m.video`: an informative row (filename,
                size, kind), no playback or download action yet — see
                `.superpowers/sdd/2026-08-13-m0-spine/media-report.md` for
                what a follow-up would need to add either.
              -->
              <div class="flex py-1 {item.isOwn ? 'justify-end' : 'justify-start'}">
                <div
                  class="min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  <div class="selectable flex items-center gap-2 text-sm">
                    <span
                      class="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-xs font-semibold {item.isOwn
                        ? 'bg-accent-content/15 text-accent-content'
                        : 'bg-surface text-content-muted'}"
                      aria-hidden="true"
                    >
                      {view.label[0]}
                    </span>
                    <span class="min-w-0">
                      <span class="block truncate font-medium">{view.filename}</span>
                      <span
                        class="block text-xs {item.isOwn
                          ? 'text-accent-content/70'
                          : 'text-content-muted'}"
                      >
                        {view.label}{view.size != null ? ` · ${formatFileSize(view.size)}` : ""}
                      </span>
                    </span>
                  </div>
                  <p
                    class="mt-1 text-right text-[10px] {item.isOwn
                      ? 'text-accent-content/70'
                      : 'text-content-muted'}"
                  >
                    {formatTime(item.timestampMs)}
                  </p>
                </div>
              </div>
            {:else if view.render === "system"}
              <!--
                Membership lines, room creation, encryption enabled, room
                replaced. Centred and muted so a history full of them reads
                as a quiet log rather than as messages.
              -->
              <div class="flex justify-center py-1.5">
                <span class="text-xs text-content-muted">{view.text}</span>
              </div>
            {:else if view.render === "placeholder"}
              <!--
                Anything the reader must be told about but this build can't
                render fully yet: undecryptable events on a fresh device
                (the common case in a real encrypted room), redactions,
                media, stickers, polls, custom suite events. Never the bare
                empty bubble that rendering nothing used to produce — see
                `timelineItemView.ts`.
              -->
              <div class="flex justify-center py-1.5">
                <span class="text-xs text-content-muted italic">{view.text}</span>
              </div>
            {/if}
            <!-- view.render === "none": deliberately silent, see `timelineItemView.ts`. -->
          {/if}
        {/if}
      {/snippet}
    </VList>
  {/if}
</div>

<style>
  /*
   * Typography for `{@html item.formattedBody}` content (see this file's
   * top-of-script doc comment for the sanitisation guarantees that make
   * rendering it safe at all). `:global(...)` throughout, deliberately: the
   * elements below come from raw injected HTML, not markup Svelte compiles
   * and scopes itself, so a plain (non-global) selector would never match
   * them.
   *
   * Colors are `--color-*` tokens from `src/app.css`, per the same
   * no-hardcoded-colors rule the rest of the app follows — except that they
   * are read through `currentColor` (the bubble's own already-token-driven
   * text color, `text-content`/`text-accent-content`/`text-content-muted`
   * set in the markup above) rather than referenced directly, since this
   * block must look right against *both* an own-message bubble (accent
   * background) and a peer bubble (surface background) without knowing
   * which one it's in.
   *
   * Long content must never widen the bubble (`max-w-[70%]` on its
   * container, in the markup above — plus `min-w-0` there too: a flex
   * item's default automatic minimum size is its content's min-content
   * size, which silently overrides an explicit `max-width` unless the item
   * opts out with `min-width: 0`, so without it a wide-enough descendant
   * — a `<table>` with many columns, say — reopens exactly the blowout
   * this block exists to prevent regardless of what's set here).
   * `overflow-wrap: anywhere` handles long unbroken words/URLs by
   * wrapping; `overflow-x: auto` + `max-width: 100%` on this container
   * (and again, more narrowly, on `table` and `pre` below, since those are
   * the two elements whose *natural* rendering is to refuse to wrap at
   * all — a wide table's columns and `pre`'s preformatted text) is what
   * turns "wide content" into "scrolls within the bubble" instead of
   * "widens the bubble, and with it the whole window"
   * (`document.documentElement.scrollWidth`, measured in review, grew to
   * 4700px against a 1905px viewport from a single 300-`<td>` message
   * before this fix). `core::timeline::harden_formatted_body`'s lowered
   * `max_depth` bounds nested `<ul>`/`<ol>`/`<blockquote>` for the same
   * reason from the other side — capping how deep the indentation in
   * `ul`/`ol`/`blockquote` below can compound in the first place, rather
   * than trying to cap already-rendered CSS padding after the fact.
   */
  .message-html {
    overflow-wrap: anywhere;
    overflow-x: auto;
    max-width: 100%;
  }

  :global(.message-html table) {
    display: block;
    overflow-x: auto;
    max-width: 100%;
  }

  :global(.message-html p) {
    margin: 0;
  }

  :global(.message-html > * + *) {
    margin-top: 0.4em;
  }

  :global(.message-html ul),
  :global(.message-html ol) {
    margin: 0.4em 0;
    padding-left: 1.25em;
  }

  :global(.message-html li) {
    margin: 0.15em 0;
  }

  :global(.message-html blockquote) {
    margin: 0.4em 0;
    padding-left: 0.6em;
    border-left: 2px solid color-mix(in srgb, currentColor 35%, transparent);
    color: color-mix(in srgb, currentColor 75%, transparent);
  }

  :global(.message-html a) {
    color: inherit;
    text-decoration: underline;
    text-decoration-color: color-mix(in srgb, currentColor 50%, transparent);
    overflow-wrap: anywhere;
  }

  :global(.message-html strong) {
    font-weight: 600;
  }

  :global(.message-html em) {
    font-style: italic;
  }

  :global(.message-html code) {
    font-family: var(--font-mono);
    font-size: 0.85em;
    background: color-mix(in srgb, currentColor 12%, transparent);
    border-radius: 0.25em;
    padding: 0.1em 0.3em;
    overflow-wrap: anywhere;
  }

  :global(.message-html pre) {
    margin: 0.4em 0;
    padding: 0.5em 0.6em;
    border-radius: 0.5em;
    background: color-mix(in srgb, currentColor 10%, transparent);
    overflow-x: auto;
    max-width: 100%;
  }

  :global(.message-html pre code) {
    background: none;
    padding: 0;
    overflow-wrap: normal;
    white-space: pre;
  }
</style>
