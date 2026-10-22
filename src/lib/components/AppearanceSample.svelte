<script lang="ts">
  // A small specimen of the tokens an appearance choice changes: ground,
  // raised surface, text, the accent on a button and as a soft highlight.
  // Account -> Appearance (Task 14) previews the choices with it, and the
  // stories use it to show Black and the accents. Colours come from the
  // tokens only; the attributes are set on this element, not on <html>, so
  // a preview never changes the app around it.
  import {
    appearanceAttributes,
    applyAttributes,
    type AppearancePrefs,
  } from "$lib/appearance";

  let {
    prefs,
    systemDark = false,
  }: { prefs: AppearancePrefs; systemDark?: boolean } = $props();

  let frame: HTMLElement | undefined = $state();

  $effect(() => {
    if (frame) applyAttributes(frame, appearanceAttributes(prefs, systemDark));
  });
</script>

<div
  bind:this={frame}
  data-testid="appearance-sample"
  class="w-72 rounded-lg border border-border bg-surface p-4 text-content"
>
  <div class="rounded-md border border-border bg-surface-raised p-3">
    <p class="text-sm">Standup moved to 10:30.</p>
    <p class="text-xs text-content-muted">Muted detail, 2 minutes ago</p>
  </div>
  <div class="mt-3 flex items-center gap-3">
    <button type="button" class="rounded-md bg-accent px-3 py-1.5 text-sm text-accent-content">
      Send
    </button>
    <span class="rounded bg-accent-soft px-2 py-1 text-xs text-content">@you</span>
    <a href="#sample" class="text-sm text-accent underline">Link</a>
  </div>
</div>
