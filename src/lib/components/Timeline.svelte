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
  // The reading surface (spec §6.3). Peer and own messages are deliberately
  // *asymmetric*, and the asymmetry is the design, not an oversight: these
  // rooms are agents, and what they send is long-form prose — plans,
  // findings, reports — while what the operator sends is a command. So a
  // peer message is an editorial block (no bubble, no border, no fill,
  // left-aligned, serif `--text-body` at a `68ch` measure, a mono sender
  // line above it) and an own message keeps a tight right-aligned bubble
  // (`--color-accent-soft` ground, `--color-content` text, 6px radius, sans
  // `--text-body-own`, `52ch`). *You type, they write.*
  //   - All three message-shaped render kinds (`bubble`/`image`/
  //     `mediaFile`) go through the single `messageBlock` snippet below,
  //     which owns that whole wrapper — the sender/meta line, the reply
  //     quote, reactions, actions, the seen marker and the trailing own
  //     meta line — and takes the branch's distinct middle as a child
  //     snippet. The asymmetry above is therefore expressed exactly once.
  //     (`view.render === "customEvent"` still carries its own wrapper; it
  //     is being replaced wholesale by the dispatch card in spec §7.)
  //   - The `68ch`/`52ch` measures are set on the block, not on the prose
  //     inside it, for two reasons: the reactions and actions rows live in
  //     the same block and would otherwise be unbounded, and the block is
  //     the flex item that the layout-blowout guard has to sit on anyway
  //     (see the comment on the style block at the foot of this file:
  //     `min-w-0` plus an explicit `max-width`, both, or a wide `<table>`
  //     regrows the document). Note for anyone editing these comments: a
  //     literal `style` *tag* written anywhere inside this script block —
  //     even in a comment, even in backticks — makes `svelte2tsx` treat
  //     the rest of the file as CSS and fail the whole component with
  //     "`<script>` was left open". Name it in prose, as here.
  //     `ch` resolves against the *element's own* font, so each block also
  //     carries the face its body is set in (`font-serif` for a peer,
  //     `font-sans` for an own message) and the chrome inside it names its
  //     own face explicitly rather than inheriting.
  //   - `continuesRun` (from `timelineGrouping.ts`, see its doc comment)
  //     collapses a sender run: the sender line and the timestamp are
  //     suppressed and the gap above tightens from 16px to 2px, so five
  //     consecutive paragraphs from one agent read as one piece of writing.
  //     Everything else — reply quote, reactions, actions, seen marker —
  //     still renders. Two things deliberately survive a collapse because
  //     they are not "the timestamp" and losing them would lose real
  //     information: the `edited` marker, and an own message's
  //     `sendingFailed`/`notSentYet` state.
  //   - Mono means machine, serif means prose (spec §5.3). System lines,
  //     placeholders, ids and timestamps are mono; message bodies are
  //     serif; chrome is sans. No mono rank is ever italic — `app.css` sets
  //     `font-synthesis: none` and no mono italic is bundled, so an italic
  //     mono string would simply render upright. Italic survives only where
  //     a real italic file exists for the face: serif emotes and `<em>`
  //     inside a message body.
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
  // never widen the message block (the same class of bug the security
  // review found with an unconstrained `<table>`, noted below on
  // `.message-html table`)
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
  // `reactionsRow`/`messageActions` below are shared snippets, rendered
  // once by `messageBlock` (see the reading-surface note above) rather than
  // duplicated per branch. Several things worth calling out:
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
  //     message block (the exact class of bug `.message-html`'s own
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
  //     same way hovering the message block does. The `group` class that
  //     drives that hover lives on the block itself (in `messageBlock`),
  //     which is why it still works now that a peer block has no bubble
  //     around it.
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

  import { tick, type Snippet } from "svelte";
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
  import { shouldMarkRead } from "./readTracking";
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
   * of the message block's own `max-w-[68ch]`/`max-w-[52ch]` — a large
   * image must not blow the block, and with it the whole layout, out past
   * this box. See this file's top-of-script doc comment.
   */
  const IMAGE_MAX_WIDTH = 320;
  const IMAGE_MAX_HEIGHT = 320;
  /** Fallback box shape when the sender's client never reported dimensions. */
  const IMAGE_DEFAULT_ASPECT = 4 / 3;

  const mediaCache = createMediaCache();

  let vlist: VListHandle | undefined = $state();
  let paginating = $state(false);
  let reachedStart = $state(false);
  // `$state`, not a plain variable: the read-tracking effect further down
  // needs to re-run when this flips (e.g. the reader scrolls back down to
  // the bottom with no new message having arrived) — see that effect's doc
  // comment. `handleScroll` writes it on every scroll event; Svelte's
  // primitive `$state` dirty-checks by value, so re-scrolling within the
  // same "at the bottom" state doesn't itself cause extra work.
  let followBottom = $state(true);

  /**
   * The list `VList` actually renders — `timelineStore.items` with
   * consecutive membership changes collapsed. Recomputes whenever the store
   * publishes a new `items` array; see this file's top-of-script doc
   * comment for why that's automatic and why it never disturbs the raw
   * array itself.
   */
  let displayRows = $derived(groupTimelineItems(timelineStore.items));

  /**
   * The id of the last own item in `timelineStore.items` whose `kind` this
   * pass renders as a bubble-shaped row (a plain message, or a rendered
   * custom event) — the one item `seenMarker` below is allowed to annotate
   * with a "Seen"/"Seen by N" line. See [`TimelineItemDto::read_by`]'s doc
   * comment (`core::dto`) for why this is scoped to the reader's *own*
   * latest message and never shown per-message: a reader doesn't need a
   * read receipt on every bubble, only confirmation that the most recent
   * thing they sent has actually been seen.
   */
  let lastOwnMessageId = $derived.by(() => {
    const items = timelineStore.items;
    for (let i = items.length - 1; i >= 0; i -= 1) {
      const candidate = items[i]!;
      if (candidate.isOwn && (candidate.kind === "message" || candidate.kind === "customMessage")) {
        return candidate.id;
      }
    }
    return null;
  });

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

  /**
   * Whether the app window currently has focus — one of `shouldMarkRead`'s
   * (`readTracking.ts`) required conditions, per this task's brief: a
   * background window must never mark a room read just because it's
   * scrolled to the bottom. `document.hasFocus()` (not merely a `blur`
   * having fired) seeds the initial value so a pane that mounts already
   * unfocused — opening the app in the background, say — doesn't start out
   * wrongly `true`.
   */
  let windowFocused = $state(document.hasFocus());

  $effect(() => {
    function updateFocus(): void {
      windowFocused = document.hasFocus();
    }
    window.addEventListener("focus", updateFocus);
    window.addEventListener("blur", updateFocus);
    // Belt and suspenders alongside focus/blur: `visibilitychange` also
    // catches the window being minimized/occluded on platforms where that
    // doesn't reliably fire a DOM `blur` on its own.
    document.addEventListener("visibilitychange", updateFocus);
    return () => {
      window.removeEventListener("focus", updateFocus);
      window.removeEventListener("blur", updateFocus);
      document.removeEventListener("visibilitychange", updateFocus);
    };
  });

  // The id of the newest item this pane has already marked the room read
  // up to — bookkeeping for the effect below, not a value the template
  // reads (same shape as `previousLastId` above). Resets to `null` on every
  // remount (a fresh room, via `+page.svelte`'s `{#key roomsStore.selectedId}`),
  // which is exactly right: a freshly opened room has nothing marked yet.
  let lastMarkedReadId: string | null = null;

  /**
   * Marks the room read exactly when `shouldMarkRead` (`readTracking.ts`)
   * says the reader is genuinely at the live end of the timeline — never
   * merely because this room is the one open. Re-evaluated whenever any of
   * its inputs change: a new item arriving, the reader scrolling to (or
   * away from) the bottom, or the window gaining (or losing) focus.
   *
   * `lastMarkedReadId` is updated *before* the (fire-and-forget) IPC call
   * resolves, not after — otherwise every one of those triggers firing
   * again before the in-flight call returns (e.g. a burst of incoming
   * messages while already at the bottom) would each independently decide
   * "not yet marked" and fire its own redundant `markRead` call. Rolled
   * back on failure so a genuine error (not merely the expected `roomChanged`
   * from a room switch this pane's own remount already makes moot) gets a
   * chance to retry on the next qualifying change instead of being silently
   * treated as done.
   */
  $effect(() => {
    const items = timelineStore.items;
    const lastItemId = items.length > 0 ? items[items.length - 1]!.id : null;
    if (
      !shouldMarkRead({
        followBottom,
        windowFocused,
        lastItemId,
        lastMarkedId: lastMarkedReadId,
      })
    ) {
      return;
    }

    lastMarkedReadId = lastItemId;
    void timelineStore.markRead(roomId).catch((err) => {
      console.error("failed to mark room read", err);
      if (lastMarkedReadId === lastItemId) lastMarkedReadId = null;
    });
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
    <!--
      A 2px rail rather than a filled inset, matching the composer's
      "REPLYING TO" strip (spec §6.4) so the same relationship reads the
      same way in both places. No own/peer colour split any more: the own
      bubble is `--color-accent-soft` with `--color-content` text, not the
      accent fill it used to be, so `--color-content-muted` on
      `--color-border` is legible on either ground — the old
      `accent-content` pair would have been near-invisible on both.
    -->
    <div class="mb-1.5 border-l-2 border-border pl-2 text-content-muted">
      {#if quote.available}
        <!--
          `truncate` alone here (no `break-words`): `truncate` is
          `white-space: nowrap` + `text-overflow: ellipsis` + `overflow:
          hidden`, which never wraps in the first place, so `break-words`
          (a wrapping rule) was dead weight on this line — see this file's
          top-of-script doc comment for why `break-words` *does* matter,
          genuinely, on the two lines below that actually allow wrapping.
        -->
        <p class="truncate font-mono text-label uppercase">{quote.sender}</p>
        {#if quote.excerpt}
          <!-- `quote.excerpt` is already truncated in the core
               (`core::timeline::REPLY_EXCERPT_MAX_CHARS`) — `break-words`
               here guards against a long space-free run within that bound,
               not the length itself. See this file's top-of-script doc
               comment. -->
          <p class="mt-0.5 line-clamp-2 font-serif text-ui break-words">{quote.excerpt}</p>
        {:else if quote.label}
          <!-- The parent loaded but had nothing to quote (redacted, a
               sticker, a poll, undecryptable, ...) — `quote.label` is the
               same short classification text `core::timeline::
               reply_parent_label` computes for it, so this reads with the
               vocabulary `viewFor`'s own placeholders already use. Fixes
               the review finding that this used to render as a bare sender
               name with no indication why. -->
          <!-- Mono, and *not* italic: these two lines share the placeholder
               vocabulary, and no mono italic is bundled — see this file's
               top-of-script doc comment and spec §6.3. -->
          <p class="mt-0.5 font-mono text-meta text-content-faint break-words">{quote.label}</p>
        {/if}
      {:else}
        <p class="font-mono text-meta text-content-faint break-words">
          Original message unavailable
        </p>
      {/if}
    </div>
  {/if}
{/snippet}

{#snippet reactionsRow(item: TimelineItem)}
  {#if item.reactions.length > 0}
    <div class="mt-1.5 flex flex-wrap gap-1 {item.isOwn ? 'justify-end' : ''}">
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

          `font-sans` explicitly: a chip is chrome, and it sits inside a
          message block that sets `font-serif` (peer) on itself so its
          `ch`-based measure resolves in the reading face.
        -->
        <button
          type="button"
          disabled={!interactive}
          onclick={() => handleToggleReaction(item.id, reaction.key)}
          aria-pressed={reaction.byMe}
          aria-label={`${displayReactionKey(reaction.key)}, ${reaction.count} ${reaction.count === 1 ? "reaction" : "reactions"}${reaction.byMe ? ", including yours" : ""} — toggle`}
          class="rounded-full border px-2 py-0.5 font-sans text-ui break-words transition-colors disabled:cursor-not-allowed disabled:opacity-60 {reaction.byMe
            ? 'border-accent bg-accent/15 font-medium text-accent hover:bg-accent/25'
            : 'border-border bg-surface-sunken text-content-muted hover:border-border-strong hover:text-content'}"
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
      reactions plus "Reply" never force the block wider than its own
      `max-w-[68ch]`/`max-w-[52ch]` cap.

      The negative margin pulls the outermost button's own padding back so
      the row aligns optically with the message body's edge rather than
      sitting indented from it — left edge for a peer block, right edge for
      an own bubble, matching which side each is anchored to. `font-sans`
      for the same reason the reaction chips carry it: this is chrome
      inside a serif block.
    -->
    <div
      class="mt-1 flex flex-wrap items-center gap-0.5 font-sans opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100 {item.isOwn
        ? '-mr-1.5 justify-end'
        : '-ml-1.5'}"
    >
      <button
        type="button"
        onclick={() => startReply(item)}
        class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        Reply
      </button>
      {#each QUICK_REACTIONS as emoji (emoji)}
        <button
          type="button"
          onclick={() => handleToggleReaction(item.id, emoji)}
          aria-label={`React with ${emoji}`}
          class="rounded px-1 py-0.5 text-ui transition-colors hover:bg-surface-sunken"
        >
          {emoji}
        </button>
      {/each}
    </div>
  {/if}
{/snippet}

{#snippet seenMarker(item: TimelineItem)}
  <!--
    "Seen"/"Seen by N" — the reader's own latest message only, per
    `TimelineItemDto::read_by`'s doc comment (`core::dto`): no per-message
    avatar stack, and never shown on anyone else's message. `lastOwnMessageId`
    (top-of-script) is what scopes this to "the last own item" rather than
    every item's own `read_by` being rendered — the check here only needs to
    confirm this specific item is that one and that at least one other
    member has actually read it yet.
  -->
  {#if item.id === lastOwnMessageId && item.readBy.length > 0}
    <!--
      `--color-content-muted`, not the `accent-content/70` this used to
      carry: that value only ever made sense against the accent-*filled*
      own bubble it sat on. The own bubble is now `--color-accent-soft`
      with `--color-content` text, and white-at-70% on that ground is
      effectively invisible. Mono, because a read receipt is data.
    -->
    <p class="mt-1 text-right font-mono text-meta text-content-muted">
      {item.readBy.length === 1 ? "Seen" : `Seen by ${item.readBy.length}`}
    </p>
  {/if}
{/snippet}

{#snippet messageBlock(item: TimelineItem, continuesRun: boolean, content: Snippet)}
  <!--
    The single wrapper every message-shaped render kind
    (`bubble`/`image`/`mediaFile`) shares — see this file's top-of-script
    doc comment for the reading-surface design this expresses, and why it
    is one snippet rather than the three verbatim copies it replaces.
    `content` is the branch's distinct middle, nothing else.

    Layout-blowout guard, both halves, on the block itself: an explicit
    `max-width` (`68ch` peer / `52ch` own) *and* `min-w-0`. The block is a
    flex item of the row below, so its automatic minimum size is its
    content's min-content size, which silently overrides `max-width`
    unless it opts out with `min-width: 0` — without both, one wide
    `<table>` regrows the document past the viewport. See the `<style>`
    block's comment for the measured regression.

    The block also carries its own face (`font-serif` peer, `font-sans`
    own) rather than leaving it to a descendant, because `ch` resolves
    against the element's own font: `68ch` has to be 68 characters of the
    face the prose is actually set in, or it is not a reading measure.
    Every piece of chrome nested inside names its face explicitly.
  -->
  <div
    class="flex {continuesRun ? 'pt-0.5' : 'pt-4'} {item.isOwn
      ? 'justify-end'
      : 'justify-start'}"
  >
    <div
      class="group flex min-w-0 flex-col text-content {item.isOwn
        ? 'max-w-[52ch] rounded-md bg-accent-soft px-3 py-2 font-sans text-body-own'
        : 'max-w-[68ch] font-serif text-body'}"
    >
      {#if !item.isOwn && (!continuesRun || item.edited)}
        <!--
          The peer sender line: name and timestamp on one baseline, both
          mono, per spec §6.3. The timestamp sits immediately after the
          name rather than pushed to the block's right edge — the block is
          shrink-to-fit, so a right edge would wander with the message's
          own width and strand the time far from the name it belongs to.
          The name `truncate`s (and so, as on the reply quote above, needs
          no `break-words`: `truncate` never wraps in the first place),
          which is what keeps a long display name from pushing the
          timestamp out of view.

          A collapsed continuation drops this line entirely — except when
          the message was edited, which is real information and not "the
          timestamp": that case renders the marker alone.
        -->
        <p class="mb-1 flex items-baseline gap-2 font-mono text-meta text-content-muted">
          {#if !continuesRun}
            <span class="min-w-0 truncate text-label uppercase">
              {item.senderDisplayName ?? item.sender ?? "Unknown"}
            </span>
            <span class="shrink-0">{formatTime(item.timestampMs)}</span>
          {/if}
          <!-- Mono rank, so no italic — see spec §6.3. -->
          {#if item.edited}<span class="shrink-0 text-content-faint">edited</span>{/if}
        </p>
      {/if}
      {@render replyQuote(item)}
      {@render content()}
      {@render reactionsRow(item)}
      {@render messageActions(item)}
      {@render seenMarker(item)}
      {#if item.isOwn}
        {@const failed = item.sendState === "sendingFailed"}
        {@const sending = item.sendState === "notSentYet"}
        <!--
          The own message's trailing meta line. A collapsed continuation
          drops the timestamp, but a send that failed or is still in
          flight is *not* a timestamp — suppressing it would silently hide
          a failed send in the middle of a run, so those two states always
          render. `--color-danger` is the token the palette assigns to a
          failed send (spec §3).
        -->
        {#if failed || sending || item.edited || !continuesRun}
          <p
            class="mt-1 flex items-baseline justify-end gap-2 font-mono text-meta {failed
              ? 'text-danger'
              : 'text-content-muted'}"
          >
            {#if failed}
              Failed to send
            {:else if sending}
              Sending…
            {:else}
              {#if item.edited}<span class="text-content-faint">edited</span>{/if}
              {#if !continuesRun}<span>{formatTime(item.timestampMs)}</span>{/if}
            {/if}
          </p>
        {/if}
      {/if}
    </div>
  </div>
{/snippet}

<div class="min-h-0 flex-1">
  {#if timelineStore.items.length === 0}
    <div class="flex h-full items-center justify-center">
      <p class="text-ui text-content-muted">No messages yet</p>
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
            differently from an ungrouped one — including the wrap guard,
            whose reasoning is spelled out there.
          -->
          <div class="flex justify-center py-2">
            <span
              class="min-w-0 max-w-[68ch] text-center font-mono text-meta break-words text-content-faint"
              >{row.text}</span
            >
          </div>
        {:else}
          {@const item = row.item}
          {@const continuesRun = row.continuesRun}
          {#if item.kind === "dateDivider"}
            <!--
              A hairline with the date sitting *on* it, not a pill (spec
              §6.3): the rule runs the full width behind an absolutely
              positioned label that paints `--color-surface` over the
              segment it occupies. `role="separator"` stays on the outer
              element; only the rule itself is `aria-hidden`, so the date
              is still announced.
            -->
            <div class="relative flex items-center justify-center py-5" role="separator">
              <span class="absolute inset-x-0 top-1/2 h-px bg-border" aria-hidden="true"></span>
              <span
                class="relative bg-surface px-3 font-mono text-label uppercase text-content-muted"
              >
                {formatDate(item.timestampMs)}
              </span>
            </div>
          {:else}
            {@const view = viewFor(item)}
            {#if view.render === "bubble"}
              {#snippet bubbleContent()}
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

                    No face or size class here on purpose: the enclosing
                    message block already sets serif `--text-body` (peer) or
                    sans `--text-body-own` (own), and `.message-html`'s own
                    rules read through `currentColor` and `em` so they suit
                    either.
                  -->
                  <!-- svelte-ignore a11y_click_events_have_key_events -->
                  <!-- svelte-ignore a11y_no_static_element_interactions -->
                  <div
                    class="message-html selectable {view.muted && !item.isOwn
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
                    class="selectable whitespace-pre-wrap break-words {view.muted && !item.isOwn
                      ? 'text-content-muted'
                      : ''}"
                  >
                    {item.body}
                  </p>
                {/if}
              {/snippet}
              {@render messageBlock(item, continuesRun, bubbleContent)}
            {:else if view.render === "emote"}
              <!--
                Centred, serif *italic* — the one italic that survives in
                this file alongside `<em>` inside a message body, because
                the serif's italic is genuinely bundled (spec §6.3) and an
                emote is prose about the sender rather than a mono rank.
              -->
              <div class="flex justify-center px-4 py-2">
                <!-- `break-words` + `min-w-0`, same discipline as every
                     other sender-controlled string in this file: both the
                     display name and the body are arbitrary text, and a
                     long space-free run in either would otherwise override
                     this flex item's own `max-width`. -->
                <p
                  class="selectable min-w-0 max-w-[68ch] text-center font-serif text-body break-words text-content-muted italic"
                >
                  {item.senderDisplayName ?? item.sender ?? "Someone"}
                  {item.body}
                </p>
              </div>
            {:else if view.render === "image"}
              {@const src = mediaCache.get(item.id)}
              {@const failed = mediaCache.hasFailed(item.id)}
              {#snippet imageContent()}
                {#if failed}
                  <!-- Never a broken-image icon: any failure — nothing
                       renderable, a rejected fetch, or the <img> itself
                       failing to decode — lands here. Mono and unitalicised
                       like every other placeholder rank (spec §6.3), since
                       what this line is really saying is "there is an image
                       here that could not be shown". -->
                  <p class="selectable font-mono text-meta text-content-faint break-words">
                    {view.alt}
                  </p>
                {:else if src}
                  <!--
                    Content, not decoration — real `alt` text from the
                    message (unlike the room list's decorative avatars,
                    this is never `aria-hidden`).
                  -->
                  <img
                    {src}
                    alt={view.alt}
                    class="block rounded-md object-cover"
                    style={imageBoxStyle(view.width, view.height)}
                    onerror={() => mediaCache.markFailed(item.id)}
                  />
                {:else}
                  <!-- Still fetching: reserves the identical box the
                       loaded <img> above will occupy — see this file's
                       top-of-script doc comment. -->
                  <div
                    class="animate-pulse rounded-md bg-surface-sunken"
                    style={imageBoxStyle(view.width, view.height)}
                  ></div>
                {/if}
              {/snippet}
              {@render messageBlock(item, continuesRun, imageContent)}
            {:else if view.render === "mediaFile"}
              <!--
                `m.file`/`m.audio`/`m.video`: an informative row (filename,
                size, kind), no playback or download action yet — see
                `.superpowers/sdd/2026-08-13-m0-spine/media-report.md` for
                what a follow-up would need to add either.

                Filename in sans, the kind/size line in mono (spec §6.3) —
                both named explicitly rather than inherited, since a peer
                block sets serif on itself for its `ch` measure. The icon
                and the sub-label both used to be `accent-content`-derived,
                which only worked against the accent-filled own bubble that
                no longer exists.
              -->
              {#snippet mediaFileContent()}
                <div class="selectable flex items-center gap-2">
                  <span
                    class="flex h-8 w-8 shrink-0 items-center justify-center rounded-md font-mono text-ui font-medium text-content-muted {item.isOwn
                      ? 'bg-surface'
                      : 'bg-surface-sunken'}"
                    aria-hidden="true"
                  >
                    {view.label[0]}
                  </span>
                  <span class="min-w-0">
                    <!-- `truncate`, so no `break-words` — same reasoning as
                         the reply quote's sender line above. -->
                    <span class="block truncate font-sans text-ui font-medium">
                      {view.filename}
                    </span>
                    <span class="mt-0.5 block font-mono text-meta text-content-muted">
                      {view.label}{view.size != null ? ` · ${formatFileSize(view.size)}` : ""}
                    </span>
                  </span>
                </div>
              {/snippet}
              {@render messageBlock(item, continuesRun, mediaFileContent)}
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
                    {@render seenMarker(item)}
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
                replaced. Centred, mono and faint so a history full of them
                reads as a quiet machine log rather than as messages — mono
                means machine, spec §5.3.

                `min-w-0` + `max-w` + `break-words`, the same three-part
                guard every other sender-controlled string in this file
                carries: `view.text` is built from `attributedName`, which
                is the sender's own *unbounded* display name, and this line
                previously had none of them. Measured with the 4700px-table
                harness that the style block's comment describes: a single
                5000-character display name pushed this row's scroll width
                to 16515px against a 1563px column. `break-words` alone is
                not enough — `overflow-wrap: break-word` does not reduce an
                element's min-content size, so a flex item's automatic
                minimum size still holds the row open until `min-w-0` lets
                it shrink.
              -->
              <div class="flex justify-center py-2">
                <span
                  class="min-w-0 max-w-[68ch] text-center font-mono text-meta break-words text-content-faint"
                  >{view.text}</span
                >
              </div>
            {:else if view.render === "placeholder"}
              <!--
                Anything the reader must be told about but this build can't
                render fully yet: undecryptable events on a fresh device
                (the common case in a real encrypted room), redactions,
                media, stickers, polls, custom suite events. Never the bare
                empty bubble that rendering nothing used to produce — see
                `timelineItemView.ts`.

                Same rank as a system line, and deliberately *not* italic:
                `font-synthesis: none` plus no bundled mono italic means an
                italic here would render upright anyway, and the mono face
                already marks the line as secondary (spec §6.3).

                Same wrap guard as the system line above, for the same
                reason: `Unsupported message (${msgtype})` and
                `Unsupported event (${detail})` both interpolate a
                sender-controlled string.
              -->
              <div class="flex justify-center py-2">
                <span
                  class="min-w-0 max-w-[68ch] text-center font-mono text-meta break-words text-content-faint"
                  >{view.text}</span
                >
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
   * Neither the face nor the size is set here, on purpose: the enclosing
   * message block (`messageBlock` in the markup above) already sets serif
   * `--text-body` for a peer and sans `--text-body-own` for an own
   * message, and everything below is expressed in `em` so it scales with
   * whichever it inherited. `code`/`pre` are the deliberate exception —
   * they force `--font-mono` regardless, because a code span in a serif
   * paragraph is still machine text (spec §5.3).
   *
   * Colors are `--color-*` tokens from `src/app.css`, per the same
   * no-hardcoded-colors rule the rest of the app follows — except that they
   * are read through `currentColor` (the block's own already-token-driven
   * text color, `text-content`/`text-content-muted` set in the markup
   * above) rather than referenced directly, since this block must look
   * right against *both* an own-message bubble (`--color-accent-soft`
   * ground) and a bare peer block (the reading surface itself) without
   * knowing which one it's in. Since this pass, both grounds carry
   * `--color-content` text, so the `currentColor` mixes below
   * (`blockquote`'s rule and tint, `code`/`pre`'s ground, `a`'s underline)
   * land on the same foreground in either — a wider margin than the old
   * accent-filled own bubble gave them.
   *
   * Long content must never widen the message block (`max-w-[68ch]` for a
   * peer, `max-w-[52ch]` for an own bubble, on its container in the markup
   * above — plus `min-w-0` there too: a flex item's default automatic
   * minimum size is its content's min-content size, which silently
   * overrides an explicit `max-width` unless the item opts out with
   * `min-width: 0`, so without it a wide-enough descendant — a `<table>`
   * with many columns, say — reopens exactly the blowout this block exists
   * to prevent regardless of what's set here). Losing the peer bubble did
   * not lose that guard: the peer block carries both halves of it in its
   * own right, and it must keep carrying them.
   * `overflow-wrap: anywhere` handles long unbroken words/URLs by
   * wrapping; `overflow-x: auto` + `max-width: 100%` on this container
   * (and again, more narrowly, on `table` and `pre` below, since those are
   * the two elements whose *natural* rendering is to refuse to wrap at
   * all — a wide table's columns and `pre`'s preformatted text) is what
   * turns "wide content" into "scrolls within the block" instead of
   * "widens the block, and with it the whole window"
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

  /* Paragraph rhythm, widened from 0.4em since the bodies became serif at
   * a 1.62 line-height: a multi-paragraph agent report rendered as one
   * undifferentiated slab, which is the opposite of what a reading measure
   * is for. Checked at 15px/1.62 against a real three-paragraph report.
   * This is the *inside* of a message; the gap *between* messages is set
   * on the block in the markup above (16px new block / 2px continuation).
   *
   * `> :not(:first-child)`, not the `> * + *` this used to be, and the
   * change is load-bearing rather than stylistic: `> * + *` has the same
   * specificity as a lone class (one class, two universals — 0,1,0), so
   * `.message-html p { margin: 0 }` above (0,1,1) outranked it and this
   * rule had *never once applied to a paragraph*. Consecutive `<p>`s ran
   * together with no gap at all, at 0.4em and at any other value.
   * `:not(:first-child)` takes its argument's specificity, making this
   * (0,2,0), which wins — while matching exactly the same elements. */
  :global(.message-html > :not(:first-child)) {
    margin-top: 0.9em;
  }

  /* `margin: 0`, not the old `0.4em 0`: a non-zero margin here would give
   * a top-level list a different gap from a paragraph for no reason.
   * Leaving it at zero lets the one paragraph-rhythm rule above own the
   * spacing of every top-level block, and `li`'s own margin below still
   * separates a *nested* list from the text above it.
   *
   * 1.4em, not the old 1.25em: Source Serif 4 has a notably large x-height
   * and correspondingly wide marker glyphs, and at 1.25em the marker sat
   * almost flush against the reading measure's left edge.
   *
   * The explicit `list-style` is a restoration, not decoration: Tailwind's
   * preflight resets `ul`/`ol` to `list-style: none`, so before this an
   * agent's markdown bullet list arrived as unmarked indented lines and
   * lost the one thing that made it a list. `outside`, so the markers hang
   * in the indent and the text edge stays flush with the paragraphs above
   * it. */
  :global(.message-html ul),
  :global(.message-html ol) {
    margin: 0;
    padding-left: 1.4em;
  }

  :global(.message-html ul) {
    list-style: disc outside;
  }

  :global(.message-html ol) {
    list-style: decimal outside;
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
