<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { message, type ItemOverrides } from "$lib/fixtures";
  import { createTimelineStore, type TimelineStoreDeps } from "$lib/stores/timeline.svelte";
  import type { DiffEnvelope } from "$lib/stores/diff";
  import type { TimelineRow } from "$lib/ipc";

  import Timeline from "./Timeline.svelte";

  const ROOM = "!story:example.org";
  const T0 = 1_700_000_000_000;

  // `message()` with the pieces a real row has: an event id (so the pill
  // renders), a sender and a sender face initial.
  const row = (overrides: ItemOverrides, initial = "?"): TimelineRow => ({
    ...message({ eventId: `$${overrides.id}`, ...overrides }),
    senderInitial: initial,
    view: {
      render: "bubble",
      muted: false,
      blocks: [{ block: "paragraph", inlines: [{ inline: "text", text: overrides.body ?? "" }] }],
    },
  });
  const atlas = (id: string, at: number, body: string, extra: Partial<ItemOverrides> = {}) =>
    row({ id, sender: "@atlas:example.org", senderDisplayName: "Atlas", timestampMs: T0 + at, body, ...extra }, "A");
  const mine = (id: string, at: number, body: string, extra: Partial<ItemOverrides> = {}) =>
    row({ id, isOwn: true, sender: "@me:example.org", timestampMs: T0 + at, body, ...extra }, "M");

  /**
   * The grouping is the core's: a row arrives knowing it continues the run
   * above it. These rooms are written by hand, so each continuation says so.
   */
  const continued = (r: TimelineRow): TimelineRow => ({ ...r, continuesRun: true });
  /** ...and the last row of a run says so (the wire omits `false`). */
  const ends = (r: TimelineRow): TimelineRow => ({ ...r, endsRun: true });

  /**
   * One room that shows every row the task 8 rules touch: a peer run (the
   * second message continues it, the third was edited), then an own run
   * (the first is edited, the second has reactions and readers, with and
   * without a count), then an own send in flight and one that failed in the
   * middle of the run, which must say so.
   */
  const rows: TimelineRow[] = [
    atlas("p1", 0, "Staging is green across all four ABIs."),
    continued(atlas("p2", 60_000, "Ready to promote.")),
    ends(continued(atlas("p3", 90_000, "Corrected the build number.", { edited: true }))),
    mine("o1", 600_000, "Hold on, check the 16KB device first.", { edited: true }),
    continued(
      mine("o2", 630_000, "Then promote.", {
        reactions: [
          { key: "👍", displayKey: "👍", count: 1, byMe: false, senders: [] },
          { key: "🎉", displayKey: "🎉", count: 3, byMe: true, senders: [] },
        ],
        readBy: ["@atlas:example.org", "@ghost:example.org"],
      }),
    ),
    continued(mine("o3", 640_000, "Sending this one.", { sendState: "notSentYet", eventId: null })),
    ends(continued(mine("o4", 650_000, "This one failed.", { sendState: "sendingFailed", eventId: null }))),
  ];

  /** A store the story fills through the real diff path: a gap forces a resync. */
  function seededStore(source: TimelineRow[] = rows, expose?: (push: (row: TimelineRow) => void) => void) {
    let handler: ((env: DiffEnvelope<TimelineRow>) => void) | null = null;
    const noop = async () => undefined;
    const deps = {
      timelineSubscribe: noop,
      timelinePaginateBack: async () => true,
      timelineResync: async () => [ROOM, 1, source] as [string, number, TimelineRow[]],
      sendMessage: noop,
      sendReply: noop,
      toggleReaction: async () => true,
      setTyping: noop,
      markRoomRead: async () => true,
      onTimelineDiff: async (h: (env: DiffEnvelope<TimelineRow>) => void) => {
        handler = h;
        return () => {};
      },
    } as unknown as TimelineStoreDeps;
    const store = createTimelineStore(deps);
    // After the resync (seq 1), the next envelope is seq 2.
    let seq = 1;
    expose?.((value) => {
      if (handler === null) throw new Error("the store is not subscribed yet");
      handler({ channel: "timeline", subject: ROOM, seq: ++seq, ops: [{ op: "pushBack", value }] });
    });
    // The store listens asynchronously: wait for it, then send an envelope
    // that is out of sequence, which makes the store resync from `rows`.
    // Not during render: subscribing writes state, which a render may not do.
    setTimeout(() => {
      void store.subscribeTo(ROOM).then(async () => {
        while (handler === null) await new Promise((r) => setTimeout(r, 10));
        handler({ channel: "timeline", subject: ROOM, seq: 99, ops: [] });
      });
    }, 0);
    return store;
  }

  // Plain DOM, as MessageActions.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 3000, what = 'timeline') => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  /**
   * A long room whose last message replies to the first: the parent is far
   * above the viewport, so a jump has to scroll. Filler timestamps are
   * seconds apart so they collapse into runs and stay short.
   */
  const quoted = atlas("q0", 0, "Parent: the plan for the release.");
  const jumpRows: TimelineRow[] = [
    quoted,
    ...Array.from({ length: 30 }, (_, i) => i === 29
        ? ends(continued(atlas(`f${i}`, 10_000 + i * 1_000, `Filler message number ${i}.`)))
        : continued(atlas(`f${i}`, 10_000 + i * 1_000, `Filler message number ${i}.`))),
    {
      ...mine("r1", 400_000, "Replying to the plan.", {
        replyTo: { eventId: "$q0", available: true, sender: "@atlas:example.org", senderDisplayName: "Atlas", body: null } as never,
      }),
      endsRun: true,
      replyQuote: { state: "available", sender: "Atlas", excerpt: "Parent: the plan for the release.", label: null },
    },
  ];

  let arrivePush: (row: TimelineRow) => void = () => {};
  let awayPush: (row: TimelineRow) => void = () => {};

  const { Story } = defineMeta({ title: "Timeline/Rhythm and quiet meta", component: Timeline });
</script>

<Story
  name="Runs, own meta and reactions"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const rowsEls = (await settled(() => {
      const found = root.querySelectorAll("[data-testid='timeline-row']");
      return found.length >= rows.length ? found[0] : null;
    }, 3000, 'rows')) && [...root.querySelectorAll("[data-testid='timeline-row']")];
    // The peer's second message continues the run: its time is revealed by
    // focus, through the same `group-focus-within` path hover uses. The
    // pane re-renders as the store settles, so the row is looked up afresh
    // each attempt rather than trusting one element reference.
    const continuationRow = () =>
      root.querySelectorAll("[data-testid='timeline-row']")[1] as HTMLElement;
    if (!continuationRow().querySelector("time")) throw new Error("a peer continuation should offer a hover time");
    if (getComputedStyle(continuationRow().querySelector("time")!).opacity !== "0") {
      throw new Error("the time should rest hidden");
    }
    await settled(() => {
      const reply = [...continuationRow().querySelectorAll("button")].find(
        (b) => b.textContent?.trim() === "Reply",
      ) as HTMLElement | undefined;
      reply?.focus();
      const time = continuationRow().querySelector("time");
      return time && getComputedStyle(time).opacity === "1" ? time : null;
    }, 3000, "the focused row's time to appear");
    // The run's first message already shows its time in the header: no reveal.
    if (rowsEls[0]!.querySelector("time")) throw new Error("a run start must not offer a second time");
    (document.activeElement as HTMLElement | null)?.blur();
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore()} />
    </div>
  {/snippet}
</Story>

<!--
  Clicking a reply's quote scrolls the parent into the viewport and washes it
  for about 1.4s; the wash fades. The play drives it with a real click on the
  button and reads the parent row's class, so a quote that did nothing fails.
-->
<Story
  name="Jump to the quoted message"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const quote = (await settled(
      () => root.querySelector("button[aria-description='Shows the original message']"),
      4000,
      "the jumpable quote",
    )) as HTMLButtonElement;
    const parentRow = () =>
      [...root.querySelectorAll("[data-testid='timeline-row']")].find((r) =>
        r.textContent?.includes("Parent: the plan for the release.") && !r.querySelector("button[aria-description]"),
      ) as HTMLElement | undefined;
    quote.click();
    await settled(
      () => (parentRow()?.classList.contains("bg-accent-soft") ? root : null),
      2000,
      "the parent to be washed",
    );
    // A beat later, so a scroll that is undone by the tail-following
    // re-pin (the list snapping back to the newest message) is caught too.
    const inView = () => {
      const box = parentRow()?.getBoundingClientRect();
      const view = root.querySelector(".timeline-scroller")!.getBoundingClientRect();
      return !!box && box.top >= view.top && box.bottom <= view.bottom;
    };
    const assertParentInView = async () => {
      // The scroll is smooth when motion is allowed: let it arrive first.
      await settled(() => (inView() ? root : null), 2000, "the smooth scroll to reach the parent").catch(() => {});
      await new Promise((r) => setTimeout(r, 300));
      const parent = parentRow();
      if (!parent) throw new Error("the parent is no longer rendered");
      const box = parent.getBoundingClientRect();
      const view = root.querySelector(".timeline-scroller")!.getBoundingClientRect();
      if (box.top < view.top || box.bottom > view.bottom) throw new Error("the parent is not in view");
    };
    await assertParentInView();
    // The wash fades in over 150ms and out over 800ms, and only the washed
    // row transitions: a theme change must not fade every row.
    const duration = () => getComputedStyle(parentRow()!).transitionDuration;
    // Reduced motion (the default capture) zeroes every transition.
    const motion = !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (motion && duration() !== "0.15s") throw new Error(`wash fade-in is ${duration()}, not 0.15s`);
    await settled(() => (!parentRow()?.classList.contains("bg-accent-soft") ? root : null), 3000, "the wash to fade");
    if (motion && duration() !== "0.8s") throw new Error(`wash fade-out is ${duration()}, not 0.8s`);
    const other = [...root.querySelectorAll("[data-testid='timeline-row']")].find((r) => r !== parentRow()) as HTMLElement;
    if (motion && getComputedStyle(other).transitionDuration !== "0s")
      throw new Error(`a row that was never washed transitions: ${getComputedStyle(other).transitionDuration} ${getComputedStyle(other).transitionProperty} ${other.className}`);
    if (motion) await settled(() => (duration() === "0s" ? root : null), 3000, "the washed row to stop transitioning");
    // Jump again and stop there, so the recorded frame shows the washed parent.
    // The quote is scrolled out of view now (the parent is at the top), so
    // it is looked up afresh.
    const again = (await settled(
      () => root.querySelector("button[aria-description='Shows the original message']"),
      3000,
      "the quote to be rendered again",
    )) as HTMLButtonElement;
    again.click();
    await settled(
      () => (parentRow()?.classList.contains("bg-accent-soft") ? root : null),
      2000,
      "the parent to be washed again",
    );
    await assertParentInView();
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore(jumpRows)} />
    </div>
  {/snippet}
</Story>

<!--
  A message that lands while the reader is at the newest end rises in: the row
  gets `row-arrive`. The rows of the first fill never do. The play reads the
  class the moment the row appears, which is before it can end.
-->
<Story
  name="A new message arrives at the newest end"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const rowEls = () => [...root.querySelectorAll("[data-testid='timeline-row']")] as HTMLElement[];
    await settled(() => (rowEls().length >= rows.length ? root : null), 3000, "the first fill");
    if (rowEls().some((r) => r.classList.contains("row-arrive"))) throw new Error("the first fill animated");
    arrivePush(ends(atlas("n1", 700_000, "Promoted. Build 412 is live.")));
    const arrived = await settled(
      () => rowEls().find((r) => r.textContent?.includes("Promoted. Build 412 is live.")),
      3000,
      "the new message",
    );
    // Under reduced motion nothing rises (and nothing would drain the class).
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (arrived.classList.contains("row-arrive") === reduced) {
      throw new Error(`row-arrive=${!reduced ? "missing" : "present"} with reduced motion=${reduced}`);
    }
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore(rows, (push) => (arrivePush = push))} />
    </div>
  {/snippet}
</Story>

<!--
  The reader has scrolled away from the newest end: a message that lands
  must not animate or move anything. The play waits for the jump-to-newest
  button, appends, jumps, and checks the new row never rose.
-->
<Story
  name="A message arrives while scrolled away"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const scroller = (await settled(() => root.querySelector(".timeline-scroller"), 3000, "the scroller")) as HTMLElement;
    await settled(() => (root.textContent?.includes("Replying to the plan.") ? root : null), 3000, "the newest row");
    // A scroll only counts as the reader's after a wheel/touch/key input.
    scroller.dispatchEvent(new WheelEvent("wheel", { deltaY: -400, bubbles: true }));
    scroller.scrollTop = 0;
    const jump = (await settled(
      () => [...root.querySelectorAll("button")].find((b) => /newest|jump/i.test(b.getAttribute("aria-label") ?? b.textContent ?? "")),
      3000,
      "the jump-to-newest button",
    )) as HTMLButtonElement;
    awayPush(ends(atlas("n2", 800_000, "Landed while you were away.")));
    await new Promise((r) => setTimeout(r, 200));
    jump.click();
    const landed = await settled(
      () => [...root.querySelectorAll("[data-testid='timeline-row']")].find((r) => r.textContent?.includes("Landed while you were away.")),
      3000,
      "the new message after jumping",
    );
    if (landed.classList.contains("row-arrive")) throw new Error("a message animated while the reader was away");
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore(jumpRows, (push) => (awayPush = push))} />
    </div>
  {/snippet}
</Story>
