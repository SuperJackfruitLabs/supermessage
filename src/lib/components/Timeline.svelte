<script lang="ts">
  // The message pane for the focused room. Virtualized with virtua's
  // `VList`, whose `shift` prop keeps back-pagination — which prepends older
  // history at the top of an already-inverted (newest-at-bottom) list — from
  // jerking the scroll position. virtua's docs: "scroll position will be
  // maintained from the end ... when items are added to/removed from start".
  //
  // `shift` is **per update**, never a constant, and the second half of that
  // same doc sentence is why: "recommended to set to false if modifications
  // occur in the middle or end of the list to prevent unexpected behavior".
  // This list is modified at the end constantly — every message sent or
  // received, and every reaction, since the row key carries the reaction
  // count. It shipped hard-coded `true` and the unexpected behaviour was
  // severe: a sent message moved every cached offset down by its own height
  // and virtua unmounted the rows actually on screen, leaving up to 602px of
  // a 911px viewport with no row painted in it until a manual scroll. So the
  // value comes from `shouldShift` (see `timelineGrouping.ts` for the rule
  // and the measurements), derived in the same pass as the rows it describes
  // — `view` below.
  //
  // `getKey` is `row.key`, never the array index — virtua's README calls out
  // index keys as broken specifically when `shift` is on, since prepending
  // renumbers every existing index.
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
  // "falls through the template" case. `core::item_view` classifies each
  // item into a render decision (bubble / emote / system line / placeholder
  // / nothing); this component only switches on that decision, it never
  // inspects the wire `kind` itself, except for `membershipGroup` rows,
  // which `groupTimelineItems` already reduced to display text. See
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
  //   - The asymmetry is one of **register, not geometry** (spec §6.3.0),
  //     and that distinction is the whole of it: sans against serif, tight
  //     against airy, a ground against no ground. Every row — peer block,
  //     own bubble, divider, log line, emote, dispatch card — lays out
  //     inside *one* centred `72ch` reading column, and "right-aligned"
  //     means against that column's right edge, never the viewport's. See
  //     the column wrapper in the markup below for what the viewport-
  //     relative version actually looked like when it was rendered.
  //   - All three message-shaped render kinds (`bubble`/`image`/
  //     `mediaFile`) go through the single `messageBlock` snippet below,
  //     which owns that whole wrapper — the sender/meta line, the reply
  //     quote, reactions, actions, the seen marker and the trailing own
  //     meta line — and takes the branch's distinct middle as a child
  //     snippet. The asymmetry above is therefore expressed exactly once.
  //     (`view.render === "customEvent"` deliberately does *not* go
  //     through it: a dispatch card is left-aligned regardless of sender —
  //     see the dispatch-card note below.)
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
  //     suppressed and the gap above tightens from 32px to 20px, so five
  //     consecutive paragraphs from one agent read as one piece of writing.
  //     Everything else — reply quote, reactions, actions, seen marker —
  //     still renders. Two things deliberately survive a collapse because
  //     they are not "the timestamp" and losing them would lose real
  //     information: the `edited` marker, and an own message's
  //     `sendingFailed`/`notSentYet` state. Those two numbers are picked
  //     against the 13.5px gap between two paragraphs of a *single*
  //     message and must stay looser than it — see `messageBlock`, which
  //     explains what went wrong when the padding was 2px and the rhythm
  //     was really being carried by the hover-only actions row.
  //   - Mono means machine, serif means prose (spec §5.3). System lines,
  //     placeholders, ids and timestamps are mono; message bodies are
  //     serif; chrome is sans. No mono rank is ever italic — `app.css` sets
  //     `font-synthesis: none` and no mono italic is bundled, so an italic
  //     mono string would simply render upright. Italic survives only where
  //     a real italic file exists for the face: serif emotes and `<em>`
  //     inside a message body.
  //
  // The dispatch card (spec §7) is this design's signature element and the
  // one bordered object in the timeline — everything else here is unbordered
  // prose. Every `kind: "customMessage"` item renders as one: Kaambaan
  // cards, runs, station status, and above all permission requests
  // (`docs/matrix-events.md` §G), which are the app's third named
  // differentiator. Four things about it are decisions, not defaults:
  //   - **Left-aligned regardless of `item.isOwn`**, and so deliberately not
  //     built on `messageBlock`. A dispatch is not a remark; it does not
  //     take a side. It occupies the full `68ch` reading measure rather
  //     than shrinking to fit.
  //   - **The event type truncates from the left**, with a leading ellipsis
  //     (`…supermessage.demo.note.v1`), in `displayEventType`
  //     (`core::item_view`) — a pure, unit-tested code-point slice
  //     rather than the `direction: rtl` CSS trick, because the type is a
  //     sender-controlled string and an RTL base direction lets the bidi
  //     algorithm visually reorder a crafted one. See that function's doc
  //     comment.
  //   - **Amber (`--color-signal`) appears here and nowhere else in the
  //     application** (spec §3, §7.1), and only when
  //     `view.view.decision !== null`: the left edge, the ground and the
  //     `AWAITING YOUR DECISION` label all switch together. Amber means the
  //     operator owes someone an answer — never a warning, an error, or the
  //     newer-schema note, which stays a faint mono line.
  //   - **The `placeholder` status is not a card**, just the same quiet
  //     centred system line every other unrenderable item gets. A type we
  //     cannot render is not worth a bordered object.
  // Every label, value, prompt and option label is plain-text `{...}`
  // interpolation. `content` is arbitrary JSON from anyone who can send to
  // the room, and `core::custom_events` has already bounded and validated all
  // of it (`bound_fields`, `bound_decision`) before it ever reaches a host.
  // Nothing read out of a custom payload may be rendered as anything but
  // text — not as markup, not as an `href`, not as an `src`, not as a style.
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
  // **There is no `{@html}` in this file any more, and there is nothing left
  // here for one to be needed for.** A bubble renders `view.blocks` through
  // `RichText.svelte` — data, not markup — and `core::rich` produced those
  // blocks from whichever of the two paths the message had: a sanitised
  // `formatted_body` from a human's client, or the raw markdown an agent
  // wrote. Every string in a block reaches the DOM through Svelte's default
  // `{...}` escaping.
  //
  // The sanitising itself did not go away, it just stopped being this file's
  // problem to justify. `core::timeline::formatted_html_body` still only
  // populates a formatted body for `format: "org.matrix.custom.html"`, still
  // after ruma's Compat allowlist pass, and `harden_formatted_body` still
  // runs the second, narrower pass that exists because ruma-html 0.8.0 has a
  // real bug in its `<a href>`/`<img src>` scheme check — without it,
  // `<a href="javascript:alert(1)">` survives. That pass removes `<img>` and
  // `<mx-reply>` outright and restricts `<a href>` to
  // `http`/`https`/`mailto`/`matrix`.
  //
  // What changed is where the guarantee has to be argued. It lives in the
  // Rust that produced the blocks, and iOS inherits it rather than
  // re-arguing it. If a future change needs to render something new, give it
  // a block — do not reintroduce an escape hatch here.
  //
  // Replies/reactions (M2, `docs/matrix-events.md` Table A) are interactive
  // as of this pass — editing is still a follow-up. `replyQuote`/
  // `reactionsRow`/`messageActions` below are shared snippets, rendered
  // once by `messageBlock` (see the reading-surface note above) rather than
  // duplicated per branch. Several things worth calling out:
  //   - A reply's parent (`item.replyTo`) may not have loaded —
  //     `replyQuoteView` (`core::item_view`) reduces the SDK's four
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
  //   - Both controls are gated by `core::item_view::can_reply_or_react` (`core::item_view`):
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
  import Shimmer from "./ai/Shimmer.svelte";
  import { mediaDownload } from "$lib/ipc";
  import { timelineStore } from "$lib/stores/timeline.svelte";
  import EmojiPicker from "./EmojiPicker.svelte";
  import { QUICK_REACTIONS } from "./emojiPicker";
  import { replyTargetStore } from "$lib/stores/replyTarget.svelte";
  import { roomsStore } from "$lib/stores/rooms.svelte";

  import { groupTimelineItems, shouldShift, type TimelineDisplayRow } from "./timelineGrouping";
  import { shouldRepin, shouldSettleAtBottom } from "./timelineFollow";
  import { LOADING_AFTER_MS, paneState } from "./timelinePane";
  import RichText from "./RichText.svelte";
  import { handleMessageBodyAuxClick, handleMessageBodyClick } from "./messageLinks";
  import { createMediaCache } from "$lib/stores/mediaCache.svelte";
  import { shouldMarkRead } from "./readTracking";
  import type { ReplyQuoteView, TimelineItem } from "$lib/ipc";

  /** The `item` arm of a display row — everything but a membership group. */
  type ItemRow = Extract<TimelineDisplayRow, { type: "item" }>;

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

  /**
   * How much off-screen content `VList` keeps rendered, in pixels.
   *
   * virtua's default is 200px, tuned for lists whose rows are cheap and
   * uniform. Ours are neither: a row can be a one-line log entry or a
   * multi-paragraph markdown answer with a dispatch card above it, and each
   * one has to be measured before the list knows its height. Flung with a
   * trackpad, the viewport outruns that measurement and the reader sees the
   * gap it leaves — the "messages disappear while scrolling" report.
   *
   * virtua names this exact trade in the prop's own docs: "Lower value will
   * give better performance but you can increase to avoid showing blank items
   * in fast scrolling."
   *
   * This is a tuning number, not an invariant, which is why no test pins it.
   * Roughly three viewport-heights of runway at this app's typical row size —
   * enough to cover a fast fling, while still virtualizing a long room.
   */
  const BUFFER_SIZE = 600;

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
   * The row keys `VList` was last given. Deliberately a plain variable and
   * not `$state`: nothing renders it, and making it reactive would mean
   * writing a tracked value from inside a `$derived`, which re-invalidates
   * the derivation that just wrote it.
   */
  let previousRowKeys: string[] = [];

  /**
   * The rows `VList` renders, **and** the `shift` value that describes how
   * they changed — derived together, in one pass, on purpose.
   *
   * They have to arrive at virtua in the same update. `shift` is a claim
   * about the `data` handed over alongside it; computing it in a second
   * `$derived` would let virtua see the new rows while still holding the
   * previous frame's answer for how they got there, which is the same class
   * of mistake as the hard-coded `true` this replaces (see `shouldShift`).
   * Returning one object makes that ordering unrepresentable rather than
   * merely unlikely.
   *
   * `groupTimelineItems` recomputes whenever the store publishes a new
   * `items` array; see this file's top-of-script doc comment for why that's
   * automatic and why it never disturbs the raw array itself.
   */
  let view = $derived.by(() => {
    const rows = groupTimelineItems(timelineStore.items);
    const keys = rows.map((row) => row.key);
    const shift = shouldShift(previousRowKeys, keys);
    previousRowKeys = keys;
    return { rows, shift };
  });

  let displayRows = $derived(view.rows);

  /**
   * Whether the wait for this room has outlasted a flinch.
   *
   * A boolean rather than a live millisecond count on purpose: `paneState`
   * only ever compares against one threshold, and a timer that re-renders the
   * pane sixty times a second to answer a yes/no question is the wrong shape.
   * The timeout is cleared whenever the room answers, so a switch that lands
   * promptly never schedules a second render at all.
   */
  let waitedLongEnough = $state(false);

  $effect(() => {
    // Keyed on the room, not on `loaded`: the threshold now gates the *empty*
    // verdict as well as the loading one, so the clock has to keep running
    // after the room answers. This pane is remounted per room
    // (`{#key roomsStore.selectedId}` in `+page.svelte`), so in practice the
    // timer is armed once and cleared on the way out.
    void roomId;
    waitedLongEnough = false;
    const timer = setTimeout(() => {
      waitedLongEnough = true;
    }, LOADING_AFTER_MS);
    return () => clearTimeout(timer);
  });

  /**
   * What this pane is showing: the room, an honest "nothing here", a quiet
   * wait, or an admission that the wait is long. See `timelinePane.ts` — the
   * distinction it draws is between a room with nothing in it and a room that
   * has not answered yet, which this pane could not previously make.
   */
  let pane = $derived(
    paneState({
      loaded: timelineStore.loaded,
      rowCount: displayRows.length,
      waitingMs: waitedLongEnough ? LOADING_AFTER_MS : 0,
    }),
  );

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
      if (
        candidate.item.isOwn &&
        (candidate.item.kind === "message" || candidate.item.kind === "customMessage")
      ) {
        return candidate.item.id;
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
    const lastId = items.length > 0 ? items[items.length - 1]!.item.id : null;
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
    const lastItemId = items.length > 0 ? items[items.length - 1]!.item.id : null;
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
    // Kept current so `unreadAbove` can tell whether the marker is still
    // above the viewport — the only thing the jump button keys off.
    // `findItemIndex` answers "which row is at this offset", and the offset is
    // exactly what this handler is given.
    if (vlist) firstVisibleIndex = vlist.findItemIndex(offset);
    if (!vlist) return;
    const distanceFromBottom = vlist.getScrollSize() - vlist.getViewportSize() - offset;
    followBottom = distanceFromBottom < BOTTOM_THRESHOLD;

    if (!paginating && !reachedStart && offset < TOP_THRESHOLD) {
      void requestOlderMessages();
    }
  }

  /**
   * The box wrapping `VList`, bound so the tail-following observer below can
   * reach virtua's scroller. Null until the list mounts — which is exactly
   * when that observer should start, and why this is what it depends on.
   */
  let scrollBox = $state<HTMLElement | null>(null);

  /**
   * Keep a reader who is at the tail *at* the tail, whatever moves.
   *
   * Two things push the tail under the fold and neither of them is a scroll,
   * so nothing else in this file notices:
   *
   *  - a sibling panel opens and takes height out of this pane (`LiveTurn`
   *    alone takes up to 33vh);
   *  - the content grows — a message arrives, or virtua measures a row it had
   *    been estimating and finds it taller.
   *
   * The second is why this exists rather than leaving it to the arriving-item
   * effect above. That effect fires one `scrollToIndex` a `tick()` after the
   * item lands, which is *before* virtua has measured it: it aims at an
   * estimate, lands short, and nothing re-aims once the real height is known.
   * A 1721px reply arriving that way left the reader looking at their own sent
   * message with the entire answer below the fold. Re-pinning on the size
   * change is immune to that ordering, because it fires again on every
   * correction. See `shouldRepin` for both measurements.
   *
   * It hangs off `scrollBox` rather than the pane, and the first attempt got
   * that wrong in a way worth recording: the pane also holds the jump-to-unread
   * button and the empty state, so its first child is whichever of those
   * happens to be mounted, and an effect keyed on the pane never re-runs when
   * the list finally appears in place of "Nothing here yet." It measured the
   * wrong box and then never measured again — the live panel opened, the tail
   * slid 393px under the fold exactly as before, and the tests still passed
   * because none of this is what a unit test can see.
   */
  $effect(() => {
    const box = scrollBox;
    if (!box) return;
    // virtua's scroller: the only child of a box that wraps only the list.
    const scroller = box.firstElementChild as HTMLElement | null;
    if (!scroller) return;

    const measure = () => ({ viewport: scroller.clientHeight, content: scroller.scrollHeight });

    // Land at the tail rather than travelling to it. The list opens at the
    // newest message, so the first-load scroll has nowhere to go — where
    // before it started at offset 0 and jumped ~9300px once the rows measured.
    // Seeding `previous` from the same measurement keeps that pin from also
    // reading as the first growth.
    scroller.scrollTop = scroller.scrollHeight;
    let previous = measure();

    // Whether the list has yet been landed on its newest row. False until the
    // first content arrives — see `shouldSettleAtBottom`, which explains why
    // arrival cannot be folded into `shouldRepin`.
    let settled = previous.content > 0;
    if (settled) scroller.scrollTop = scroller.scrollHeight;

    const react = () => {
      const next = measure();
      if (shouldSettleAtBottom(previous, next, settled)) {
        // The rows a room opens with have just landed. Same assignment as the
        // repin below, for the same reason: the bottom is exact.
        scroller.scrollTop = scroller.scrollHeight;
        settled = true;
        previous = measure();
        return;
      }
      if (shouldRepin(previous, next, followBottom)) {
        // Assigning `scrollTop` rather than `scrollToIndex`: the bottom of the
        // scroller is exact and needs no index, no measurement and no guess
        // about which row is last, and the browser clamps it for us. It fires
        // an ordinary scroll event, so `handleScroll` recomputes
        // `followBottom` from the corrected position and agrees.
        scroller.scrollTop = scroller.scrollHeight;
      }
      previous = next;
    };

    // The pane's own height, and the scrolled content's — the two things
    // `shouldRepin` compares. One observer watches both boxes; which one moved
    // is exactly what the predicate works out.
    const observer = new ResizeObserver(react);
    observer.observe(scroller);
    const content = scroller.firstElementChild;
    if (content) observer.observe(content);

    return () => observer.disconnect();
  });

  /**
   * The event id whose reaction picker is open, or null.
   *
   * One at a time, and held here rather than per row: the picker floats over
   * the timeline instead of expanding a row, because `VList` measures row
   * heights and a row that grows mid-scroll is the thing that surface handles
   * worst.
   */
  let pickingReactionFor = $state<string | null>(null);

  /** The event whose media is being saved, so its own button can say so. */
  let savingMedia = $state<string | null>(null);

  /**
   * What to say under the row once a save finished — where it went, or why it
   * did not.
   *
   * In place rather than as a banner: the reader asked about *this* file, and
   * a path only means anything next to the thing it is a path to. This app has
   * no toast surface, and inventing one for a line of text would be a bigger
   * change than the feature it serves.
   */
  let saveNote = $state<{ eventId: string; text: string; failed: boolean } | null>(null);

  /**
   * Saves a message's media to a path the reader chooses.
   *
   * The dialog is opened on the Rust side (`Session::media_download`), so this
   * hands over an event id and nothing else — a path named here would be a
   * path the webview controls.
   *
   * A cancelled dialog resolves to null and says nothing: the reader knows
   * they cancelled, and announcing it would be noise.
   */
  async function saveMedia(eventId: string | null, filename: string): Promise<void> {
    // Same reasoning as `handleToggleReaction`: media is fetched by event id,
    // and a local echo has none.
    if (eventId === null || savingMedia !== null) return;
    savingMedia = eventId;
    saveNote = null;
    try {
      const path = await mediaDownload(eventId);
      if (path !== null) saveNote = { eventId, text: `Saved to ${path}`, failed: false };
    } catch (err) {
      const reason = err instanceof Error ? err.message : String(err);
      saveNote = { eventId, text: `Could not save ${filename}: ${reason}`, failed: true };
    } finally {
      savingMedia = null;
    }
  }

  /**
   * Where the unread marker sits in `displayRows`, or -1 when there is none.
   *
   * The SDK inserts the marker at most once, at the boundary between read and
   * unread, so this is a lookup rather than a computation. -1 rather than null
   * because it feeds `scrollToIndex` directly.
   */
  /** The topmost row the viewport currently shows, written by `handleScroll`. */
  let firstVisibleIndex = $state(0);

  const unreadIndex = $derived(
    displayRows.findIndex((row) => row.type === "item" && row.item.kind === "readMarker")
  );

  /**
   * Whether the marker has scrolled off the top.
   *
   * `firstVisibleIndex` is written by `handleScroll`, so this recomputes as
   * the reader moves. The button exists for one situation — you opened a room
   * with unread messages and were dropped at the bottom — and must not sit
   * there once the line it points at is on screen.
   */
  const unreadAbove = $derived(unreadIndex >= 0 && firstVisibleIndex > unreadIndex);

  function jumpToUnread(): void {
    if (unreadIndex < 0) return;
    vlist?.scrollToIndex(unreadIndex, { align: "start" });
  }

  /**
   * Toggles `key` as a reaction on `eventId`. Never mutates
   * `timelineStore.items` itself — see this file's top-of-script doc
   * comment for why the local echo arriving back through the diff stream is
   * what actually updates the chip, not this function.
   */
  async function handleToggleReaction(eventId: string | null, key: string): Promise<void> {
    // `null` while the message is still a local echo. A reaction addresses an
    // event, and there is no event yet — the affordance is already hidden in
    // that state (`canReplyOrReact`), so reaching here means something raced,
    // and doing nothing is the correct answer rather than sending a reaction
    // against an id the homeserver has never heard of.
    if (eventId === null) return;
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
  function startReply(row: ItemRow): void {
    replyTargetStore.set(roomId, replyTargetStore.fromItem(row));
  }

  /**
   * The operator's answer to a pending decision on a dispatch card (spec
   * §7.1) — deliberately inert in this build.
   *
   * **What replaces this: sending a Matrix event — not an HTTP call.**
   * An earlier version of this comment said Kaambaan's gate-resolution REST
   * endpoint, and that is now known to be wrong
   * (rakeshgangwar/kaambaan#34). Three reasons, in ascending order of how
   * badly a REST client would fail:
   *
   * 1. The suite's decision is that supermessage acts **only** through
   *    Matrix. An Application Service translates the event into the
   *    Kaambaan call. The client then holds exactly one credential — the
   *    Matrix one — which is also what keeps this app usable as an ordinary
   *    Matrix client against any homeserver, rather than degrading to
   *    read-only wherever suite credentials are absent.
   * 2. Gate resolution requires a **human session cookie**. An agent-token
   *    bearer cannot reach it at all, so a client holding a suite token
   *    could not resolve a gate even if it tried.
   * 3. Resolving as a single bridge identity would attribute every approval
   *    in the suite to one account and silently void Kaambaan's
   *    separation-of-duties check, which refuses a decision whose
   *    `decidedBy` is the agent that produced the work. The Application
   *    Service therefore has to act *on behalf of* the person who tapped
   *    the button, which needs an explicitly-minted `mxid → Principal`
   *    link — never one inferred from a localpart or a matching email.
   *
   * So this becomes a `timelineStore` send of a decision event carrying the
   * option id and the event it answers. Two further things this slot waits
   * on, both that team's to design rather than this app's to invent: the
   * inbound schema whose renderer sets `CustomEventRenderResult.decision`
   * (`core::custom_events`, "Decisions"), and the outbound decision event type
   * itself. Note also that "gate" is two mechanisms there — a stage-review
   * gate, which resolves today, and a mid-run elicitation, which currently
   * has no return path at all — so this may end up answering two event
   * types rather than one.
   *
   * Nothing in this build can reach it: no shipped renderer sets
   * `decision`, so `core::custom_events::resolve_custom_event` returns `decision: null` for every
   * real event and the branch that renders these buttons never executes.
   * That is the spec's requirement, not an accident — §7.1: "Do not ship a
   * visible button that does nothing." It logs rather than being an empty
   * body so that the first renderer to set a decision produces visible
   * evidence in the console instead of a silent click.
   */
  function onDecide(itemId: string, optionId: string): void {
    // `optionId` is the option's *name* — see `permissionRequestRenderer`,
    // which puts the name in `CustomEventDecisionOption.id` precisely because
    // this is what gets sent and the room is a shared human record. The hub's
    // `matchPermissionAnswer` accepts the number, the name or the id, so this
    // resolves through the path a reader typing "Allow once" already uses.
    //
    // An ordinary message, not a custom event: the answer needs no new
    // vocabulary, and sending it as text means the transcript afterwards reads
    // as a person deciding rather than as a machine exchange. It also keeps
    // this the same send path Element would use.
    void timelineStore.send(roomId, optionId).catch((err: unknown) => {
      console.error("failed to send a decision", { itemId, optionId, err });
    });
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
   * membership check. See {@link selectKnownRoom}'s doc comment.
   *
   * Known limitation since the spaces rail landed, recorded rather than
   * hidden: while a space is selected the roster is *filtered*, so a link to
   * a joined room outside that space no longer resolves in-app and falls
   * through to opening matrix.to instead. It degrades rather than breaks,
   * and there is no unfiltered room-id source in the core's command surface
   * to consult — `rooms_resync` serves the filtered list too. If in-app
   * routing across spaces matters later, that is a core change, not a
   * webview one. */
  function knownRoomIds(): readonly string[] {
    return roomsStore.rooms.map((row) => row.room.id);
  }
</script>

{#snippet replyQuote(quote: ReplyQuoteView | null, isOwn: boolean)}
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
      {#if quote.state === "available"}
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
               vocabulary `core::item_view::view_for`'s own placeholders already use. Fixes
               the review finding that this used to render as a bare sender
               name with no indication why. -->
          <!-- Mono, and *not* italic: these two lines share the placeholder
               vocabulary, and no mono italic is bundled — see this file's
               top-of-script doc comment and spec §6.3.

               `faint` only on a peer block; `muted` inside an own bubble.
               The faint rank is defined against the *reading surface*
               (spec §3 checks it there and nowhere else) and it does not
               survive a tinted ground: composited over
               `--color-accent-soft` it measures **4.26:1 light / 3.52:1
               dark**, under the 4.5:1 floor §9 sets, while `muted` on the
               same ground is 7.07 / 6.95. The rank the own bubble gets for
               its secondary text is therefore `muted`, and the same swap
               is made on the two other faint-on-`accent-soft` lines (the
               `edited` marker and the image placeholder below). Measured
               by compositing the layer stack in a canvas — the numbers a
               token-pair calculator gives for `faint` on `surface` (4.92)
               do not describe this ground at all. -->
          <p
            class="mt-0.5 font-mono text-meta break-words {isOwn
              ? 'text-content-muted'
              : 'text-content-faint'}"
          >
            {quote.label}
          </p>
        {/if}
      {:else}
        <p
          class="font-mono text-meta break-words {isOwn
            ? 'text-content-muted'
            : 'text-content-faint'}"
        >
          Original message unavailable
        </p>
      {/if}
    </div>
  {/if}
{/snippet}

{#snippet reactionsRow(item: TimelineItem, interactive: boolean, alignEnd: boolean = item.isOwn)}
  <!--
    `alignEnd` defaults to `item.isOwn` — an own bubble's affordances hang
    off its right edge — but it is a *parameter*, not a read of `isOwn`,
    because one caller genuinely differs: the dispatch card is left-aligned
    regardless of sender (spec §7), so its rows must be too. See the card's
    call sites. Do not "simplify" this back to `item.isOwn`: `isOwn` is
    account-scoped (`event.sender() == own_user` in the core), so any other
    session signed in as this account can produce an own custom event, and
    a right-hanging row under a left-anchored card is then reachable, not
    hypothetical.

    This row renders *outside* the message container, on the sheet ground,
    tucked under the container's bottom edge — see `messageBlock`. A
    reaction is chrome that acts on a message, not part of it, and this
    file already refuses to mix the two anywhere else. Positive offsets
    rather than a negative one that would overlap the container's edge:
    an overlap only reads as "tucked into the corner" against a container
    that *has* a visible corner, and of the three that call this, only the
    own bubble does — a peer block and the space under a dispatch card
    would just get a chip sitting too close to the text above it.
  -->
  {#if item.reactions.length > 0}
    <div class="mt-1.5 flex flex-wrap gap-1 {alignEnd ? 'justify-end' : ''}">
      {#each item.reactions as reaction (reaction.key)}
        {@const chipClass = reaction.byMe
          ? "reaction-chip-mine border-accent font-medium text-accent"
          : "border-border bg-surface-sunken text-content-muted hover:border-border-strong hover:text-content"}
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

          The "mine" fill is `.reaction-chip-mine` (in the style block at
          the foot of this file) rather than a `bg-accent/15` utility, and
          that is a contrast fix. A translucent fill composites against
          whatever happens to be behind it, and this one snippet renders on
          **four** different grounds: `--color-surface` (a peer block),
          `--color-accent-soft` (an own bubble), `--color-surface-raised`
          (a dispatch card) and `--color-signal-soft` (a pending one). The
          tint measured 5.55:1 on the first and 4.26:1 on the second, and
          the fix that branched on `item.isOwn` still left the two card
          grounds unmeasured — where they came in at 5.00:1 resting and
          4.53:1 on hover, under the 5.0:1 bar. Branching per ground does
          not scale and is how this was missed twice. `.reaction-chip-mine`
          instead paints the accent tint over its *own* opaque
          `--color-surface`, so the chip's contrast is a single number on
          every ground it can ever land on, present or future.

          Numbers are composited by the browser, not modelled: Tailwind
          emits `/15` as `color-mix(in oklab, … , transparent)`, so
          anything that reads `getComputedStyle().backgroundColor` and
          expects `rgba()` silently measures the wrong ground. Paint the
          layer stack into a canvas and read the pixel back.
        -->
        <button
          type="button"
          disabled={!interactive}
          onclick={() => handleToggleReaction(item.eventId, reaction.key)}
          aria-pressed={reaction.byMe}
          aria-label={`${reaction.displayKey}, ${reaction.count} ${reaction.count === 1 ? "reaction" : "reactions"}${reaction.byMe ? ", including yours" : ""} — toggle`}
          class="rounded-full border px-2 py-0.5 font-sans text-ui break-words transition-colors disabled:cursor-not-allowed disabled:opacity-60 {chipClass}"
        >
          {reaction.displayKey} {reaction.count}
        </button>
      {/each}
    </div>
  {/if}
{/snippet}

{#snippet messageActions(row: ItemRow, alignEnd: boolean = row.item.isOwn)}
  {@const item = row.item}
  {@const interactive = row.canReplyOrReact}
  <!-- `alignEnd`: see `reactionsRow`'s note on why this is a parameter. -->
  {#if interactive}
    <!--
      Chrome, not content — no `.selectable` here (see this file's
      top-of-script comment on user-select discipline), and rendered
      outside the message container on the sheet ground for the same
      reason `reactionsRow` is; see its comment and `messageBlock`'s.

      Faded out until the *row* is hovered or one of these buttons has
      focus (`focus-within`, not `hover` alone), so tabbing through the
      timeline still reaches every button — opacity, never `display:
      none`, keeps them in the tab order the whole time. The `group` that
      drives that hover is on `messageBlock`'s outermost row precisely so
      that it encloses this detached element: on the container, the
      pointer would leave the group the instant it reached the row being
      revealed. `flex-wrap` so six quick reactions plus "Reply" never
      force the row wider than the reading column.

      The negative margin pulls the outermost button's own padding back so
      the row aligns optically with the message container's edge rather
      than sitting indented from it — left edge for a peer block or a
      dispatch card, right edge for an own bubble. `font-sans` for the
      same reason the reaction chips carry it: this is chrome, and the
      column it now sits directly on is set in the reading serif.

      **It is positioned, not laid out**, and that is worth the extra
      mechanism. In normal flow this row reserved 26px on *every* message
      forever — measured in the running app on 2026-08-17: a one-line
      message came to 142px, of which 54px was the bubble and 88px was
      chrome, 26 of it this row sitting at `opacity: 0`. Reserving space
      was what kept the layout from jumping on hover; overlaying the gap
      below the block achieves the same thing for nothing, because that
      gap is the next message's top padding and is empty by construction.

      `pointer-events-none` until revealed so an invisible row cannot eat
      a click meant for the message underneath it, and `top-full` rather
      than a fixed offset so it always sits immediately beneath its own
      block whatever that block contains.

      **It has to fit the gap it overlays**, and that was measured, not
      guessed: with `mt-1` it ended 6px past where the next message's first
      line begins, so hovering one message painted its controls over the
      text of the next. No margin, and the gap below (`pt-6` on the block
      that follows) is 24px against this row's 22px.
    -->
    <div
      class="pointer-events-none absolute top-full right-0 left-0 z-10 flex flex-wrap items-center gap-0.5 font-sans opacity-0 transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 focus-within:pointer-events-auto focus-within:opacity-100 {alignEnd
        ? '-mr-1.5 justify-end'
        : '-ml-1.5'}"
    >
      <button
        type="button"
        onclick={() => startReply(row)}
        class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        Reply
      </button>
      {#each QUICK_REACTIONS as emoji (emoji)}
        <button
          type="button"
          onclick={() => handleToggleReaction(item.eventId, emoji)}
          aria-label={`React with ${emoji}`}
          class="rounded px-1 py-0.5 text-ui transition-colors hover:bg-surface-sunken"
        >
          {emoji}
        </button>
      {/each}
      <!--
        The six above stay the fast path — one click, no panel. This is for
        everything else, which used to be reachable only if somebody else in
        the room had already reacted with it.
      -->
      <button
        type="button"
        onclick={() => (pickingReactionFor = item.id)}
        aria-label="React with another emoji"
        class="rounded px-1.5 py-0.5 text-ui font-medium text-content-muted transition-colors hover:bg-surface-sunken hover:text-content"
      >
        +
      </button>
    </div>
  {/if}
{/snippet}

{#snippet seenMarker(item: TimelineItem, alignEnd: boolean = item.isOwn)}
  <!-- `alignEnd`: see `reactionsRow`'s note on why this is a parameter.


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
    <p class="mt-1 font-mono text-meta text-content-muted {alignEnd ? 'text-right' : 'text-left'}">
      {item.readBy.length === 1 ? "Seen" : `Seen by ${item.readBy.length}`}
    </p>
  {/if}
{/snippet}

{#snippet logLine(text: string)}
  <!--
    The quiet machine log: membership changes (grouped or not), room
    creation, encryption enabled, room replaced, and every placeholder for
    something this build cannot render yet. All of these are the same row —
    centred, mono `--text-meta`, `--color-content-faint` — and they were
    three verbatim copies of this markup before this snippet existed.
    Keeping them literally identical is the point, not an accident: a
    collapsed membership run must read no differently from an ungrouped
    one, and a placeholder must read as part of the same log rather than as
    a failed message. Mono means machine (spec §5.3), and no mono rank is
    ever italic (spec §6.3) — `font-synthesis: none` plus no bundled mono
    italic would render an italic upright anyway.

    `min-w-0` + `max-w` + `break-words`, the same three-part guard every
    other sender-controlled string in this file carries. These strings are
    not app-authored constants: a system line is built from
    `attributedName`, which is the sender's own *unbounded* display name,
    and a placeholder interpolates a sender-controlled `msgtype`/`detail`.
    Before the guard, a single 5000-character display name pushed the
    scroller's own `scrollWidth` to 16515px against a 1563px column.
    `break-words` alone is not enough — `overflow-wrap: break-word` does
    not reduce an element's min-content size, so a flex item's automatic
    minimum size still holds the row open until `min-w-0` lets it shrink.
  -->
  <div class="flex justify-center py-2">
    <span
      class="min-w-0 max-w-[68ch] text-center font-mono text-meta break-words text-content-faint"
      >{text}</span
    >
  </div>
{/snippet}

{#snippet messageBlock(row: ItemRow, content: Snippet)}
  {@const item = row.item}
  {@const continuesRun = row.continuesRun}
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
  <!--
    Vertical rhythm, and why these two values and not the spec's literal
    "2px": the gap above a row must not depend on whether the row above it
    happened to render an actions row. `messageActions` is hidden with
    `opacity`, never `display: none` (it has to stay in the tab order), so
    it occupies ~26px of layout whenever `core::item_view::can_reply_or_react` is true and 0px
    when it is not — a local echo, a failed send, anything without a server
    event id yet. At `pt-0.5` that made the gap between two run
    continuations 28px in the common case but **2px** in the other, which
    is tighter than the 13.5px between two paragraphs of a *single*
    message: two separate messages ended up closer together than two
    paragraphs of one, inverting the hierarchy.

    So the padding carries the rhythm on its own: 20px above a run
    continuation (comfortably looser than the 13.5px intra-message
    paragraph gap) and 32px above a new sender block (clearly looser again,
    so a boundary still reads as a boundary). Both hold with or without an
    actions row; the row only ever adds to them.
  -->
  <!--
    Three nested elements, each earning its place — this is not one wrapper
    too many:

    1. The **row** carries the vertical rhythm and, since the reaction and
       action rows were detached, the `group` class. `group` has to live
       out here now: `messageActions` is revealed by `group-hover`, and if
       `group` stayed on the container the pointer would leave it the
       moment it reached the very row it was revealing. One consequence,
       accepted deliberately: the hover target is now the full column
       width rather than the message's own box. For a peer block, which
       has no bubble to aim at, that is the better target anyway.
    2. The **alignment row** is a row-direction flex container whose single
       item is the message container. It exists to keep the container a
       flex item of a *row*-direction parent, which is precisely the
       relationship the layout-blowout guard is written against: in a
       column-direction parent `min-width: auto` would not apply to the
       cross axis and `min-w-0` would quietly stop being load-bearing.
       Do not collapse this into the row with `items-end`/`items-start`.
    3. The **container** is the message itself.

    Reactions and actions render *after* the alignment row, on the column
    (sheet) ground rather than inside the container — chrome does not sit
    inside content, the same rule that keeps mono off prose and
    `.selectable` off chrome. The timestamp and the seen marker stay
    inside: those annotate the message rather than acting on it. Both
    detached snippets render nothing at all when there is nothing to show,
    so a message with no reactions and no available actions still
    contributes exactly its own height and the rhythm above is untouched.
  -->
  <!--
    `relative` anchors the hover-actions overlay (`messageActions`), which no
    longer takes height of its own.

    `pt-6` where a run starts, down from `pt-8`, and `pt-4` inside one. 32px
    between speakers is generous for a two-person room, and measured against the
    rest of the block it was the largest single piece of chrome around a
    one-line message.

    The exact numbers are load-bearing rather than taste: this gap is what the
    hover-actions overlay sits in, so it must stay at least as tall as that row
    (22px) or hovering a message covers the next one's first line.
  -->
  <div class="group relative flex flex-col {continuesRun ? 'pt-4' : 'pt-6'}">
    <div class="flex {item.isOwn ? 'justify-end' : 'justify-start'}">
      <div
        class="flex min-w-0 flex-col text-content {item.isOwn
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
        {@render replyQuote(row.replyQuote, item.isOwn)}
        {@render content()}
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
                Not sent
              {:else if sending}
                Sending…
              {:else}
                <!-- `muted`, not the `faint` its peer-side counterpart
                     carries: this line sits on the own bubble's
                     `--color-accent-soft`, where faint measures 4.26:1 light
                     / 3.52:1 dark. See `replyQuote` for the measurement and
                     the rule. It costs the marker its rank below the
                     timestamp; the word is doing that work anyway. -->
                {#if item.edited}<span class="text-content-muted">edited</span>{/if}
                {#if !continuesRun}<span>{formatTime(item.timestampMs)}</span>{/if}
              {/if}
            </p>
          {/if}
        {/if}
      </div>
    </div>
    {@render reactionsRow(item, row.canReplyOrReact)}
    {@render messageActions(row)}
  </div>
{/snippet}

<div class="relative min-h-0 flex-1">
  {#if unreadAbove}
    <!--
      Only while the line is off the top. It exists for one situation — you
      opened a room with unread messages and were dropped at the bottom — and a
      button that stayed put once the thing it points at is on screen would be
      chrome, not navigation.
    -->
    <div class="pointer-events-none absolute inset-x-0 top-2 z-20 flex justify-center">
      <button
        type="button"
        onclick={jumpToUnread}
        class="pointer-events-auto rounded-full border border-accent/40 bg-surface px-3 py-1 text-ui text-accent shadow-sm transition-colors hover:bg-surface-raised"
      >
        Jump to unread
      </button>
    </div>
  {/if}

  {#if pane !== "rows"}
    <!--
      `bg-surface-sunken` here too, so the pane's ground is the field whether
      or not there is anything on the sheet — see the scroller below.

      Three states share this box on purpose, and it is the same box each
      time: switching between them changes only the words, never the ground,
      so a room arriving is one fade rather than a sequence of layouts. See
      `timelinePane.ts` for why `settling` says nothing at all.
    -->
    <div class="flex h-full items-center justify-center bg-surface-sunken">
      {#if pane === "empty"}
        <p class="fade-in text-ui text-content-muted">Nothing here yet.</p>
      {:else if pane === "loading"}
        <!--
          A sweep rather than a static line: this appears only when the pane
          has been waiting past `LOADING_AFTER_MS`, so by the time a reader
          sees it they are already wondering whether anything is happening.
          Motion answers that; a still label does not. See `ai/Shimmer.svelte`.
        -->
        <Shimmer
          class="fade-in text-ui"
          contentLength={21}>Loading conversation…</Shimmer
        >
      {/if}
    </div>
  {:else}
    <!--
      A plain box around the list, bound so the tail-following observer can
      reach virtua's scroller deterministically. `VList` is a component and
      `bind:this` on it yields a `VListHandle`, which exposes no element; and
      the pane above holds the jump button and the empty state as well, so
      "the pane's first child" is whichever of those happens to be mounted.
      This wrapper contains exactly one thing, and `h-full` keeps the list's
      `height: 100%` resolving against the same box it did before.
    -->
    <div bind:this={scrollBox} class="fade-in h-full">
    <VList
      bind:this={vlist}
      data={displayRows}
      getKey={(row: TimelineDisplayRow) => row.key}
      shift={view.shift}
      bufferSize={BUFFER_SIZE}
      onscroll={handleScroll}
      class="bg-surface-sunken"
    >
      {#snippet children(row: TimelineDisplayRow, _index: number)}
        <!--
          The reading column (spec §6.3.0). *Every* row lays out inside one
          centred `72ch` column: peer blocks align to its left edge, own
          bubbles to its right edge, and dividers, log lines, emotes and
          dispatch cards centre within it. Wrapping here — around the whole
          row body rather than per branch — is what lets the `customEvent`
          card inherit the same anchoring without that branch being touched.

          This replaced viewport-relative alignment after the first
          implementation was rendered and reviewed: at 1905px a reply sat
          599px from the message it answered, in the very case where it
          quotes its parent by name, and the pane read as two unrelated
          columns with a void between them that grew with the window. The
          own/peer asymmetry this design wants is one of *register* — sans
          against serif, tight against airy, a ground against no ground —
          and every bit of that survives inside a shared column. The
          horizontal distance was never carrying meaning.

          `font-serif text-body` on the column is load-bearing, not
          inherited decoration: `ch` resolves against the element's own
          font, so this is what makes the column's `72ch` and the peer
          block's `68ch` the same unit and the two numbers actually
          comparable (566px and 535px, measured). Measured in the inherited
          16px sans instead, `72ch` would be ~691px — a coincidence rather
          than a relationship, and wide enough to reopen the gap this
          exists to close. Nothing depends on inheriting the face: every
          descendant already names its own (`messageBlock`, `logLine`, the
          date divider, the emote and the dispatch card all set theirs).

          **The sheet.** The column carries `--color-surface` and the
          scroller behind it carries `--color-surface-sunken`, so the
          reading column is the one lit surface in the window and the space
          either side of it is a considered field rather than 1050px of
          inert nothing (which is what a flat ground looked like at 1905px
          once the column landed). The roster and the composer are already
          `--color-surface-sunken`, so the field is continuous with them
          and the app reads as a three-level stack: sunken field, surface
          sheet, raised card. No hairline on the sheet's edges — checked
          both ways at 1905px and 700px in both themes, and the tone step
          alone is cleaner; a border turned the sheet into a boxed panel
          and started competing with the dispatch card, which is supposed
          to be the timeline's only bordered object.

          The sheet owns its own gutter, which is why the scroller above no
          longer carries `px-4`. Each `max-w` is its padding plus 72ch
          rather than a bare `72ch`, because Tailwind's `box-sizing:
          border-box` would otherwise take the margins *out of* the 72ch
          and clamp the peer block's own 68ch measure to whatever was left;
          widening by exactly the padding keeps the content box at 72ch at
          every width. The margins narrow below `lg`, where the pane is too
          narrow for a field to exist at all: at 700px the sheet fills the
          pane and 32px of margin would be 79px of reading width bought for
          8px of field either side. Measured: content box 566px at 1905px,
          and 380px at 700px — exactly what it was before the sheet, so a
          narrow window pays nothing for this.

          Vertical padding is deliberately *not* set here. Rows already
          carry their own top padding (32px new block / 20px continuation)
          and the sheet is continuous down the whole scroll height, so
          per-row vertical padding would not add margins to a page — it
          would add a gap between every pair of rows and double-count
          against a rhythm that was measured. See the report for the
          rendered check that this reads as a sheet and not a stripe.
        -->
        <!--
          `data-testid` is the handle the driven UI test selects rows by
          (`e2e/timeline-rows.spec.ts`). It is here rather than on any inner
          branch because the invariants worth testing are about ROWS — a
          message row with no text in it, a row with no height — and those are
          properties of this box whatever it ends up containing.
        -->
        <div
          data-testid="timeline-row"
          class="mx-auto w-full max-w-[calc(72ch+2rem)] min-w-0 bg-surface px-4 font-serif text-body lg:max-w-[calc(72ch+4rem)] lg:px-8"
        >
          {#if row.type === "membershipGroup"}
            <!--
              A collapsed run of consecutive membership changes — see
              `timelineGrouping.ts`. Literally the same row as an ungrouped
              `system` line, because it renders through the same `logLine`
              snippet rather than a copy of its markup.
            -->
            {@render logLine(row.text)}
          {:else}
            {@const item = row.item}
            {@const continuesRun = row.continuesRun}
            {@const view = row.view}
            {#if view.render === "dateDivider"}
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
            {:else if view.render === "bubble"}
                {#snippet bubbleContent()}
                  <!--
                    One renderer for both bodies now. `core::rich` parses the
                    sanitised `formatted_body` a human's client sent *and* the
                    raw markdown an agent wrote into the same block tree, so
                    this branch no longer chooses between an `{@html}` and a
                    markdown tokeniser — the choice, and the guarantees that
                    made each one safe, moved to Rust where iOS and Android
                    inherit them.

                    What is gone with it: the `{@html}` that was safe only
                    because of a chain of core-side guarantees, and the
                    three-paragraph comment that had to explain why. There is
                    no escape hatch left on this path to guard.

                    `onclick`/`onauxclick` are delegated link handling, not
                    controls of their own — they act only when the click
                    bubbled up from a nested `<a href>`. An `<a>`'s native
                    keyboard activation already dispatches a bubbling `click`,
                    so this element needs no keyboard handler of its own.
                    `onauxclick` covers the middle-click case `onclick` cannot
                    see; see `messageLinks.ts`.
                  -->
                  {#if item.isOwn}
                    <!--
                      What the operator typed, exactly as typed. *You type,
                      they write* (spec §6.3): an own message is a command, and
                      rendering markdown in it would mean a stray asterisk
                      silently changing what you appear to have said. The
                      client also sends it as plain `m.text`, so this is what
                      every other client in the room sees too.

                      The core enforces that — `blocks_for` returns an own
                      message verbatim as a single paragraph rather than
                      parsing it — so this branch differs only in styling, and
                      a host cannot forget the rule by rendering the same
                      blocks the peer branch does.
                    -->
                    <div class="selectable whitespace-pre-wrap break-words">
                      <RichText blocks={view.blocks} />
                    </div>
                  {:else}
                    <!-- svelte-ignore a11y_click_events_have_key_events -->
                    <!-- svelte-ignore a11y_no_static_element_interactions -->
                    <div
                      class="message-html selectable {view.muted ? 'text-content-muted' : ''}"
                      onclick={(e: MouseEvent) =>
                        handleMessageBodyClick(e, undefined, selectKnownRoom, knownRoomIds)}
                      onauxclick={(e: MouseEvent) =>
                        handleMessageBodyAuxClick(e, undefined, selectKnownRoom, knownRoomIds)}
                    >
                      <RichText blocks={view.blocks} />
                    </div>
                  {/if}
                {/snippet}
                {@render messageBlock(row, bubbleContent)}
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
                <!--
                  Keyed by the *event* id, not the row's identity: the media
                  repository is addressed by event, and a local echo has no
                  event to address. `""` never resolves, which is the honest
                  answer for an image whose event does not exist yet.
                -->
                {@const src = mediaCache.get(item.eventId ?? "")}
                {@const failed = mediaCache.hasFailed(item.eventId ?? "")}
                {#snippet imageContent()}
                  {#if failed}
                    <!-- Never a broken-image icon: any failure — nothing
                         renderable, a rejected fetch, or the <img> itself
                         failing to decode — lands here. Mono and unitalicised
                         like every other placeholder rank (spec §6.3), since
                         what this line is really saying is "there is an image
                         here that could not be shown".

                         `faint` on a peer block, `muted` inside an own
                         bubble, for the ground reason `replyQuote` sets
                         out. -->
                    <p
                      class="selectable font-mono text-meta break-words {item.isOwn
                        ? 'text-content-muted'
                        : 'text-content-faint'}"
                    >
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
                      onerror={() => mediaCache.markFailed(item.eventId ?? "")}
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
                {@render messageBlock(row, imageContent)}
              {:else if view.render === "mediaFile"}
                <!--
                  `m.file`/`m.audio`/`m.video`: an informative row (filename,
                  size, kind) with a Save action. No in-app playback — that is
                  still a follow-up (see
                  `.superpowers/sdd/2026-08-13-m0-spine/media-report.md`) — but
                  a file you can save is a file you can open, which is the part
                  that was actually missing.

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
                    <button
                      type="button"
                      onclick={() => void saveMedia(item.eventId, view.filename)}
                      disabled={savingMedia === item.eventId}
                      class="ml-auto shrink-0 rounded px-2 py-1 text-ui text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:opacity-50"
                    >
                      {savingMedia === item.eventId ? "Saving…" : "Save"}
                    </button>
                  </div>
                  {#if saveNote?.eventId === item.eventId}
                    <p
                      class="mt-1 font-mono text-meta {saveNote.failed
                        ? 'text-destructive'
                        : 'text-content-muted'}"
                    >
                      {saveNote.text}
                    </p>
                  {/if}
                {/snippet}
                {@render messageBlock(row, mediaFileContent)}
              {:else if view.render === "customEvent"}
                <!--
                  The dispatch card (spec §7) — a `kind: "customMessage"`
                  item: Kaambaan cards/runs/permission requests/station status
                  once those schemas land (`docs/matrix-events.md` §G), the
                  demo renderer until then. `view.view` is the whole
                  `core::custom_events::resolve_custom_event` outcome
                  (`core::custom_events`) — this block only
                  switches on its `status`, never decides anything itself.
                  See this file's top-of-script doc comment for the four
                  decisions this markup expresses.

                  Every value below is plain-text interpolation (`{...}`),
                  never `{@html}`, never an `href`/`src`/inline style —
                  `content` is arbitrary JSON from anyone who can send to the
                  room, and `core::custom_events::resolve_custom_event` has already bounded its
                  fields and validated its decision before either reaches
                  here. `break-words` + the card's own `max-w-[68ch]`/
                  `min-w-0` guard against a long unbroken value, label or
                  option widening the card, the same discipline every other
                  sender-controlled surface in this file follows.
                -->
                {#if view.view.status === "placeholder"}
                  <!--
                    Not a card, on purpose (spec §7): a type this build
                    cannot render at all is not worth a bordered object. It
                    is a log line like any other, and it renders through the
                    same `logLine` snippet — see there for the wrap guard and
                    why every row in that log is deliberately identical.
                  -->
                  {@render logLine(view.view.text)}
                {:else}
                  {@const decision =
                    view.view.status === "rendered" ? view.view.decision : null}
                  <!--
                    `justify-start` unconditionally, and no `messageBlock`: a
                    dispatch does not take a side (spec §7). `flex-1` +
                    `max-w-[68ch]` makes it occupy the full reading measure
                    rather than shrinking to its content, and `min-w-0` is the
                    other half of the layout-blowout guard the block comment
                    on the style rules at the foot of this file describes.
                    `font-serif` on the wrapper both sets the face card values
                    are read in and makes `68ch` resolve against that face, so
                    a card is exactly as wide as a peer message.
                  -->
                  <div class="flex justify-start pt-8">
                    <div class="group relative min-w-0 max-w-[68ch] flex-1 font-serif text-body text-content">
                      <div class="dispatch-card {decision ? 'dispatch-card-pending' : ''}">
                        <!--
                          Header: what the card is left, the timestamp right,
                          a hairline beneath. The *name* the renderer gives
                          this kind of event ("Turn", "Permission") rather
                          than `view.eventType` — a reader should not have to
                          parse `dev.agentpod.turn.v1` to learn they are
                          looking at a turn. The schema address stays in the
                          `title`, for a card nothing recognises and for
                          anyone diagnosing one; `displayEventType` truncated
                          it from the *left* for exactly that case, and still
                          does.
                        -->
                        <div
                          class="flex items-baseline gap-3 border-b border-border px-3 py-2 font-mono text-content-muted"
                        >
                          <span
                            class="min-w-0 flex-1 text-label uppercase break-words"
                            title={view.eventType}
                          >
                            {view.label}
                          </span>
                          <span class="shrink-0 text-meta">{formatTime(item.timestampMs)}</span>
                        </div>
                        {#if view.view.status === "rendered"}
                          <!--
                            A real `<dl>`: these rows are label/value pairs,
                            and a screen reader should read them as such
                            rather than as a run of unrelated lines. Keyed by
                            index, not `field.label` — a renderer's fields are
                            trusted (registered application code, not an array
                            read straight off the payload), but a duplicate
                            label is still possible and shouldn't be able to
                            confuse Svelte's keyed reconciliation.

                            A two-column *grid*, not a flex row per pair, and
                            the label track is `max-content` rather than the
                            fixed `9ch` this first shipped with. Both halves
                            of that are corrections found by rendering:

                            - A fixed `9ch` is narrower than most real labels,
                              and `overflow-wrap` then breaks them mid-word —
                              `REQUEST`/`ED BY`, and at the 60-char bound a
                              twelve-line syllable ladder. `min-w-[9ch]` keeps
                              the spec's column rank for a short label like
                              `NOTE`; `max-w-[16ch]` bounds it; between them
                              an ordinary multi-word label wraps at its spaces
                              and only a single over-long *word* still breaks,
                              which is `break-words`' (`overflow-wrap:
                              break-word`, not `anywhere`) last resort doing
                              what it should.
                            - A `max-content` track clamped by those two
                              widths sizes to the longest label *in this card*
                              and applies to every row, so the values still
                              line up in one column. Per-row flex would let
                              each row pick its own label width and the grid
                              would stop being a grid.

                            `ch` resolves against the element's own font, so
                            the two caps are on the `dt`, which is the mono
                            one — 9ch of mono, as the spec means it, not 9ch
                            of the serif the card is set in.
                          -->
                          <dl
                            class="selectable m-0 grid grid-cols-[max-content_minmax(0,1fr)] items-baseline gap-x-3 gap-y-1 px-3 py-2"
                          >
                            {#each view.view.fields as field, i (i)}
                              <dt
                                class="min-w-[9ch] max-w-[16ch] font-mono text-label uppercase break-words text-content-muted"
                              >
                                {field.label}
                              </dt>
                              <dd class="m-0 min-w-0 break-words">{field.value}</dd>
                            {/each}
                          </dl>
                          {#if view.view.reasoning}
                            <!--
                              How the agent reached this, when it said.
                              Collapsed: it is context, not the conclusion,
                              and an operator scanning a room wants the
                              conclusion first.

                              A `<details>` rather than a scripted toggle —
                              the element already is a disclosure, keyboard
                              operable and announced as one, and re-building
                              that in Svelte would only be a worse version.
                            -->
                            <details class="border-t border-border px-3 py-2">
                              <summary
                                class="cursor-pointer font-mono text-label uppercase text-content-muted"
                              >
                                Reasoning
                              </summary>
                              <p
                                class="selectable mt-2 mb-0 break-words whitespace-pre-wrap text-meta text-content-muted"
                              >
                                {view.view.reasoning}
                              </p>
                            </details>
                          {/if}
                          {#if view.view.newerVersion}
                            <!--
                              Mono and emphatically *not* amber: this is a
                              note, not a decision, and amber is reserved
                              (spec §3). Not italic either — no mono italic is
                              bundled (spec §6.3).

                              `--color-content-muted`, not `faint`, and that
                              is a measured floor rather than a preference:
                              `faint` on `--color-surface-raised` is 4.16:1
                              in dark, under the 4.5:1 bar, because the card's
                              ground is *raised* off the surface the rest of
                              the log's faint rows sit on. `muted` on the same
                              ground is 8.21:1.
                            -->
                            <p class="px-3 pb-2 font-mono text-meta text-content-muted">
                              Shown from a newer version of this event
                            </p>
                          {/if}
                        {:else}
                          <!-- status === "fallbackBody": the plain-text
                               `content.body` Matrix convention puts on every
                               suite custom event, for a type this build has
                               no renderer for. Serif, no field grid (spec
                               §7) — it is prose, not data. -->
                          <p class="selectable px-3 py-2 whitespace-pre-wrap break-words">
                            {view.view.text}
                          </p>
                        {/if}
                        {#if decision}
                          <!--
                            UNREACHABLE IN THIS BUILD — do not go looking for
                            these buttons in the running app. No shipped
                            renderer sets `CustomEventRenderResult.decision`
                            (`core::custom_events` "Decisions"; the demo renderer
                            never does, and a unit test holds it that way), so
                            `core::custom_events::resolve_custom_event` returns `decision: null` for
                            every real event and this block never executes.
                            That is spec §7.1's requirement — "do not ship a
                            visible button that does nothing" — and the reason
                            `onDecide` is inert. Kaambaan's permission-request
                            renderer plus its gate-resolution REST call
                            (`docs/positioning.md`, wedge #3) are what make
                            this live; the slot is covered by unit tests
                            against a fixture renderer so it ships proven
                            rather than speculative.

                            Everything here is bounded and validated by
                            `boundDecision` before it arrives: the prompt is a
                            string capped at 300 chars, and there are at most
                            four options, each with a string `id` and a string
                            `label` capped at 60. A malformed decision is
                            `null` by then, so this block cannot render a
                            half-built control.
                          -->
                          <div class="border-t border-border px-3 py-2">
                            <p class="selectable break-words">{decision.prompt}</p>
                            <!--
                              The only amber in the application (spec §7.1),
                              alongside this card's left edge and ground. It
                              says the operator owes someone an answer.
                            -->
                            <p class="mt-2 font-mono text-label uppercase text-signal">
                              Awaiting your decision
                            </p>
                            <div class="mt-1.5 flex flex-wrap gap-2">
                              <!--
                                Keyed by index, not `option.id`, and for a
                                sharper reason than the field grid above:
                                `boundDecision` guarantees each `id` is a
                                string, but nothing makes two options' ids
                                *distinct* — a renderer echoing a payload
                                could easily produce two `"approve"`s, and a
                                duplicate key is a Svelte runtime error
                                (`each_key_duplicate`) that would take the
                                whole timeline render down. The id is still
                                what `onDecide` receives; it is never a key
                                and never reaches the DOM.
                              -->
                              {#each decision.options as option, i (i)}
                                <button
                                  type="button"
                                  onclick={() => onDecide(item.id, option.id)}
                                  class="min-w-0 max-w-full rounded border border-signal px-2.5 py-1 font-sans text-ui font-medium break-words text-signal transition-colors hover:bg-signal hover:text-surface-raised"
                                >
                                  {option.label}
                                </button>
                              {/each}
                            </div>
                          </div>
                        {/if}
                      </div>
                      <!--
                        Outside the bordered object, not inside it: the card
                        is the dispatch, and these are this reader's
                        affordances against it — the same relationship, and
                        the same shared snippets, a message block has.

                        `alignEnd: false` explicitly, on all three, because
                        the card is left-aligned regardless of sender and its
                        affordances must be too. This is not defensive
                        decoration: `isOwn` is `event.sender() == own_user` in
                        the core, i.e. *account*-scoped, not client-scoped, so
                        another session signed in as this account can produce
                        an own custom event — and `lastOwnMessageId`
                        (top-of-script) already handles exactly that case for
                        `seenMarker`. Left the default and these rows would
                        hang off the right edge of a left-anchored card.
                      -->
                      {@render reactionsRow(item, row.canReplyOrReact, false)}
                      {@render messageActions(row, false)}
                      {@render seenMarker(item, false)}
                    </div>
                  </div>
                {/if}
              {:else if view.render === "unreadMarker"}
                <!--
                  Where you left off. A rule with a word on it, in the accent
                  rather than the signal colour: this is navigation, not a
                  decision waiting on you (spec §3 reserves amber for that).
                -->
                <div class="flex items-center gap-3 py-2" data-testid="unread-marker">
                  <span class="h-px flex-1 bg-accent/40"></span>
                  <span class="font-mono text-meta uppercase tracking-wide text-accent">New</span>
                  <span class="h-px flex-1 bg-accent/40"></span>
                </div>
              {:else if view.render === "system"}
                <!-- Membership lines, room creation, encryption enabled, room
                     replaced — see `logLine` for why this row looks the way
                     it does and what its wrap guard is protecting. -->
                {@render logLine(view.text)}
              {:else if view.render === "placeholder"}
                <!--
                  Anything the reader must be told about but this build can't
                  render fully yet: undecryptable events on a fresh device
                  (the common case in a real encrypted room), redactions,
                  media, stickers, polls, custom suite events. Never the bare
                  empty bubble that rendering nothing used to produce — see
                  `core::item_view`. Rendered as the same log row as a
                  system line, deliberately: see `logLine`.
                -->
                {@render logLine(view.text)}
              <!-- view.render === "none": deliberately silent, see `core::item_view`. -->
            {/if}
          {/if}
        </div>
      {/snippet}
    </VList>
    </div>
  {/if}
</div>

<style>
  /*
   * Arriving, rather than appearing.
   *
   * A room switch was measured at four visual states in 145ms: the empty-state
   * message, then bare scroller, then thirteen rows mounted but every one
   * `visibility: hidden` because virtua had not measured them, then the
   * settled room. The middle two are unavoidable — virtua has to mount a row
   * before it can measure it — but they do not have to be *watched*. Fading
   * the list in over that window covers the mount, the measurement, and the
   * scroll landing at the tail, so what a reader sees is one room resolving
   * instead of four states arguing.
   *
   * On the list as a whole, deliberately, and never on individual rows: virtua
   * mounts and unmounts rows continuously as you scroll, so a per-row fade
   * would shimmer down the page for the entire length of a conversation. The
   * one moment worth covering is the one where a whole room appears at once.
   *
   * `prefers-reduced-motion` drops it to nothing rather than shortening it.
   * The fade is decoration over a state that is already correct.
   */
  .fade-in {
    animation: fade-in 140ms ease-out both;
  }

  @keyframes fade-in {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .fade-in {
      animation: none;
    }
  }

  /*
   * The dispatch card's frame (spec §7) — the timeline's only bordered
   * object, and the only place `--color-signal` (amber) appears anywhere in
   * this application (spec §3).
   *
   * **Two border ranks, and the difference between them is the whole
   * device.** A 1px `--color-border` hairline on three sides, a 2px
   * `--color-border-strong` edge on the left (spec §7). The first
   * implementation used `border-strong` on all four sides, and rendering it
   * is what exposed the mistake: the left edge was then the same colour as
   * its neighbours and merely one pixel wider — invisible at any normal
   * viewing distance. That left the card's signature device existing *only*
   * on the pending variant, which no shipped renderer can currently
   * produce, so everything a user could actually see had no signature at
   * all. The edge has to read as a rank in the ordinary state, so that
   * going amber changes an edge's **meaning** rather than conjuring an edge
   * from nothing.
   *
   * This matters more in light than the token table suggests:
   * `--color-surface-raised` on `--color-surface` measures 1.03:1, so in
   * light mode the card has, for practical purposes, no ground — only its
   * frame. The frame is what makes it an object there.
   *
   * Written here rather than as Tailwind utilities for one specific
   * reason: the card sets `border-color` on three sides and a *different*
   * `border-left-color` on the fourth. As utilities those are two rules of
   * equal specificity, so which one wins depends on the order Tailwind
   * happens to emit `border-color` and `border-left-color` in — not on the
   * order they appear in the class attribute, which is what a reader would
   * naturally assume. One rule, with the left edge stated after the
   * shorthand, is unambiguous. It also lets the pending swap be a single
   * named state rather than four interleaved conditionals.
   *
   * `--color-signal-soft` is the pending ground and `--color-signal` the
   * pending edge; both are tokens, no literal colours (spec §3). The 100ms
   * transition is the whole motion budget this element gets (spec §8) and
   * is covered by `app.css`'s `prefers-reduced-motion` opt-out.
   */
  .dispatch-card {
    border: 1px solid var(--color-border);
    border-left: 2px solid var(--color-border-strong);
    border-radius: 6px;
    background-color: var(--color-surface-raised);
    transition:
      background-color 100ms,
      border-color 100ms;
  }

  .dispatch-card-pending {
    border-left-color: var(--color-signal);
    background-color: var(--color-signal-soft);
  }

  /*
   * The "mine" reaction chip's fill — see `reactionsRow`'s comment for why
   * this is here rather than a `bg-accent/15` utility. The short version:
   * one snippet, four possible grounds, and a translucent fill takes its
   * contrast from whichever one it lands on.
   *
   * The trick is one line: an opaque `background-color` with the accent
   * tint painted over it as a `background-image`. `background-image` sits
   * *above* `background-color` on the same element, so the tint composites
   * against `--color-surface` here and never against the ground behind the
   * chip — the chip stops caring what it is sitting on. A `linear-gradient`
   * between two identical colour stops is the standard way to express "a
   * flat layer" as an image; there is no gradient in it.
   *
   * Tokens only, no literal colours (spec §3), and the tint percentages
   * are the measured ones: 15% resting and 20% on hover give accent text
   * 5.60:1 / 5.16:1 in light and 5.55:1 / 5.02:1 in dark, on every ground.
   */
  .reaction-chip-mine {
    background-color: var(--color-surface);
    background-image: linear-gradient(
      color-mix(in oklab, var(--color-accent) 15%, transparent),
      color-mix(in oklab, var(--color-accent) 15%, transparent)
    );
  }

  .reaction-chip-mine:hover {
    background-image: linear-gradient(
      color-mix(in oklab, var(--color-accent) 20%, transparent),
      color-mix(in oklab, var(--color-accent) 20%, transparent)
    );
  }

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
   * wrapping; `overflow-x: auto` + `max-width: 100%` on this container is
   * what turns "wide content" into "scrolls within the block" instead of
   * "widens the block, and with it the whole window"
   * (`document.documentElement.scrollWidth`, measured in review, grew to
   * 4700px against a 1905px viewport from a single 300-`<td>` message
   * before this fix). **This container is the scroll container for the two
   * elements whose natural rendering refuses to wrap** — a wide `<table>`'s
   * columns and `pre`'s preformatted text. `pre` also caps and scrolls
   * itself below; a `<table>` deliberately does not, and the table block
   * below says why at length.
   *
   * **Measure the reading column, not the document and not the VList
   * scroller, when you re-verify this.** Two obvious assertions are both
   * *insensitive* — they pass with the guard removed entirely:
   * `document.documentElement.scrollWidth === window.innerWidth` (because
   * `+page.svelte`'s own `min-w-0` and the scroller's forced `overflow-x`
   * absorb the overflow long before it reaches the document) and the VList
   * scroller's own `scrollWidth` (virtua sets `contain: strict` on it, so
   * it reports its `clientWidth` no matter what is inside). Measured on the
   * table fixture, guard off: document 1905, scroller 1617/1617 — both
   * unchanged and both green. The discriminating number is the **reading
   * column's** own `scrollWidth` against its `clientWidth`
   * (`.mx-auto.max-w-[calc(72ch+…)]` in the markup above): 630 = 630 with
   * the guard on, **41691** with it off, at a 1905px viewport; 412 = 412
   * versus 41675 at 700px. Both halves of the guard earn their place under
   * that measure, at different widths: `max-width` is what holds at 1905px,
   * `min-w-0` at 700px.
   *
   * `core::timeline::harden_formatted_body`'s lowered
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

  /*
   * Tables. A markdown table from an agent is the densest thing this
   * component renders, and it is *data* — the one kind of message content
   * that is read by scanning rather than by reading.
   *
   * **What was here before, and why it had to change.** The rule was
   * `display: block; overflow-x: auto; max-width: 100%`, and it did prevent
   * the blowout — by destroying the table. A `display: block` table is a
   * block box wrapping an *anonymous* table box, and that anonymous box
   * shrink-to-fits its container, so the auto table-layout algorithm
   * compresses every column to the narrowest width that fits instead of
   * overflowing and scrolling. Measured on the 4-column review table
   * (`# | Issue | Impact | Fix`) at a 1905px viewport: the `#` column came
   * out **13px** wide and `10` rendered as `1` above `0`; every row was two
   * lines tall; `Path.mkdir(parents=True)` broke across two lines and, at
   * 700px, `~/.aether/logs/run_{id}.log` across three. `overflow-wrap:
   * anywhere` above is what let it get that small: unlike `break-word`, it
   * lowers a box's *min-content* size to one character, so the table had no
   * floor to compress against.
   *
   * **What replaces it.** The table is a real `display: table` again with
   * `width: 100%`, and the collapse is fixed where the collapse came from —
   * the `overflow-wrap` inherited into the cells — rather than by taking
   * the table out of the layout algorithm:
   *   - `th`/`td` take `overflow-wrap: break-word`, which undoes the
   *     container's `anywhere`. The distinction is the whole fix:
   *     `break-word` permits a last-resort break but, unlike `anywhere`,
   *     **does not lower min-content size**. So `10`'s min-content is the
   *     whole two-character token and the `#` column cannot be squeezed
   *     below it, while `Regression risk on every manual commit` still has
   *     a min-content of just `Regression` and wraps at spaces the way a
   *     prose column should.
   *   - `code` inside a cell needs its own override, and this is not
   *     belt-and-braces: `.message-html code` below sets `overflow-wrap:
   *     anywhere` **on the code element itself**, which beats anything
   *     inherited from the cell. With the cell rule alone,
   *     `Path.mkdir(parents=True)` still broke across two lines (measured).
   *     `normal` gives the span an unbreakable min-content, so its column
   *     is never narrower than the span.
   *   - Auto table layout then does the rest: every column gets at least
   *     its min-content width, the table fits the block whenever the sum
   *     fits, and it overflows only when the content genuinely cannot fit —
   *     into `.message-html`'s `overflow-x: auto` above. That is why there
   *     is deliberately no `overflow-x` or `max-width` on the table itself
   *     any more: **the scroll container is the message body, not the
   *     table.**
   *   - `width: 100%` makes a table that fits stretch to the width of its
   *     message block rather than huddling at its content width. Note what
   *     that is 100% *of*: the block is itself content-sized, so a message
   *     holding nothing but a two-column key/value table stays 150px wide
   *     (measured) — the table fills whatever the rest of the message has
   *     already claimed.
   * The review table, re-measured: `#` column 41px with `10` and `11` on
   * one line, both code spans on one line, the prose column wrapping to
   * three lines, table width = block width, **no scroll** — and the reading
   * column still 630 = 630.
   *
   * **`min-width: max-content` is not the floor to use**, though this rule
   * carried it for one commit. max-content is "the width at which nothing
   * needs to break", so it does not raise the floor, it bans wrapping: the
   * same review table went to 1039px and scrolled inside a 566px block with
   * the prose column forced onto a single line. Horizontal scrolling is the
   * worst reading mode in a timeline and nearly every real table has a
   * prose column, so that fires constantly. min-content is the floor; the
   * `overflow-wrap` rules above are what make min-content mean something.
   *
   * **What still scrolls, and why it has to.** A cell holding one very long
   * unbroken token — a 5001-character digest, measured — makes the table
   * 37,602px wide and the message body scrolls, rather than breaking the
   * token. That follows from the same property that fixes the `#` column:
   * `break-word` does not lower min-content, and a column can never be
   * narrower than its min-content, so the column is as wide as the token.
   * The only way to break it is `anywhere` in cells, which is exactly what
   * collapsed `10` into `1` over `0`. Capping cells with `max-width: 48ch`
   * was measured as an escape and is worse: Chrome honours it on the box
   * (table back to 566px) but the text simply spills out of the cell and
   * past the grid — 34,628px of scrollable overflow with no border
   * containing it. An honest scroll beats content escaping its own table.
   *
   * **Three shapes that look right and are not** (all measured in Chrome
   * before this was written, not reasoned about):
   *   - `display: block; width: max-content; max-width: 100%; overflow-x:
   *     auto` — the widely-copied recipe, and it changes nothing: the block
   *     is clamped to 100% by `max-width`, the anonymous table inside
   *     resolves against that clamped box, and the columns compress exactly
   *     as before (`#` column 34px, rows still two lines).
   *   - `display: block` plus `table > * { display: table; width:
   *     max-content }` — this *does* scroll, but it turns `thead` and
   *     `tbody` into two independent tables whose columns are sized
   *     separately: header cells landed at x = 0/30/85/153 over body cells
   *     at x = 0/38/250/525. A table whose header does not sit over its own
   *     column is worse than one that is merely narrow.
   *   - A wrapper `<div class="table-scroll">` around each table, which is
   *     the conventional fix, is not available: this HTML arrives through
   *     `{@html}` from a core-sanitised string, and adding elements to it
   *     means changing `harden_formatted_body` on the Rust side.
   *
   * **The trade this makes, stated plainly.** A table only scrolls when the
   * sum of its columns' min-content widths exceeds the block — a genuinely
   * unfittable table (30 columns, or an unbreakable token). Everything else
   * wraps in place, which is the behaviour Element has and the one a
   * timeline wants. When it does scroll, the affordance is whatever the
   * platform gives `.message-html`'s scrollbar, which on an
   * overlay-scrollbar webview is nothing until you touch it — the same deal
   * `pre` has had all along.
   *
   * **Type ranks.** Cells leave the serif reading rank for `--font-sans`:
   * serif is this app's face for *prose you read*, and a table is data you
   * scan (spec §4). Not mono either — mono is reserved here for machine
   * text, and a whole table set in it reads as a code block and costs about
   * a sixth more width per column; `code` inside a cell still switches to
   * mono under its own rule below, which is exactly the distinction worth
   * keeping visible. Header cells *are* labels, so they take the mono
   * label treatment the date divider and the dispatch card already use —
   * uppercase, letter-spaced, 500. `tabular-nums` because the first column
   * of an agent's table is nearly always a row number.
   *
   * **Grid, and why its colour is a `currentColor` mix rather than
   * `--color-border`.** Every cell carries a full 1px border, not just a
   * header rule and row hairlines: a data table is scanned across as well
   * as down, and the vertical rules are what keep a wide scrolled table's
   * columns readable. The weight is `--color-border`'s — every figure below
   * is a painted pixel read out of a screenshot composited into a canvas,
   * never `getComputedStyle`, since a `color-mix(…, transparent)` has no
   * `backgroundColor` to parse. On the peer reading surface the mix reads
   * **1.36:1** in light and 1.48:1 in dark against the token's 1.31:1 /
   * 1.30:1 — the same hairline, a shade hotter in dark. But it cannot *be*
   * `--color-border`, for exactly the reason this whole block reads colour
   * through `currentColor`: an own message sits on `--color-accent-soft`,
   * where the token measures **1.13:1 in light and 1.01:1 in dark** — a
   * grid that is, in dark, simply not painted. The mix lands at 1.36:1 and
   * 1.52:1 on that same ground, because it follows the ground it is on.
   */
  :global(.message-html table) {
    width: 100%;
    border-collapse: collapse;
    font-family: var(--font-sans);
    font-size: 0.9em;
    line-height: 1.45;
    font-variant-numeric: tabular-nums;
  }

  :global(.message-html th),
  :global(.message-html td) {
    overflow-wrap: break-word;
    border: 1px solid color-mix(in srgb, currentColor 15%, transparent);
    padding: 0.7em 0.9em;
    text-align: left;
    vertical-align: top;
  }

  /* Overrides `.message-html code`'s own `overflow-wrap: anywhere` below,
   * which is set on the code element and so outranks the cell's rule above
   * by inheritance. Without this line a code span in a cell still splits
   * mid-token — the exact `Path.mkdir(parents=` / `True)` break this pass
   * set out to fix. `normal` rather than `nowrap`: it leaves a genuine
   * break opportunity usable as a last resort, and measured on every
   * fixture the two are identical. */
  :global(.message-html th code),
  :global(.message-html td code) {
    overflow-wrap: normal;
  }

  /* Left-aligned like the body cells, not centred (the UA default): a
   * centred header sitting over left-aligned data breaks the column's
   * reading edge, which is the one thing a scanned table cannot afford.
   *
   * The padding numbers differ from the body cells' only because they are
   * expressed in this rank's own, smaller `em` — 0.9/1.15em of 10.53px
   * lands at 9.48px/12.11px against a body cell's 9.45px/12.15px, i.e. the
   * same painted gutter. The header row still comes out 4px shorter (35px
   * against 39px), which is the label rank being smaller and is meant. */
  :global(.message-html thead th) {
    font-family: var(--font-mono);
    font-size: 0.78em;
    font-weight: 500;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    padding: 0.9em 1.15em;
    background: color-mix(in srgb, currentColor 6%, transparent);
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

  /*
   * Emphasis inside inline code, which italics cannot express here.
   *
   * `code` below switches the run to `--font-mono`, and this app bundles
   * IBM Plex Mono at 400 and 500 only — no italic file, deliberately (spec
   * §4, §6.3: mono means machine, and mono ranks carry no italic).
   * `font-synthesis: none` in `app.css` means the missing face is *not*
   * faked, so `<em><code>x</code></em>` in a sender's formatted body
   * previously composed to "mono italic" and rendered plainly upright: the
   * emphasis vanished with no fallback at all.
   *
   * The fix uses the one emphasis channel the face actually has. 500 is a
   * real bundled weight, so it renders; `font-style: normal` is explicit
   * rather than incidental, so the declaration says out loud that the
   * italic is being dropped on purpose rather than inherited by accident.
   *
   * The residual, stated plainly: `<strong><code>` asks for 600, which CSS
   * font matching resolves to the same bundled 500, so emphasis and strong
   * emphasis are indistinguishable *inside inline code*. Two ranks
   * collapsing into one is a real loss and it is not fixable without
   * either bundling a mono italic (which the design refuses) or inventing
   * a non-typographic marker for `em` (a background, a rule) that would
   * collide with the `code` chip's own ground and the link underline. One
   * visible rank beats one silently discarded one.
   */
  :global(.message-html em code),
  :global(.message-html code em) {
    font-style: normal;
    font-weight: 500;
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

{#if pickingReactionFor !== null}
  <!--
    Mounted once for the whole timeline rather than per row: only one picker
    can be open, and a row that mounts a dialog is a row whose height depends
    on state the virtualizer cannot see.
  -->
  <EmojiPicker
    onPick={(emoji) => {
      const target = pickingReactionFor;
      pickingReactionFor = null;
      if (target !== null) handleToggleReaction(target, emoji);
    }}
    onClose={() => (pickingReactionFor = null)}
  />
{/if}
