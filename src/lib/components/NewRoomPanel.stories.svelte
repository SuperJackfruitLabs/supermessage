<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import { expect, userEvent, waitFor, within } from "storybook/test";

  import type { PersonDto } from "$lib/ipc";
  import NewRoomPanel from "./NewRoomPanel.svelte";
  import NewRoomPanelFocusHarness from "./NewRoomPanelFocusHarness.svelte";

  const created = async () => "!new:id.agentpod.dev";
  const opened = async () => {};

  const people: PersonDto[] = [
    {
      userId: "@atlas:id.agentpod.dev",
      name: "Atlas",
      initial: "A",
      runtime: { harness: "Claude Code", host: "build-box" },
      avatarUrl: null,
    },
    { userId: "@priya:id.agentpod.dev", name: "Priya", initial: "P", runtime: null, avatarUrl: null },
    {
      userId: "@noname:id.agentpod.dev",
      name: "@noname:id.agentpod.dev",
      initial: "N",
      runtime: null,
      avatarUrl: null,
    },
  ];

  const everyone = async (query: string | null) =>
    query === null ? people : people.filter((p) => p.name.toLowerCase().includes(query.toLowerCase()));

  const { Story } = defineMeta({
    title: "Rooms/NewRoomPanel",
    component: NewRoomPanel,
    args: {
      onCreate: created,
      onJoin: created,
      onOpened: opened,
      onClose: () => {},
      loadPeople: everyone,
      onOpenPerson: async () => "!dm:id.agentpod.dev",
    },
  });
</script>

<!-- An agent with a runtime, a person, and a person with no display name (the raw id shows). -->
<Story
  name="Known people"
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await expect(await canvas.findByText("Claude Code on build-box")).toBeInTheDocument();
  }}
/>

<Story
  name="Nobody yet"
  args={{ loadPeople: async () => [] }}
  play={async ({ canvasElement }) => {
    await expect(await within(canvasElement).findByText("Nobody yet")).toBeInTheDocument();
  }}
/>

<Story
  name="No one matching"
  args={{ loadPeople: async (q: string | null) => (q === null ? people : []) }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.type(await canvas.findByRole("searchbox"), "zz");
    await expect(await canvas.findByText("No one matching zz", {}, { timeout: 2000 })).toBeInTheDocument();
  }}
/>

<!-- `onOpenPerson` never resolves: the row says Working… and the others are disabled. -->
<Story
  name="Opening a person"
  args={{ onOpenPerson: () => new Promise<string>(() => {}) }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole("button", { name: /Priya/ }));
    await expect(await canvas.findByText("Working…")).toBeInTheDocument();
  }}
/>

<Story
  name="Open fails"
  args={{
    onOpenPerson: async () => {
      throw { kind: "refused", message: "M_FORBIDDEN: not allowed to invite" };
    },
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole("button", { name: /Priya/ }));
    await expect(await canvas.findByRole("alert")).toBeInTheDocument();
  }}
/>

<Story
  name="Join by address"
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole("button", { name: "Join by address" }));
    await expect(await canvas.findByPlaceholderText("#general:supermessage.dev")).toBeInTheDocument();
  }}
/>

<!--
  The homeserver refusing. `roomCreation.ts` exists because a request built from a typo comes
  back as an opaque homeserver error rather than "that is not a user id" — so this is the state
  where the panel's own validation has passed and the server still said no.
-->
<Story
  name="Create fails"
  args={{ onCreate: async () => { throw { kind: "refused", message: "M_ROOM_IN_USE: that alias is taken" }; } }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await userEvent.click(await canvas.findByRole("button", { name: "New room" }));
    await userEvent.type(canvas.getByPlaceholderText("Q4 rollout"), "Q4 rollout");
    await userEvent.click(canvas.getByRole("button", { name: "Create" }));
    await expect(await canvas.findByText(/M_ROOM_IN_USE|alias is taken/)).toBeInTheDocument();
  }}
/>

<!-- Refining the query keeps the list on screen: no "Looking for who you know" after the first load. -->
<Story
  name="Refining keeps the list"
  args={{
    loadPeople: async (q: string | null) => {
      if (q !== null) await new Promise((r) => setTimeout(r, 600));
      return everyone(q);
    },
  }}
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByText("Priya");
    await userEvent.type(canvas.getByRole("searchbox"), "pri");
    // The debounce has passed and the slow answer has not come: the old list is still there.
    await new Promise((r) => setTimeout(r, 350));
    await expect(canvas.getByText("Atlas")).toBeInTheDocument();
    await expect(canvas.queryByText("Looking for who you know")).toBeNull();
    await waitFor(() => expect(canvas.queryByText("Atlas")).toBeNull(), { timeout: 2000 });
    await expect(canvas.getByText("Priya")).toBeInTheDocument();
  }}
/>

<!-- Cancel, Escape and the scrim give focus back to the button that opened the panel; Escape is consumed. -->
<Story
  name="Closing returns focus to the opener"
  play={async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    const opener = canvas.getByTestId("opener");
    for (const close of ["cancel", "escape", "scrim"] as const) {
      opener.focus();
      await userEvent.click(opener);
      await canvas.findByRole("dialog");
      if (close === "cancel") await userEvent.click(canvas.getByRole("button", { name: "Cancel" }));
      else if (close === "escape") await userEvent.keyboard("{Escape}");
      else await userEvent.click(canvas.getByRole("button", { name: "Close" }));
      await waitFor(() => expect(canvas.queryByRole("dialog")).toBeNull());
      await expect(opener).toHaveFocus();
    }
    await expect(canvas.getByTestId("leaked")).toHaveTextContent("consumed");
  }}
>
  {#snippet template()}<NewRoomPanelFocusHarness />{/snippet}
</Story>
