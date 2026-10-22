<script lang="ts">
  import type { NotificationMode } from "$lib/ipc";
  import { NOTIFY_OPTIONS, muteTarget } from "./roomSettings";
  import InlineFailure from "./timeline/InlineFailure.svelte";

  /**
   * The room's notifications and pin (iOS `RoomInfoPanel.swift:121-138`). Each control reports
   * its change; the parent writes optimistically and reloads, so a refused write snaps back.
   * "Notify me about" is a native `<select>`: its popup is the OS's.
   */
  export interface Props {
    mode: NotificationMode;
    pinned: boolean;
    onMode: (mode: NotificationMode) => void;
    onPinned: (pinned: boolean) => void;
    /** A write is in flight: the controls cannot be used until it lands. */
    disabled?: boolean;
    /** A refused write, said under the controls; they show the core's real state. */
    failure?: string | null;
  }

  let { mode, pinned, onMode, onPinned, disabled = false, failure = null }: Props = $props();
</script>

<div class="flex flex-col gap-2 border-b border-border px-4 py-3">
  <h3 class="text-label text-content-muted">Notifications</h3>
  <label class="flex items-center justify-between gap-2 text-ui text-content">
    Mute
    <input
      type="checkbox"
      role="switch"
      checked={mode === "muted"}
      {disabled}
      onchange={(e) => onMode(muteTarget(e.currentTarget.checked))}
    />
  </label>
  <label class="flex items-center justify-between gap-2 text-ui text-content">
    Notify me about
    <select
      value={mode}
      {disabled}
      onchange={(e) => onMode(e.currentTarget.value as NotificationMode)}
      class="rounded-control border border-border bg-surface px-2 py-1 text-ui text-content"
    >
      {#each NOTIFY_OPTIONS as option (option.mode)}
        <option value={option.mode} selected={option.mode === mode}>{option.label}</option>
      {/each}
    </select>
  </label>
  <label class="flex items-center justify-between gap-2 text-ui text-content">
    Pin to top
    <input type="checkbox" role="switch" checked={pinned} {disabled} onchange={(e) => onPinned(e.currentTarget.checked)} />
  </label>
  {#if failure !== null}
    <InlineFailure message={failure} />
  {/if}
</div>
