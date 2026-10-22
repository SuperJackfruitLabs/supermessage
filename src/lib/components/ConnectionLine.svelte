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

<!--
  The status element stays mounted in every state and only its text changes,
  so a change is announced (an element inserted together with its text often
  is not). Live renders it empty, which shows nothing.

  The core's detail is in `title` for sighted hover and in a visually hidden
  span for assistive tech. A span rather than `aria-description`, which
  WebKitGTK's accessibility bridge does not reliably expose.
-->
<p class="text-meta text-content-muted" role="status" title={words ? (message ?? undefined) : undefined}>
  {#if words}
    {words}
    {#if message}<span class="sr-only">. {message}</span>{/if}
  {/if}
</p>
