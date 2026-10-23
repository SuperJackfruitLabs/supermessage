<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    agentAvatarVariantsFixture,
    rosterAgentPreview,
    rosterAgentWorking,
    rosterApprovalNeeded,
    rosterInvitation,
    rosterLongName,
    rosterQuiet,
    rosterUnread,
    rosterUnreadOver99,
  } from "$lib/fixtures";

  import RoomRow from "./RoomRow.svelte";

  const noop = () => {};
  const tile = agentAvatarVariantsFixture[0]!;
  const tileRaised = agentAvatarVariantsFixture[3]!;

  const settled = async (find: () => unknown, what: string) => {
    const deadline = Date.now() + 3000;
    while (!find()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 25));
    }
  };
  const rows = (root: HTMLElement) => [...root.querySelectorAll<HTMLButtonElement>("button")];
  // Fixed, so the age labels are stable across runs rather than drifting
  // with the clock — which is what made the DOM baseline so fragile.
  const NOW = 1_700_000_300_000;

  const { Story } = defineMeta({
    title: "Roster/RoomRow",
    component: RoomRow,
    args: { now: NOW, selected: false, avatarUrl: null, onSelect: noop, onAvatarFailed: noop },
  });
</script>

<!-- Quiet draws a TRANSPARENT dot rather than nothing, so names stay
     aligned down the column. Absence is not a state worth a mark; it is
     also not a reason to move everything. -->
<Story name="Quiet" args={{ row: rosterQuiet, state: "quiet", describesAgent: false }} />

<!-- `describesAgent` is what `core::roster::describes_agent` would say of
     the row: true for one with a role or glyph (an agent's room), false for
     a plain name such as "Research" (a room of people). -->
<Story name="Agent active" args={{ row: rosterAgentWorking, state: "active", describesAgent: true, avatar: tile }} />
<Story name="Agent idle" args={{ row: rosterAgentWorking, state: "idle", describesAgent: true, avatar: tile }} />

<!-- The same "idle" state on a room of people draws no dot: "idle" says
     nothing a reader wants to know about people. -->
<Story name="People room idle" args={{ row: rosterQuiet, state: "idle", describesAgent: false }} />

<!-- The only place amber appears in the roster, and it means what it means
     everywhere: something is owed. The left border carries it too. -->
<Story name="Approval needed" args={{ row: rosterApprovalNeeded, state: "needsYou", describesAgent: false }} />

<Story name="Unread count" args={{ row: rosterUnread, state: "quiet", describesAgent: false }} />

<!-- An invitation reads as a room in every other respect — name, avatar,
     position — so without the outlined pill the only honest thing about the
     row is what happens when you open it. Outlined, not filled: it is a
     state, and the filled accent pill belongs to the unread count. -->
<Story name="Invitation" args={{ row: rosterInvitation, state: "quiet", describesAgent: false }} />

<Story name="Selected" args={{ row: rosterQuiet, state: "quiet", describesAgent: false, selected: true }} />
<Story name="Long name" args={{ row: rosterLongName, state: "quiet", describesAgent: false }} />

<!--
  The states a row arrives in, together (iOS RoomRowView previews): two lines each, the dot on
  the avatar, the time accent when unread, the role when the room has said nothing, a blank
  line when neither.
-->
{#snippet everyState()}
  <div class="flex w-[360px] flex-col bg-surface font-sans text-content">
    <RoomRow row={rosterApprovalNeeded} state="needsYou" describesAgent={true} avatar={tileRaised} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterAgentPreview} state="active" describesAgent={true} avatar={tile} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterAgentWorking} state="idle" describesAgent={true} avatar={agentAvatarVariantsFixture[2]!} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterAgentWorking} state="quiet" describesAgent={true} avatar={agentAvatarVariantsFixture[1]!} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterUnreadOver99} state="quiet" describesAgent={false} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterInvitation} state="quiet" describesAgent={false} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    <RoomRow row={rosterQuiet} state="quiet" describesAgent={false} selected={true} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
  </div>
{/snippet}

<Story
  name="Every state"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => rows(root).length === 7, "seven rows");
    const [needs, preview, idle, quiet, many, invited] = rows(root);
    const dotOf = (b: HTMLElement | undefined) => b!.querySelector("[data-testid='state-dot']");
    if (!dotOf(needs)?.className.includes("bg-signal")) throw new Error("Needs you: a signal dot on the avatar");
    if (!dotOf(preview)?.className.includes("bg-ok")) throw new Error("Active: an ok dot");
    if (!dotOf(idle)?.className.includes("bg-content-faint")) throw new Error("Idle: a faint dot");
    if (dotOf(quiet)) throw new Error("Quiet draws no dot");
    for (const b of [needs, preview, idle, quiet]) if (!b!.querySelector("[data-testid='agent-badge']")) throw new Error("agents wear the badge");
    if (many!.textContent?.includes("99+") !== true) throw new Error("140 unread reads 99+");
    if (!invited!.textContent?.includes("Invitation")) throw new Error("the invitation pill");
    for (const b of rows(root)) {
      if (b.querySelectorAll("[data-testid='row-second-line']").length !== 1) throw new Error("two lines: one second line");
    }
    if (!idle!.querySelector("[data-testid='row-second-line']")!.textContent?.includes("OPENCLAW")) throw new Error("the role fills a silent room's second line");
    if (preview!.textContent?.includes("CLAUDE-CODE")) throw new Error("the runtime is out of the row");
    if (!preview!.getAttribute("aria-label")?.includes("Agent, active")) throw new Error("the row's name says Agent and the state");
  }}
>
  {#snippet template()}{@render everyState()}{/snippet}
</Story>

<Story name="Every state, dark">
  {#snippet template()}<div data-appearance="dark">{@render everyState()}</div>{/snippet}
</Story>

<!-- The reader turned the dots off: not even a debt is drawn. -->
<Story
  name="State dot hidden"
  args={{ row: rosterApprovalNeeded, state: "needsYou", describesAgent: true, avatar: tile, showsState: false }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => rows(root).length === 1, "the row");
    if (root.querySelector("[data-testid='state-dot']")) throw new Error("no dot when hidden");
    if (!root.querySelector("[data-testid='agent-badge']")) throw new Error("the badge is not a state");
  }}
/>

<!-- 150 % text in a 600px window: the name and the preview truncate, nothing overflows. -->
<Story
  name="Two lines at 600px and Largest"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await settled(() => rows(root).length === 2, "two rows");
    for (const b of rows(root)) {
      if (b.scrollWidth > b.clientWidth + 1) throw new Error(`a row overflows: ${b.scrollWidth} > ${b.clientWidth}`);
      const lines = b.querySelector("span.flex-col")!.children;
      if (lines.length !== 2) throw new Error("still two lines");
    }
    const name = rows(root)[0]!.querySelector<HTMLElement>("span.truncate")!;
    if (name.scrollWidth <= name.clientWidth) throw new Error("the long name should truncate");
  }}
>
  {#snippet template()}
    <div data-testid="frame" class="flex flex-col bg-surface font-sans text-content" style="zoom: 1.5; width: 400px;">
      <RoomRow row={rosterLongName} state="active" describesAgent={true} avatar={tile} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
      <RoomRow row={rosterUnreadOver99} state="quiet" describesAgent={false} selected={false} avatarUrl={null} now={NOW} onSelect={noop} onAvatarFailed={noop} />
    </div>
  {/snippet}
</Story>
