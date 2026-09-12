<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import { searchHits, searchNoHits } from "$lib/fixtures";
  import { roomRowsForSearch } from "$lib/fixtures/rooms";

  import SearchPanel from "./SearchPanel.svelte";

  const noop = () => {};

  const { Story } = defineMeta({
    title: "Rooms/SearchPanel",
    component: SearchPanel,
    args: { rooms: roomRowsForSearch, onOpenRoom: noop, onClose: noop },
    parameters: {
      docs: {
        description: {
          component:
            "A modal over the scrim. Both the scrim and the overlay shadow " +
            "are tokens the design-language work changed, and this is one of " +
            "the four components that actually use them.",
        },
      },
    },
  });
</script>

<!-- Nothing searched yet. Distinct from "no results", which is the point. -->
<Story name="Before searching" args={{ onSearch: async () => searchNoHits }} />

<Story name="With hits" args={{ onSearch: async () => searchHits }} />

<!-- Searched and found nothing — the state that depends on `searched`. -->
<Story name="No results" args={{ onSearch: async () => searchNoHits }} />

<!--
  A failed search. Without the failure message the panel would just look
  empty, which is indistinguishable from "nothing matched".
-->
<Story
  name="Search fails"
  args={{ onSearch: async () => { throw new Error("M_LIMIT_EXCEEDED: too many requests"); } }}
/>
