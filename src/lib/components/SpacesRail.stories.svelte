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

<!-- "All rooms" selected: spaceId is null, which is its own case. -->
<Story name="All rooms selected" args={{ spaces: spacesMany, selectedId: null }} />
