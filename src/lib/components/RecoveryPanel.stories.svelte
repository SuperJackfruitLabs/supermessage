<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";
  import { expect, userEvent, within } from "storybook/test";

  import RecoveryPanel from "./RecoveryPanel.svelte";

  // A real recovery key's shape: the SDK emits base58 in groups of four, and
  // the panel has to hold one without pushing the dialog wider than the screen.
  const KEY = "EsTb 8Qn4 7rGa 2mVd 9pLx 3kWc 6yHf 1tRj";

  const key = () => Promise.resolve(KEY);
  const settle = () => new Promise<void>((r) => setTimeout(r, 400));
  const refuse = () =>
    Promise.reject(new Error("M_FORBIDDEN: that is not this account's recovery key"));

  const { Story } = defineMeta({
    title: "Encryption/RecoveryPanel",
    component: RecoveryPanel,
  });
</script>

<!-- Nothing set up yet: the state most accounts open on. -->
<Story
  name="Not set up"
  args={{ state: "disabled", onEnable: key, onRecover: settle, onClose: () => {} }}
/>

<!--
  Already on. Deliberately has no "show me my key again" — there is no such
  thing, and a button implying otherwise would be a lie about what was stored.
-->
<Story
  name="Already on"
  args={{ state: "enabled", onEnable: key, onRecover: settle, onClose: () => {} }}
/>

<!--
  The account has recovery; this device does not hold the secrets. The one
  state where the entry field is the primary action rather than a way back.
-->
<Story
  name="This device is missing keys"
  args={{ state: "incomplete", onEnable: key, onRecover: settle, onClose: () => {} }}
/>

<!--
  Before the first sync answers. Must not read as "not set up": offering a
  second key to somebody who already has one is how the first gets orphaned.
-->
<Story
  name="Still checking"
  args={{ state: "unknown", onEnable: key, onRecover: settle, onClose: () => {} }}
/>

<!--
  The key itself, the one moment it exists outside the SDK. This is the frame
  worth looking at hardest: it must be selectable, must wrap, and must not be
  dismissable by accident.
-->
<Story
  name="The key, shown once"
  args={{ state: "disabled", onEnable: key, onRecover: settle, onClose: () => {} }}
  play={async ({ canvasElement }) => {
    // Driven rather than posed. This state only exists after the key comes
    // back, and a story that merely *described* it rendered the previous
    // screen instead — the contact sheet caught it as a frame identical to
    // "Not set up", which is exactly what that duplicate check is for.
    const panel = within(canvasElement);
    await userEvent.click(await panel.findByRole("button", { name: "Set up recovery" }));
    await expect(await panel.findByTestId("recovery-key")).toBeInTheDocument();
  }}
/>

<!-- A wrong key. The panel stays open, because the user needs another go. -->
<Story
  name="Wrong key"
  args={{ state: "incomplete", onEnable: key, onRecover: refuse, onClose: () => {} }}
  play={async ({ canvasElement }) => {
    const panel = within(canvasElement);
    await userEvent.type(await panel.findByLabelText("Recovery key"), "EsTb wrong key");
    await userEvent.click(await panel.findByRole("button", { name: "Restore" }));
    await expect(await panel.findByRole("alert")).toBeInTheDocument();
  }}
/>
