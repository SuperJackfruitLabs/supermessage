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
   * One room that shows every row the task 8 rules touch: a peer run (the
   * second message continues it, the third was edited), then an own run
   * (the first is edited, the second has reactions and readers, with and
   * without a count), then an own send in flight and one that failed in the
   * middle of the run, which must say so.
   */
  const rows: TimelineRow[] = [
    atlas("p1", 0, "Staging is green across all four ABIs."),
    atlas("p2", 60_000, "Ready to promote."),
    atlas("p3", 90_000, "Corrected the build number.", { edited: true }),
    mine("o1", 600_000, "Hold on, check the 16KB device first.", { edited: true }),
    mine("o2", 630_000, "Then promote.", {
      reactions: [
        { key: "👍", displayKey: "👍", count: 1, byMe: false, senders: [] },
        { key: "🎉", displayKey: "🎉", count: 3, byMe: true, senders: [] },
      ],
      readBy: ["@atlas:example.org", "@ghost:example.org"],
    }),
    mine("o3", 640_000, "Sending this one.", { sendState: "notSentYet", eventId: null }),
    mine("o4", 650_000, "This one failed.", { sendState: "sendingFailed", eventId: null }),
  ];

  /** A store the story fills through the real diff path: a gap forces a resync. */
  function seededStore() {
    let handler: ((env: DiffEnvelope<TimelineRow>) => void) | null = null;
    const noop = async () => undefined;
    const deps = {
      timelineSubscribe: noop,
      timelinePaginateBack: async () => true,
      timelineResync: async () => [ROOM, 1, rows] as [string, number, TimelineRow[]],
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
