<script lang="ts">
  import "../app.css";
  import { onMount } from "svelte";
  import { installWebviewLogBridge } from "$lib/webviewLog";

  let { children } = $props();

  // Development only: this mirrors the webview's warnings and errors into the
  // Rust log, because WKWebView's console cannot be attached to
  // programmatically (see `webviewLog.ts`). A shipped build has no log anybody
  // is reading, so it would be a channel with nothing on the other end.
  onMount(() => {
    if (import.meta.env.DEV) installWebviewLogBridge();
  });
</script>

{@render children()}
