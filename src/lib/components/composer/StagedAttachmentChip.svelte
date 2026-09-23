<script lang="ts">
  /**
   * A file staged for sending, above the composer.
   *
   * The eyebrow takes `--color-accent`, and that is a measurement rather
   * than a preference: on the composer tray, `--color-accent-soft` over
   * `--color-surface-sunken` is **1.059:1 in light** against 1.395:1 in
   * dark — weaker than the 1.090:1 sheet-on-field step the whole depth
   * story rests on, i.e. subliminal in exactly the theme most people use.
   * An accent eyebrow is a second channel that does not depend on that step
   * surviving, and it measures 6.10:1 light / 5.46:1 dark on this ground.
   *
   * Amber would be louder and is forbidden: `--color-signal` means the
   * operator owes someone an answer, and they owe this one only to
   * themselves.
   */
  export interface Props {
    filename: string;
    /** Size and type, composed by `stagedAttachment.ts`. */
    summary: string;
    /** What sending this will and will not carry, when there is a caveat. */
    caveat: string | null;
    onRemove: () => void;
  }

  let { filename, summary, caveat, onRemove }: Props = $props();
</script>

<div class="shrink-0 border-l-2 border-l-accent bg-accent-soft px-4 py-2">
  <div class="mx-auto flex w-full max-w-[72ch] items-start gap-3">
    <div class="min-w-0 flex-1">
      <!--
        The eyebrow takes `--color-accent`, and that is the measurement
        that made it: on the composer tray the strip's own ground carries
        the "look here" alone, and `--color-accent-soft` over
        `--color-surface-sunken` is **1.059:1 in light** against 1.395:1
        in dark — weaker than the 1.090:1 sheet-on-field step the whole
        depth story rests on (spec §3), i.e. subliminal in exactly the
        theme most people use. An accent eyebrow is a second channel that
        does not depend on that step surviving, and it measures 6.10:1
        light / 5.46:1 dark on this ground. Amber would be louder and is
        forbidden: `--color-signal` means the operator owes someone an
        answer (§3), and they owe this one only to themselves.
      -->
      <p class="text-label text-accent">Attached</p>
      <p class="truncate text-ui font-medium text-content" title={filename}>{filename}</p>
      <p class="mt-0.5 truncate text-meta tabular-nums text-content-muted">{summary}</p>
      {#if caveat}
        <p class="mt-1 text-ui text-content-muted">{caveat}</p>
      {/if}
    </div>
    <button
      type="button"
      onclick={onRemove}
      aria-label="Remove attachment"
      class="shrink-0 rounded-control border border-border-strong px-2 py-1 text-ui font-medium text-content-muted transition-colors hover:bg-surface hover:text-content"
    >
      Remove
    </button>
  </div>
</div>
