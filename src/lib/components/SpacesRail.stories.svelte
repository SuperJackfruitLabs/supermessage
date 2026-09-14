<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { spaceAvatarsNone, spacesFew, spacesMany } from "$lib/fixtures";

  import SpacesRail from "./SpacesRail.svelte";

  const noop = () => {};

  const { Story } = defineMeta({
    title: "Rooms/SpacesRail",
    component: SpacesRail,
    args: {
      avatars: spaceAvatarsNone,
      onSelect: noop,
      onInvitation: noop,
      onAvatarFailed: noop,
    },
  });
</script>

<Story name="A few spaces" args={{ spaces: spacesFew, selectedId: null }} />

<!--
  Includes a joined space with childCount 0, a very long name, and an
  invitation. All three look like bugs until you know the rules: 0 is the
  honest answer rather than a loading state, and an invited space is a rail
  entry that cannot be selected at all.
-->
<Story name="Many spaces" args={{ spaces: spacesMany, selectedId: null }} />

<!-- One selected, which is the only state that paints the accent. -->
<Story name="One selected" args={{ spaces: spacesMany, selectedId: "!guild:id.agentpod.dev" }} />

<!--
  There was an "All rooms selected" story here, with `selectedId: null`. It
  rendered byte-identically to "Many spaces" above, and had to: `railEntries`
  prepends the All-rooms entry with a null `spaceId`, and the rail selects on
  `entry.spaceId === selectedId`, so `selectedId: null` *is* All-rooms
  selected. "Many spaces" was already showing that state under another name.

  Removed rather than fixed, because there was nothing to fix — the state it
  named is covered. Two names for one picture is worse than one: it implies a
  distinction the component does not make, and a reviewer looking for the
  All-rooms case would have found it and moved on.
-->
