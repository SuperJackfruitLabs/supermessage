<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { message, type ItemOverrides } from "$lib/fixtures";
  import { createTimelineStore, type TimelineStoreDeps } from "$lib/stores/timeline.svelte";
  import type { DiffEnvelope } from "$lib/stores/diff";
  import type { LivePayload, TimelineRow, TurnSnapshot, TurnUpdate } from "$lib/ipc";
  import { createLiveStore } from "$lib/stores/live.svelte";
  import { turnStep, turnSummary } from "$lib/fixtures";
  import { storyServices } from "./live/liveCardStoryServices";

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
    ...Array.from({ length: 30 }, (_, i) => atlas(`f${i}`, 10_000 + i * 1_000, `Filler message number ${i}.`)),
    {
      ...mine("r1", 400_000, "Replying to the plan.", {
        replyTo: { eventId: "$q0", available: true, sender: "@atlas:example.org", senderDisplayName: "Atlas", body: null } as never,
      }),
      replyQuote: { state: "available", sender: "Atlas", excerpt: "Parent: the plan for the release.", label: null },
    },
  ];

  /**
   * A turn mirror the story drives by hand: the channels the real one reads
   * are handlers it can call, and `turn()` answers with the snapshot given.
   */
  function liveHarness(snapshot: TurnSnapshot | null = null) {
    let onTurn: (u: TurnUpdate) => void = () => {};
    let onLive: (p: LivePayload) => void = () => {};
    const quiet = async () => () => {};
    const store = createLiveStore({
      onTurn: async (h) => {
        onTurn = h;
        return () => {};
      },
      onLive: async (h) => {
        onLive = h;
        return () => {};
      },
      onThought: quiet,
      turn: async () => snapshot,
    });
    void store.focus(ROOM);
    let seq = 0;
    return {
      store,
      turn: (u: TurnUpdate) => onTurn(u),
      answer: (text: string, done = false) => onLive({ roomId: ROOM, seq: ++seq, text, done }),
    };
  }

  const finishedSnapshot = (over: Partial<ReturnType<typeof turnSummary>> = {}): TurnSnapshot => ({
    summary: turnSummary({
      turnId: 7,
      revision: 5,
      inProgress: false,
      endedBy: "done",
      lastActivityMs: 1_700_000_012_000,
      hasThought: true,
      totalSteps: 2,
      completedSteps: 2,
      recordLabel: "Thought for 12s · 2 steps",
      ...over,
    }),
    steps: [
      turnStep({ id: "a", title: "read the release notes", seq: 1 }),
      turnStep({ id: "b", title: "run the 16KB check", seq: 2, kind: "execute" }),
    ],
    thought: "The notes name the 16KB device as the open risk.",
  });

  const finishedHarness = liveHarness(finishedSnapshot());
  const bridgeHarness = liveHarness(null);
  const replyHarness = liveHarness(null);
  const probeHarness = liveHarness(null);
  const awayHarness = liveHarness(null);

  let arrivePush: (row: TimelineRow) => void = () => {};
  let awayPush: (row: TimelineRow) => void = () => {};

  const rowEls = (root: Element) =>
    [...root.querySelectorAll("[data-testid='timeline-row']")] as HTMLElement[];
  const liveCard = (root: Element) => root.querySelector("[data-testid='live-card']") as HTMLElement | null;
  const recordEls = (root: Element) => [...root.querySelectorAll("[data-testid='what-i-did']")] as HTMLElement[];
  const motionAllowed = () => !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /** The properties an animation inside the timeline may touch. */
  const ALLOWED_PROPS = new Set(["opacity", "transform", "offset", "easing", "composite", "computedOffset"]);

  interface Frame {
    t: number;
    /** Top of the live row and bottom of the history row just above it. */
    liveTop: number | null;
    historyBottom: number | null;
    historyTop: number | null;
    /** Distance of the scroller from its tail. */
    fromTail: number;
    firstVisibleTop: number | null;
    badProps: string[];
    glides: number;
  }

  /** Records one entry per animation frame until `stop()`. */
  function sampleFrames(root: HTMLElement) {
    const frames: Frame[] = [];
    let running = true;
    const scroller = root.querySelector(".timeline-scroller") as HTMLElement;
    // Measured in a task right after the frame is painted, not inside the
    // `requestAnimationFrame` callback: that runs before the frame's layout and
    // its resize observers, so it would read a state no frame ever shows.
    const record = (t: number) => {
      const rows = rowEls(root);
      const card = liveCard(root);
      const liveRow = card?.closest("[data-testid='timeline-row']") as HTMLElement | null;
      const i = liveRow ? rows.indexOf(liveRow) : -1;
      const history = i > 0 ? rows[i - 1]! : null;
      const view = scroller.getBoundingClientRect();
      const firstVisible = rows.find((r) => r.getBoundingClientRect().bottom > view.top + 1);
      const badProps: string[] = [];
      let glides = 0;
      for (const a of document.getAnimations()) {
        const effect = a.effect as KeyframeEffect | null;
        const target = effect?.target;
        if (!effect || !target || !scroller.contains(target)) continue;
        // Reduced motion zeroes every duration to 0.01ms (app.css): such a transition
        // of height or visibility is a style change, not an animation anyone sees.
        if (Number(effect.getComputedTiming().duration) <= 1) continue;
        if (effect.composite === "add") glides += 1;
        for (const frame of effect.getKeyframes())
          for (const prop of Object.keys(frame))
            if (!ALLOWED_PROPS.has(prop))
              badProps.push(`${prop} on <${target.tagName.toLowerCase()} class="${(target as HTMLElement).className}">`);
      }
      frames.push({
        t,
        liveTop: liveRow ? liveRow.getBoundingClientRect().top : null,
        historyBottom: history ? history.getBoundingClientRect().bottom : null,
        historyTop: history ? history.getBoundingClientRect().top : null,
        fromTail: scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight,
        firstVisibleTop: firstVisible ? firstVisible.getBoundingClientRect().top : null,
        badProps,
        glides,
      });
    };
    const loop = (t: number) => {
      setTimeout(() => record(t), 0);
      if (running) requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
    return {
      frames,
      stop: () => {
        running = false;
      },
    };
  }

  const SENTENCES = [
    "Staging is green across all four ABIs, and the promotion window opens at nine.",
    "The 16KB device is the one left to check before anything ships to the store.",
    "I compared the release notes with the build manifest and found one stale entry.",
    "That entry names a build number that was superseded on Tuesday afternoon.",
    "Correcting it is a one-line change, and I have already prepared the patch.",
    "After that the only open item is the rollout percentage for the first day.",
    "I would start at five percent and widen it once the crash-free rate holds.",
  ];

  /** Grows the answer every 450 ms, like iOS `-fixtureStreaming`'s pace. */
  async function streamAnswer(h: ReturnType<typeof liveHarness>, startedAt = 1_700_000_000_000) {
    h.turn({
      summary: turnSummary({ turnId: 3, revision: 1, startedAtMs: startedAt, lastActivityMs: startedAt, answering: true, currentStepTitle: "Writing the answer" }),
      step: null,
    });
    let text = "";
    for (let i = 0; i < SENTENCES.length; i++) {
      text += (i > 0 ? "\n\n" : "") + SENTENCES[i];
      h.answer(text);
      await new Promise((r) => setTimeout(r, 450));
    }
  }

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
    arrivePush(atlas("n1", 700_000, "Promoted. Build 412 is live."));
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
    awayPush(atlas("n2", 800_000, "Landed while you were away."));
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

<!--
  A finished turn has no row: its record is drawn inside the answer's own row,
  between the sender's line and the text, collapsed.
-->
<Story
  name="A finished turn's record in its answer"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => (recordEls(root).length > 0 ? root : null), 4000, "the record");
    if (liveCard(root)) throw new Error("a finished turn has no card");
    const records = recordEls(root);
    if (records.length !== 1) throw new Error(`${records.length} records, want one`);
    const row = records[0]!.closest("[data-testid='timeline-row']")!;
    if (!row.textContent?.includes("Corrected the build number.")) throw new Error("the record is not in the newest peer row");
    if (rowEls(root).filter((r) => r !== row && r.querySelector("[data-testid='what-i-did']")).length) throw new Error("another row carries it");
    if (records[0]!.querySelector("summary")?.textContent?.trim() !== "Thought for 12s · 2 steps") throw new Error("the core's label");
    if ((records[0] as HTMLDetailsElement).open) throw new Error("collapsed");
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore()} live={finishedHarness.store} liveServices={storyServices} />
    </div>
  {/snippet}
</Story>

<!--
  RF1: a bridge that never sends deltas, only edits its progress message. The
  core has no turn for it: no card, no record. The edited row is just a message.
-->
<Story
  name="A bridge that only edits its progress message"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => (rowEls(root).some((r) => r.textContent?.includes("Searching files")) ? root : null), 3000, "the edited message");
    await new Promise((r) => setTimeout(r, 300));
    if (liveCard(root)) throw new Error("no turn, no card");
    if (recordEls(root).length) throw new Error("no turn, no record");
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline
        roomId={ROOM}
        store={seededStore([
          mine("b0", 0, "Find where the tokens are generated."),
          atlas("b1", 20_000, "🔎 Searching files…", { edited: true }),
        ])}
        live={bridgeHarness.store}
        liveServices={storyServices}
      />
    </div>
  {/snippet}
</Story>

<!--
  RF1: a turn the core ended by the agent's reply. The card goes at that moment
  and the record appears in the answer's row.
-->
<Story
  name="A turn the core ended by a reply"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => (rowEls(root).length >= 2 ? root : null), 3000, "the rows");
    replyHarness.turn({ summary: turnSummary({ turnId: 9, revision: 1, currentStepTitle: "Starting" }), step: null });
    await settled(() => liveCard(root), 2000, "the live card");
    if (recordEls(root).length) throw new Error("no record while the turn is going");
    replyHarness.turn({
      summary: turnSummary({
        turnId: 9,
        revision: 2,
        inProgress: false,
        endedBy: "reply",
        hasThought: false,
        totalSteps: 1,
        completedSteps: 1,
        recordLabel: "1 step · 12s",
      }),
      step: { index: 0, step: turnStep({ id: "r1", title: "read the release notes" }) },
    });
    await settled(() => (!liveCard(root) && recordEls(root).length === 1 ? root : null), 2000, "the card to go and the record to show");
    const row = recordEls(root)[0]!.closest("[data-testid='timeline-row']")!;
    if (!row.textContent?.includes("design/tokens.toml")) throw new Error("the record is not in the newest peer row");
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline
        roomId={ROOM}
        store={seededStore([
          mine("c0", 0, "Find where the tokens are generated."),
          atlas("c1", 20_000, "They come from design/tokens.toml through the generator."),
        ])}
        live={replyHarness.store}
        liveServices={storyServices}
      />
    </div>
  {/snippet}
</Story>

<!--
  The probe (Review Focus 5): the live card is a row of the virtual list. As the
  answer grows every 450 ms for 3 s, on every animation frame the newest history
  row meets the card's row with no gap and no overlap, nothing but opacity and
  transform animates, and a reader at the tail stays at the tail. With motion
  allowed (`SNAPSHOT_MOTION=allow`) the history also glides (0.32 s, `glide.ts`):
  no long frame and no jump larger than a glide step.
-->
<Story
  name="Timeline — a streaming turn"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => (rowEls(root).length >= jumpRows.length ? root : null), 4000, "the history");
    await new Promise((r) => setTimeout(r, 400));
    const sampler = sampleFrames(root);
    await streamAnswer(probeHarness, Date.now());
    await new Promise((r) => setTimeout(r, 500));
    sampler.stop();
    const frames = sampler.frames.filter((f) => f.liveTop !== null);
    if (frames.length < 20) throw new Error(`only ${frames.length} frames with the card`);

    // (a) no gap, no overlap between the newest history row and the card's row.
    for (const f of frames) {
      if (f.historyBottom === null || Math.abs(f.historyBottom - f.liveTop!) > 1)
        throw new Error(`history bottom ${f.historyBottom} vs card top ${f.liveTop}`);
    }
    // (b) nothing but opacity and transform animates.
    const bad = frames.flatMap((f) => f.badProps);
    if (bad.length) throw new Error(`animated ${[...new Set(bad)].join(", ")}`);
    // (c) the reader stays at the tail.
    const off = Math.max(...frames.map((f) => f.fromTail));
    if (off > 1) {
      const bad = frames.map((f, i) => [i, Math.round(f.fromTail)] as const).filter(([, d]) => d > 1);
      throw new Error(`left the tail by ${off}px in ${bad.length} of ${frames.length} frames: ${JSON.stringify(bad.slice(0, 12))}`);
    }

    const glides = Math.max(...frames.map((f) => f.glides));
    if (!motionAllowed()) {
      if (glides !== 0) throw new Error("reduced motion must not glide");
      return;
    }
    // Motion allowed: the stutter check.
    if (glides === 0) throw new Error("the history never glided");
    // Skip the first frames: mounting the card is not streaming.
    const steady = frames.slice(3);
    const dts = steady.slice(1).map((f, i) => f.t - steady[i]!.t);
    const longFrames = dts.filter((dt) => dt > 50);
    if (longFrames.length) throw new Error(`${longFrames.length} long frames, worst ${Math.max(...longFrames).toFixed(0)}ms`);
    const jumps = steady.slice(1).map((f, i) => Math.abs(f.historyTop! - steady[i]!.historyTop!));
    const worst = Math.max(...jumps);
    if (worst > 12) {
      const at = jumps.map((j, i) => [i, +j.toFixed(1), +dts[i]!.toFixed(0), +steady[i + 1]!.historyTop!.toFixed(1)] as const).filter(([, j]) => j > 5);
      throw new Error(`the history jumped ${worst.toFixed(1)}px in one frame: [frame, px, dtMs, top] ${JSON.stringify(at.slice(0, 14))}`);
    }
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore(jumpRows)} live={probeHarness.store} liveServices={storyServices} />
    </div>
  {/snippet}
</Story>

<!--
  The reader has scrolled back 400px: the card grows below the fold and the
  first visible row does not move.
-->
<Story
  name="Timeline — a streaming turn, scrolled back"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const scroller = (await settled(() => root.querySelector(".timeline-scroller"), 3000, "the scroller")) as HTMLElement;
    await settled(() => (root.textContent?.includes("Replying to the plan.") ? root : null), 4000, "the newest row");
    await new Promise((r) => setTimeout(r, 400));
    scroller.dispatchEvent(new WheelEvent("wheel", { deltaY: -400, bubbles: true }));
    scroller.scrollTop = Math.max(0, scroller.scrollTop - 400);
    await settled(
      () => [...root.querySelectorAll("button")].find((b) => /newest|jump/i.test(b.getAttribute("aria-label") ?? b.textContent ?? "")),
      3000,
      "the jump-to-newest button",
    );
    await new Promise((r) => setTimeout(r, 300));
    const sampler = sampleFrames(root);
    // The card is not on screen, so its row is not what is measured: the first visible row is.
    await new Promise((r) => setTimeout(r, 100));
    const before = sampler.frames.at(-1)!.firstVisibleTop;
    await streamAnswer(awayHarness, Date.now());
    await new Promise((r) => setTimeout(r, 400));
    sampler.stop();
    if (before === null) throw new Error("no visible row");
    const moved = Math.max(...sampler.frames.map((f) => Math.abs((f.firstVisibleTop ?? before) - before)));
    if (moved > 1) throw new Error(`the first visible row moved ${moved}px while the card grew`);
    const bad = sampler.frames.flatMap((f) => f.badProps);
    if (bad.length) throw new Error(`animated ${bad.join(", ")}`);
    if (sampler.frames.some((f) => f.glides > 0)) throw new Error("a scrolled-back reader's history must not glide");
  }}
>
  {#snippet template()}
    <div class="flex h-[560px] w-[760px] flex-col bg-surface">
      <Timeline roomId={ROOM} store={seededStore(jumpRows)} live={awayHarness.store} liveServices={storyServices} />
    </div>
  {/snippet}
</Story>
