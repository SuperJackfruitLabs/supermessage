<script module lang="ts">
  import { defineMeta } from "@storybook/addon-svelte-csf";

  import type { ReportSubject } from "$lib/safety";

  import ReportSheet from "./ReportSheet.svelte";
  import ReportSheetFocusHarness from "./ReportSheetFocusHarness.svelte";

  // Plain DOM, as LeaveRoomConfirm.stories does: no `storybook/test` in the bundle.
  const settled = async (find: () => Element | null | undefined, ms = 2000) => {
    const deadline = Date.now() + ms;
    for (;;) {
      const found = find();
      if (found) return found;
      if (Date.now() > deadline) throw new Error("timed out waiting for the sheet to settle");
      await new Promise((r) => setTimeout(r, 16));
    }
  };
  const button = (root: HTMLElement, label: string) =>
    [...root.querySelectorAll("button")].find((b) => b.textContent?.trim() === label);
  const click = (el: Element) => el.dispatchEvent(new MouseEvent("click", { bubbles: true }));

  /** Types into the reason field the way a keystroke does, so the counter is asked. */
  async function type(root: HTMLElement, text: string): Promise<HTMLTextAreaElement> {
    const field = (await settled(() => root.querySelector("textarea"))) as HTMLTextAreaElement;
    field.value = text;
    field.dispatchEvent(new Event("input", { bubbles: true }));
    return field;
  }

  const agentMessage: ReportSubject = {
    kind: "message",
    roomId: "!r:example.org",
    eventId: "$e1",
    senderId: "@agent_atlas:example.org",
    senderName: "Atlas",
    isAgent: true,
  };
  const person: ReportSubject = { kind: "user", userId: "@priya:example.org", name: "Priya", isAgent: false };
  const room: ReportSubject = { kind: "room", roomId: "!r:example.org", name: "Cloudchamber" };

  const left = (n: number) => () => Promise.resolve(n);
  const landed = () => Promise.resolve(null);
  const never = () => false;

  const base = { isBlocked: never, remaining: left(2000), onSend: landed, onClose: () => {} };

  const { Story } = defineMeta({
    title: "Safety/ReportSheet",
    component: ReportSheet,
  });
</script>

<!-- An agent's message, a reason typed, the block toggle on: the agent footer shows. -->
<Story
  name="Report a message"
  args={{ ...base, subject: agentMessage, remaining: left(1962) }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await type(root, "It keeps posting private keys into the room.");
    await settled(() => (root.querySelector("input[type=checkbox]") as HTMLInputElement | null)?.checked ? root : null);
  }}
/>

<!-- Refused by the homeserver: the sheet stays on the form, the reason is kept. -->
<Story
  name="Report a person, refused"
  args={{
    ...base,
    subject: person,
    onSend: () => Promise.resolve("This homeserver doesn't accept person reports. Nothing was sent."),
  }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await type(root, "Harassing me in two rooms.");
    click((await settled(() => button(root, "Send"))) as Element);
    click((await settled(() => button(root, "Report and block"))) as Element);
    await settled(() => root.querySelector("[role=alert]"));
    const field = root.querySelector("textarea") as HTMLTextAreaElement;
    if (field.value !== "Harassing me in two rooms.") throw new Error("the reason was lost");
  }}
/>

<!-- A room has nobody to block, so there is no toggle. -->
<Story name="Report a room" args={{ ...base, subject: room }} />

<Story name="Already blocked" args={{ ...base, subject: person, isBlocked: () => true }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    click((await settled(() => button(root, "Send"))) as Element);
    // Nothing more to block, so the action reads "Report".
    await settled(() => button(root, "Report"));
    if (button(root, "Report and block")) throw new Error("offered to block someone already blocked");
  }}
/>

<Story
  name="Over the limit"
  args={{ ...base, subject: agentMessage, remaining: left(-5) }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    await type(root, "x");
    await settled(() => (root.textContent?.includes("5 characters over the limit") ? root : null));
    if (!(button(root, "Send") as HTMLButtonElement).disabled) throw new Error("Send is enabled over the limit");
  }}
/>

<!-- The confirmation, with the block toggle left on. -->
<Story
  name="Confirm"
  args={{ ...base, subject: agentMessage }}
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    click((await settled(() => button(root, "Send"))) as Element);
    await settled(() => button(root, "Report and block"));
  }}
/>

<!-- Opened from a menu that has since closed: Cancel returns focus to the button it was started from. -->
<Story
  name="Cancel returns focus to the opener"
  play={async ({ canvasElement }) => {
    const root = canvasElement as HTMLElement;
    const opener = (await settled(() => root.querySelector("[data-testid=opener]"))) as HTMLElement;
    const cancel = (await settled(() => button(root, "Cancel"))) as HTMLElement;
    cancel.click();
    await settled(() => (document.activeElement === opener ? opener : null), 1000).catch(() => {
      throw new Error("focus did not return to the opener");
    });
  }}
>
  {#snippet template()}<ReportSheetFocusHarness />{/snippet}
</Story>
