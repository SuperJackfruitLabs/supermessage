<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  /**
   * Driven with plain DOM rather than `storybook/test`.
   *
   * Importing that pulls the interactions instrumenter into the preview bundle
   * for *every* story, and it does not leave the pixels alone: it moved three
   * of them in `live-liveturnbubble--several-paragraphs`, a component these
   * stories have nothing to do with — text subpixel antialiasing, identical to
   * the eye, and over the screenshot gate's tolerance. Proven by re-running
   * main's own gate on the same runner, where it passes.
   *
   * A helper that clicks and waits costs eight lines and leaves the other
   * eighty-four frames exactly as they were.
   */
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the panel to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };

  const buttonNamed = (root: HTMLElement, label: string) =>
    [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === label);

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
    const root = canvasElement as HTMLElement;
    (await settled(() => buttonNamed(root, "Set up recovery"))).dispatchEvent(
      new MouseEvent("click", { bubbles: true }),
    );
    await settled(() => root.querySelector("[data-testid='recovery-key']"));
  }}
/>

<!-- A wrong key. The panel stays open, because the user needs another go. -->
<Story
  name="Wrong key"
  args={{ state: "incomplete", onEnable: key, onRecover: refuse, onClose: () => {} }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const field = (await settled(() =>
      root.querySelector("input[aria-label='Recovery key']"),
    )) as HTMLInputElement;
    // `input` rather than keystrokes: Svelte binds on `input`, and typing
    // character by character buys nothing a screenshot can show.
    field.value = "EsTb wrong key";
    field.dispatchEvent(new Event("input", { bubbles: true }));
    (await settled(() => buttonNamed(root, "Restore"))).dispatchEvent(
      new MouseEvent("click", { bubbles: true }),
    );
    await settled(() => root.querySelector("[role='alert']"));
  }}
/>
