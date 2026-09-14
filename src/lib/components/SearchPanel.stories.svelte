<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import { expect, userEvent, within } from "storybook/test";

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

<!--
  Three of these four need a search to have happened.

  They differ only in what `onSearch` returns, and `onSearch` fires when
  somebody submits the form — so as static renders all four were the same
  empty panel, byte-identically. The screenshot gate found that; reading the
  file would not have, because the stories look correct and their names are
  accurate about what they intend.

  `play` is what makes the intent real: type, submit, then assert the state
  arrived. The assertion is the part worth keeping — without it a play
  function that silently stops matching the markup leaves the story back
  where it started, showing an empty panel under a name that promises hits.
-->
<!-- Nothing searched yet. Distinct from "no results", which is the point,
     and the only one of the four that is correct without interacting. -->
<Story name="Before searching" args={{ onSearch: async () => searchNoHits }} />

<Story
  name="With hits"
  args={{ onSearch: async () => searchHits }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "build");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
  }}
/>

<!-- Searched and found nothing — the state that depends on `searched`. -->
<Story
  name="No results"
  args={{ onSearch: async () => searchNoHits }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "nothing matches this");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/Nothing matched/)).toBeInTheDocument();
  }}
/>

<!--
  A failed search. Without the failure message the panel would just look
  empty, which is indistinguishable from "nothing matched" — which is
  precisely what it looked like before this story searched anything.
-->
<Story
  name="Search fails"
  args={{ onSearch: async () => { throw new Error("M_LIMIT_EXCEEDED: too many requests"); } }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "anything");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/M_LIMIT_EXCEEDED|too many/i)).toBeInTheDocument();
  }}
/>
