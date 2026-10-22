<script lang="ts">
  /**
   * The runtime of an agent's room: which harness drives it and which machine
   * it runs on (`RoomInfoPanel.swift:108-117`). The core has already read both
   * out of the topic, so nothing is parsed here.
   */
  let { runtime }: { runtime: { harness: string; host: string } } = $props();
</script>

<dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 border-b border-border px-4 py-3 text-ui">
  <dt class="text-content-muted">Harness</dt>
  <dd class="selectable min-w-0 text-right break-words text-content">{runtime.harness}</dd>
  <dt class="text-content-muted">Machine</dt>
  <dd class="selectable min-w-0 text-right break-words text-content">{runtime.host}</dd>
</dl>
