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
  // Replies/reactions (M2, `docs/matrix-events.md` Table A) are interactive
  // as of this pass — editing is still a follow-up. `replyQuote`/
  // `reactionsRow`/`messageActions` below are shared snippets, reused across
  // every bubble-shaped render kind (`bubble`/`image`/`mediaFile`) rather
  // than duplicated per branch. Several things worth calling out:
  //   - A reply's parent (`item.replyTo`) may not have loaded —
  //     `replyQuoteView` (`timelineItemView.ts`) reduces the SDK's four
  //     `TimelineDetails` states to two outcomes: `available` (something to
  //     quote) or not (render "Original message unavailable", never an
  //     empty quote or a spinner — this build never calls
  //     `Timeline::fetch_details_for_event`, so an unavailable parent will
  //     not resolve itself). A `Ready` parent can *also* have nothing to
  //     quote (redacted, a sticker, a poll, undecryptable, ...) — that
  //     renders `quote.label`, the same short classification text
  //     `core::timeline::reply_parent_label` computes on the Rust side,
  //     rather than a bare sender name with no explanation.
  //   - A reply excerpt and a reaction key are both sender-controlled
  //     strings. The excerpt is already truncated in the core
  //     (`core::timeline::REPLY_EXCERPT_MAX_CHARS`) before it ever crosses
  //     IPC; a reaction key is not (its exact bytes matter for aggregation),
  //     so `displayReactionKey` caps it for *display* only. Both still carry
  //     `break-words` here regardless — truncation bounds the length, not
  //     whether a long space-free run within that bound can still widen the
  //     bubble (the exact class of bug `.message-html`'s own
  //     `overflow-wrap: anywhere` exists to prevent; see that block's doc
  //     comment for the 4700px regression this guards against).
  //   - Clicking an existing reaction chip, one of the quick-reaction
  //     buttons in `messageActions`, or "Reply" never mutates
  //     `timelineStore.items` itself — see this file's "Never optimistically
  //     appends" note above, which applies identically here: `Timeline::
  //     toggle_reaction`/`send_reply` add their own local echo, which
  //     arrives back through the same diff stream this component only ever
  //     reads. Adding a second, local update here would double-render,
  //     exactly the bug that note already guards `Composer` against.
  //   - Both controls are gated by `canReplyOrReact` (`timelineItemView.ts`):
  //     an item only has a real Matrix event id — which `Timeline::
  //     toggle_reaction`/`send_reply` both require — once the server has
  //     echoed it back, never while it's still a local echo
  //     (`sendState: "notSentYet"`) or failed to send.
  //   - The pending reply target lives in `replyTargetStore`
  //     (`$lib/stores/replyTarget.svelte.ts`), keyed by `roomId` (a prop,
  //     not read off `timelineStore` — see that store's doc comment for why
  //     it must be scoped per room exactly like `Composer`'s drafts, and
  //     what leaking it across a room switch would cost).
  //   - `messageActions`' buttons are revealed on hover *or* focus-within
  //     (`focus-within:opacity-100`, not `hover:opacity-100` alone) — CSS
  //     opacity, not `display: none`, so they stay in the tab order and
  //     reachable by keyboard even while visually faded out; focusing one
  //     (e.g. by tabbing through the timeline) reveals the whole row the
  //     same way hovering the bubble does.
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
  import { replyTargetStore } from "$lib/stores/replyTarget.svelte";
  import { roomsStore } from "$lib/stores/rooms.svelte";
  import {
    canReplyOrReact,
    displayReactionKey,
    replyQuoteView,
    viewFor,
  } from "./timelineItemView";
  import { groupTimelineItems, type TimelineDisplayRow } from "./timelineGrouping";
  import { handleMessageBodyAuxClick, handleMessageBodyClick } from "./messageLinks";
  import { createMediaCache } from "$lib/stores/mediaCache.svelte";
  import type { TimelineItem } from "$lib/ipc";

  /**
   * The room this pane shows — needed only to scope `replyTargetStore`'s
   * writes (see this file's top-of-script doc comment). `+page.svelte`
   * already remounts this whole component on a room switch (`{#key
   * roomsStore.selectedId}`), so this is always the room whose messages are
   * actually on screen — never stale the way a value read off a
   * non-remounted component (`Composer`) would have to guard against.
   */
  let { roomId }: { roomId: string } = $props();

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
      // `roomId` is a stable prop for this component's whole lifetime — this
      // pane is remounted (`{#key roomsStore.selectedId}` in `+page.svelte`)
      // on every room switch, unlike `Composer.svelte`, so there is no
      // "reader switched rooms mid-call" case to snapshot against here. It's
      // still passed explicitly, not left for the core to infer from
      // whatever's focused, so a stale call from a just-unmounted instance
      // (its promise still resolving after the reader switched rooms) names
      // the room it was actually issued for and gets rejected as
      // `roomChanged` rather than paginating whatever room is focused now.
      reachedStart = await timelineStore.paginateBack(roomId, PAGE_SIZE);
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

  /**
   * The fixed set of one-click reactions offered on every eligible message.
   * Small and fixed on purpose — the task brief calls for exactly this, not
   * a full emoji picker (out of scope for this pass): clicking an existing
   * chip already covers reacting with anything someone else in the room
   * already used.
   */
  const QUICK_REACTIONS = ["👍", "❤️", "😂", "🎉", "😮", "🙏"];

  /**
   * Toggles `key` as a reaction on `eventId`. Never mutates
   * `timelineStore.items` itself — see this file's top-of-script doc
   * comment for why the local echo arriving back through the diff stream is
   * what actually updates the chip, not this function.
   */
  async function handleToggleReaction(eventId: string, key: string): Promise<void> {
    try {
      // Same reasoning as `requestOlderMessages`'s `roomId` argument: stable
      // for this component's lifetime, passed explicitly so a stale toggle
      // from a just-unmounted instance is rejected rather than landing
      // against whatever room is focused when it finally resolves.
      await timelineStore.toggleReaction(roomId, eventId, key);
    } catch (err) {
      console.error("failed to toggle reaction", err);
    }
  }

  /**
   * Starts (or replaces) the pending reply target for `roomId`. Scoped per
   * room in `replyTargetStore` — see that store's doc comment and this
   * file's top-of-script comment for why a reply target must never be
   * allowed to follow the reader across a room switch the way a stale
   * composer draft once did.
   */
  function startReply(item: TimelineItem): void {
    replyTargetStore.set(roomId, replyTargetStore.fromItem(item));
  }

  /**
   * Selects `targetRoomId` in-app, for a matrix.to/`matrix:` link inside a
   * rendered message body that addresses a room the account is already in —
   * see `messageLinks.ts`'s top-of-module doc comment for the full routing
   * decision. Bound explicitly at the `onclick`/`onauxclick` call sites
   * below, rather than relied on as a default: `handleMessageBodyClick`/
   * `handleMessageBodyAuxClick` default `selectRoom` to an inert no-op
   * precisely so importing `messageLinks.ts` never has to construct the real
   * `roomsStore` singleton (see that file's doc comment) — this component is
   * the one place that's safe, since it's a real Svelte component, not a
   * unit-tested plain module.
   */
  function selectKnownRoom(targetRoomId: string): void {
    roomsStore.select(targetRoomId);
  }

  /** Every room id the account is currently in, for `resolveInAppRoomId`'s
   * membership check. See {@link selectKnownRoom}'s doc comment. */
  function knownRoomIds(): readonly string[] {
    return roomsStore.rooms.map((room) => room.id);
  }
</script>

{#snippet replyQuote(item: TimelineItem)}
  {@const quote = replyQuoteView(item.replyTo)}
  {#if quote}
    <div
      class="mb-1 rounded-md border-l-2 px-2 py-1 text-xs {item.isOwn
        ? 'border-accent-content/40 text-accent-content/85'
        : 'border-border text-content-muted'}"
    >
      {#if quote.available}
        <!--
          `truncate` alone here (no `break-words`): `truncate` is
          `white-space: nowrap` + `text-overflow: ellipsis` + `overflow:
          hidden`, which never wraps in the first place, so `break-words`
          (a wrapping rule) was dead weight on this line — see this file's
          top-of-script doc comment for why `break-words` *does* matter,
          genuinely, on the two lines below that actually allow wrapping.
        -->
        <p class="truncate font-medium">{quote.sender}</p>
        {#if quote.excerpt}
          <!-- `quote.excerpt` is already truncated in the core
               (`core::timeline::REPLY_EXCERPT_MAX_CHARS`) — `break-words`
               here guards against a long space-free run within that bound,
               not the length itself. See this file's top-of-script doc
               comment. -->
          <p class="line-clamp-2 break-words">{quote.excerpt}</p>
        {:else if quote.label}
          <!-- The parent loaded but had nothing to quote (redacted, a
               sticker, a poll, undecryptable, ...) — `quote.label` is the
               same short classification text `core::timeline::
               reply_parent_label` computes for it, so this reads with the
               vocabulary `viewFor`'s own placeholders already use. Fixes
               the review finding that this used to render as a bare sender
               name with no indication why. -->
          <p class="italic break-words">{quote.label}</p>
        {/if}
      {:else}
        <p class="italic break-words">Original message unavailable</p>
      {/if}
    </div>
  {/if}
{/snippet}

{#snippet reactionsRow(item: TimelineItem)}
  {#if item.reactions.length > 0}
    <div class="mt-1 flex flex-wrap gap-1">
      {#each item.reactions as reaction (reaction.key)}
        {@const interactive = canReplyOrReact(item)}
        <!--
          `displayReactionKey` caps a reaction key's rendered length (a key
          is arbitrary sender-controlled text, not necessarily one emoji);
          `break-words` guards the chip itself against a long run within
          that cap, same reasoning as the reply excerpt above. `byMe` gets a
          visually distinct style so a reader can tell at a glance which
          chips they've already added to. A real `<button>`, not a `<span>`
          with a click handler, so it's keyboard-operable with an accessible
          name on its own — `aria-pressed` mirrors `byMe` for the same
          reason a toggle button conventionally exposes its own state.
          Clicking never mutates `item.reactions` itself; see this file's
          top-of-script doc comment.
        -->
        <button
          type="button"
          disabled={!interactive}
          onclick={() => handleToggleReaction(item.id, reaction.key)}
          aria-pressed={reaction.byMe}
          aria-label={`${displayReactionKey(reaction.key)}, ${reaction.count} ${reaction.count === 1 ? "reaction" : "reactions"}${reaction.byMe ? ", including yours" : ""} — toggle`}
          class="rounded-full border px-2 py-0.5 text-xs break-words transition-colors disabled:cursor-not-allowed disabled:opacity-60 {reaction.byMe
            ? 'border-accent bg-accent/20 text-accent font-medium hover:bg-accent/30'
            : 'border-border/70 bg-surface-sunken text-content-muted hover:bg-surface'}"
        >
          {displayReactionKey(reaction.key)} {reaction.count}
        </button>
      {/each}
    </div>
  {/if}
{/snippet}

{#snippet messageActions(item: TimelineItem)}
  {#if canReplyOrReact(item)}
    <!--
      Chrome, not content — no `.selectable` here (see this file's
      top-of-script comment on user-select discipline). Faded out until the
      bubble is hovered *or* one of these buttons has focus
      (`focus-within`, not `hover` alone), so tabbing through the timeline
      still reaches every button — opacity, never `display: none`, keeps
      them in the tab order the whole time. `flex-wrap` so six quick
      reactions plus "Reply" never force the bubble wider than its own
      `max-w-[70%]` cap.
    -->
    <div
      class="mt-1 flex flex-wrap items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100"
    >
      <button
        type="button"
        onclick={() => startReply(item)}
        class="rounded px-1.5 py-0.5 text-[11px] font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        Reply
      </button>
      {#each QUICK_REACTIONS as emoji (emoji)}
        <button
          type="button"
          onclick={() => handleToggleReaction(item.id, emoji)}
          aria-label={`React with ${emoji}`}
          class="rounded px-1 py-0.5 text-xs transition-colors hover:bg-surface-sunken"
        >
          {emoji}
        </button>
      {/each}
    </div>
  {/if}
{/snippet}

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
                  class="group min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  {@render replyQuote(item)}
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
                      onclick={(e: MouseEvent) =>
                        handleMessageBodyClick(e, undefined, selectKnownRoom, knownRoomIds)}
                      onauxclick={(e: MouseEvent) =>
                        handleMessageBodyAuxClick(e, undefined, selectKnownRoom, knownRoomIds)}
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
                  {@render reactionsRow(item)}
                  {@render messageActions(item)}
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
                      {#if item.edited}<span class="italic">edited</span> · {/if}{formatTime(
                        item.timestampMs,
                      )}
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
                  class="group min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  {@render replyQuote(item)}
                  {#if failed}
                    <!-- Never a broken-image icon: any failure — nothing
                         renderable, a rejected fetch, or the <img> itself
                         failing to decode — lands here. -->
                    <p class="selectable text-sm text-content-muted italic break-words">{view.alt}</p>
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
                  {@render reactionsRow(item)}
                  {@render messageActions(item)}
                  <p
                    class="mt-1 text-right text-[10px] {item.isOwn
                      ? 'text-accent-content/70'
                      : 'text-content-muted'}"
                  >
                    {#if item.edited}<span class="italic">edited</span> · {/if}{formatTime(
                      item.timestampMs,
                    )}
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
                  class="group min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                    ? 'bg-accent text-accent-content'
                    : 'border border-border bg-surface-raised text-content'}"
                >
                  {#if !item.isOwn}
                    <p class="mb-0.5 text-xs font-medium text-content-muted">
                      {item.senderDisplayName ?? item.sender ?? "Unknown"}
                    </p>
                  {/if}
                  {@render replyQuote(item)}
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
                  {@render reactionsRow(item)}
                  {@render messageActions(item)}
                  <p
                    class="mt-1 text-right text-[10px] {item.isOwn
                      ? 'text-accent-content/70'
                      : 'text-content-muted'}"
                  >
                    {#if item.edited}<span class="italic">edited</span> · {/if}{formatTime(
                      item.timestampMs,
                    )}
                  </p>
                </div>
              </div>
            {:else if view.render === "customEvent"}
              <!--
                A `kind: "customMessage"` item — Kaambaan cards/runs/
                permission requests/station status once those schemas land
                (`docs/matrix-events.md` §G), the demo renderer until then.
                `view.view` is the whole `resolveCustomEvent` outcome
                (`$lib/components/customEvents.ts`) — this block only
                switches on its `status`, never decides anything itself.

                Every value below is plain-text interpolation (`{...}`),
                never `{@html}` — `content` is arbitrary JSON from anyone
                who can send to the room, and a renderer's fields are
                already bounded in count/length by `resolveCustomEvent`
                before they ever reach here. `break-words` + the bubble's
                own `max-w-[70%]`/`min-w-0` guard against a long unbroken
                value or label widening the bubble, the same discipline
                every other sender-controlled surface in this file follows.
              -->
              {#if view.view.status === "placeholder"}
                <div class="flex justify-center py-1.5">
                  <span class="text-xs text-content-muted italic">{view.view.text}</span>
                </div>
              {:else}
                <div class="flex py-1 {item.isOwn ? 'justify-end' : 'justify-start'}">
                  <div
                    class="group min-w-0 max-w-[70%] rounded-2xl px-3 py-2 {item.isOwn
                      ? 'bg-accent text-accent-content'
                      : 'border border-border bg-surface-raised text-content'}"
                  >
                    {#if !item.isOwn}
                      <p class="mb-0.5 text-xs font-medium text-content-muted">
                        {item.senderDisplayName ?? item.sender ?? "Unknown"}
                      </p>
                    {/if}
                    <p
                      class="mb-1 text-[10px] font-semibold tracking-wide uppercase {item.isOwn
                        ? 'text-accent-content/70'
                        : 'text-content-muted'}"
                    >
                      Custom event
                    </p>
                    {#if view.view.status === "rendered"}
                      <div class="selectable space-y-0.5 text-sm">
                        <!--
                          Keyed by index, not `field.label` — a renderer's
                          fields are trusted (registered application code,
                          not an array read straight off the payload), but a
                          duplicate label is still possible and shouldn't be
                          able to confuse Svelte's keyed reconciliation.
                        -->
                        {#each view.view.fields as field, i (i)}
                          <p class="break-words">
                            <span class="font-medium">{field.label}:</span>
                            {field.value}
                          </p>
                        {/each}
                      </div>
                      {#if view.view.newerVersion}
                        <p
                          class="mt-1 text-[10px] italic {item.isOwn
                            ? 'text-accent-content/70'
                            : 'text-content-muted'}"
                        >
                          Shown from a newer version of this event
                        </p>
                      {/if}
                    {:else}
                      <!-- status === "fallbackBody": the plain-text
                           `content.body` Matrix convention puts on every
                           suite custom event, for a type this build has no
                           renderer for. -->
                      <p
                        class="selectable text-sm whitespace-pre-wrap break-words {item.isOwn
                          ? ''
                          : 'text-content-muted'}"
                      >
                        {view.view.text}
                      </p>
                    {/if}
                    {@render reactionsRow(item)}
                    {@render messageActions(item)}
                    <p
                      class="mt-1 text-right text-[10px] {item.isOwn
                        ? 'text-accent-content/70'
                        : 'text-content-muted'}"
                    >
                      {formatTime(item.timestampMs)}
                    </p>
                  </div>
                </div>
              {/if}
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
