<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import {
    rosterAgentWorking,
    rosterApprovalNeeded,
    rosterInvitation,
    rosterLongName,
    rosterQuiet,
    rosterUnread,
  } from "$lib/fixtures";

  import RoomRow from "./RoomRow.svelte";

  const noop = () => {};
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
<Story name="Agent active" args={{ row: rosterAgentWorking, state: "active", describesAgent: true }} />
<Story name="Agent idle" args={{ row: rosterAgentWorking, state: "idle", describesAgent: true }} />

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
