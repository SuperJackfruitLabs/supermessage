<script lang="ts">
  // The app's one connection indicator: a single line under the roster title,
  // like iOS's subtitle under "Chats". Renders nothing when live.
  //
  // Never amber (amber means a pending decision) and no dot: the words carry
  // the state. The core's own error detail is only in the `title` attribute.

  import { connectionSubtitle } from "$lib/connectionWords";
  import type { ConnectionState } from "$lib/ipc";

  export interface Props {
    state: ConnectionState;
    /** The core's own explanation, when it has one. */
    message?: string | null;
  }

  let { state, message = null }: Props = $props();

  const words = $derived(connectionSubtitle(state));
</script>

{#if words}
  <p
    class="text-meta text-content-muted"
    role="status"
    title={message ?? undefined}
  >
    {words}
  </p>
{/if}
