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
  args={{ onSearch: async () => { throw { kind: "network", message: "" }; } }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "anything");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText("Can't reach the homeserver.")).toBeInTheDocument();
  }}
/>

<!-- Opened from a room: narrowed to it, with the hint and no search yet. -->
<Story
  name="Scoped to this room"
  args={{
    scope: { roomId: "!ashram:id.agentpod.dev", name: "Ashram" },
    onSearch: async () => searchNoHits,
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Find a message in Ashram.")).toBeInTheDocument();
    await expect(canvas.getByRole("radio", { name: "Ashram" })).toHaveAttribute("aria-checked", "true");
  }}
/>

<!--
  Hits come back only when a room id was passed, so seeing them proves the
  search was narrowed. Widening re-runs it (nothing, with no room id), and
  narrowing again brings the hits back.
-->
<Story
  name="Scoped, with hits"
  args={{
    scope: { roomId: "!ashram:id.agentpod.dev", name: "Ashram" },
    onSearch: async (_term: string, roomId?: string) => (roomId ? searchHits.slice(0, 2) : searchNoHits),
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "build");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
    await userEvent.click(canvas.getByRole("radio", { name: "All rooms" }));
    await expect(await canvas.findByText(/Nothing matched/)).toBeInTheDocument();
    await userEvent.click(canvas.getByRole("radio", { name: "Ashram" }));
    await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
    // No baked-in focus ring in the recorded frame.
    (document.activeElement as HTMLElement | null)?.blur();
  }}
/>

<!-- Escape is consumed by the panel, so the page's window handler can see
     it was handled and leave the room-info panel underneath open. -->
<Story
  name="Escape is consumed"
  args={{
    scope: { roomId: "!ashram:id.agentpod.dev", name: "Ashram" },
    onSearch: async () => searchNoHits,
  }}
  play={async ({ canvasElement }) => {
    let prevented: boolean | null = null;
    const probe = (e: KeyboardEvent) => {
      if (e.key === "Escape") prevented = e.defaultPrevented;
    };
    window.addEventListener("keydown", probe);
    try {
      const canvas = within(canvasElement);
      await userEvent.click(canvas.getByPlaceholderText(/Search messages/));
      await userEvent.keyboard("{Escape}");
      await expect(prevented).toBe(true);
    } finally {
      window.removeEventListener("keydown", probe);
    }
  }}
/>

<!-- Arrow keys move between the two scope choices (roving tabindex). -->
<Story
  name="Scope moves with arrow keys"
  args={{
    scope: { roomId: "!ashram:id.agentpod.dev", name: "Ashram" },
    onSearch: async () => searchNoHits,
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    canvas.getByRole("radio", { name: "Ashram" }).focus();
    await userEvent.keyboard("{ArrowRight}");
    await expect(canvas.getByRole("radio", { name: "All rooms" })).toHaveAttribute("aria-checked", "true");
    await expect(canvas.getByText("Find a message across your rooms.")).toBeInTheDocument();
    (document.activeElement as HTMLElement | null)?.blur();
  }}
/>

<!--
  The sidebar's Search destination: no backdrop and no dialog role, no Close, the field focused
  when it appears. Scoped to the open room, as the overlay is.
-->
<Story
  name="Embedded, scoped to a room"
  args={{
    embedded: true,
    scope: { roomId: "!ashram:id.agentpod.dev", name: "Ashram" },
    onSearch: async (_term: string, roomId?: string) => (roomId ? searchHits.slice(0, 2) : searchNoHits),
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.queryByRole("dialog")).toBeNull();
    await expect(canvas.queryByRole("button", { name: /Close/ })).toBeNull();
    await expect(canvas.getByPlaceholderText(/Search messages/)).toHaveFocus();
    await expect(canvas.getByRole("radio", { name: "Ashram" })).toHaveAttribute("aria-checked", "true");
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "build");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
    (document.activeElement as HTMLElement | null)?.blur();
  }}
/>

<Story
  name="Embedded, everywhere"
  args={{ embedded: true, onSearch: async () => searchHits }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(canvas.getByText("Find a message across your rooms.")).toBeInTheDocument();
    await expect(canvas.queryByRole("radiogroup")).toBeNull();
    await userEvent.type(canvas.getByPlaceholderText(/Search messages/), "build");
    await userEvent.keyboard("{Enter}");
    await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
    (document.activeElement as HTMLElement | null)?.blur();
  }}
/>

<!-- Embedded, Escape clears the term and keeps the panel — and the event is consumed. -->
<Story
  name="Embedded, Escape clears"
  args={{ embedded: true, onSearch: async () => searchHits }}
  play={async ({ canvasElement }) => {
    let prevented: boolean | null = null;
    const probe = (e: KeyboardEvent) => {
      if (e.key === "Escape") prevented = e.defaultPrevented;
    };
    window.addEventListener("keydown", probe);
    try {
      const canvas = within(canvasElement);
      const field = canvas.getByPlaceholderText(/Search messages/) as HTMLInputElement;
      await userEvent.type(field, "build");
      await userEvent.keyboard("{Enter}");
      await expect(await canvas.findByText(/Ready to promote build 214/)).toBeInTheDocument();
      await userEvent.keyboard("{Escape}");
      await expect(field.value).toBe("");
      await expect(prevented).toBe(true);
      await expect(canvas.queryByText(/Ready to promote build 214/)).toBeNull();
      await expect(canvas.getByText("Find a message across your rooms.")).toBeInTheDocument();
      await expect(canvas.getByPlaceholderText(/Search messages/)).toBeInTheDocument();
    } finally {
      window.removeEventListener("keydown", probe);
    }
    (document.activeElement as HTMLElement | null)?.blur();
  }}
/>
