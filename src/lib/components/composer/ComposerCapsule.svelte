<script lang="ts">
  import type { Snippet } from "svelte";

  /**
   * One capsule holds the strips, the chip and the field row, with "+" inside
   * at the leading edge (iOS `ComposerView.swift:16-22`, `:62-81`).
   */
  export interface Props {
    /** Reply or edit strip, and the staged or failed chip. */
    top?: Snippet;
    field: Snippet;
    onAttach: () => void;
    /** True only while sending; never while editing (O2). */
    attachDisabled: boolean;
    sendLabel: string;
    canSend: boolean;
    onSend: () => void;
    /**
     * The mic, which takes Send's place while the field is empty and nothing is staged (iOS
     * `ComposerView.swift:189-197`), and stays under the pointer during a held recording.
     */
    mic?: Snippet;
  }

  let { top, field, onAttach, attachDisabled, sendLabel, canSend, onSend, mic }: Props = $props();
</script>

<div class="w-full min-w-0 flex-1 rounded-card border border-border bg-surface">
  {#if top}
    <!-- Clips the strips' square corners to the capsule without clipping the field's focus ring. -->
    <div class="overflow-hidden rounded-t-card">{@render top()}</div>
  {/if}
  <div class="flex items-end gap-2 px-2 py-2">
    <button
      type="button"
      onclick={onAttach}
      disabled={attachDisabled}
      aria-label="Attach a file"
      title="Attach a file — or drop one on the window"
      class="flex shrink-0 items-center justify-center rounded-control px-2.5 py-2 text-ui-lg text-content-muted transition-colors hover:bg-surface-sunken hover:text-content disabled:text-content-faint disabled:hover:bg-transparent"
    >
      <span aria-hidden="true">+</span>
    </button>
    {@render field()}
    <!--
      Disabled is a *ghost*, not a faded fill. `disabled:opacity-60` over the
      accent fill measured 2.04:1 light / 2.17:1 dark. WCAG exempts inactive
      controls, so that was not a violation — but this button is disabled
      whenever the composer is empty, which is most of the time, and an
      illegible label on the primary instrument's main control most of the
      time is a usability problem the exemption does not make go away.
      Dropping the fill rather than fading it keeps the label readable *and*
      strengthens the inert signal instead of weakening it: an unfilled
      button plainly is not the primary action, whereas a washed-out filled
      one just looks broken.

      **The label is `content-muted`, and the ground is not `surface`.** The
      commit that made this a ghost recorded the label at 4.92:1 — which is
      `content-faint` on `--color-surface`, a ground this button has never
      had. Dropping the fill exposes whatever is behind it, and that is the
      composer tray: `--color-surface-sunken`. Re-measured there by
      compositing the painted stack, `content-faint` is **4.516:1 light /
      4.911:1 dark** — over §9's floor, but in light by 0.016, which is
      rounding, not margin. So the rank moves up one: `content-muted` on the
      same ground measures **7.492:1 light / 9.697:1 dark** — not the 8.2:1
      muted reads on the sheet, for the same reason the faint figure moved:
      the ground here is sunken, and every number about this button has to
      be taken against it.

      Nothing is lost by that. The disabled state was never carried by the
      label's colour — it is carried by the missing accent fill and the
      hairline border that replaces it, which is exactly the argument for
      the ghost in the first place. Fading the label *as well* was belt and
      braces that cost legibility for a signal already fully delivered.

      The border is `border-transparent` when enabled rather than absent, so
      the box measures the same in both states — adding a border only on
      `:disabled` would shift the button by a pixel every time the composer
      goes from empty to non-empty, which is on the first keystroke of every
      message.
    -->
    {#if mic}
      {@render mic()}
    {:else}
    <button
      type="button"
      onclick={onSend}
      disabled={!canSend}
      class="flex shrink-0 items-center gap-1.5 rounded-control border border-transparent bg-accent px-3 py-2 text-ui font-medium text-accent-content transition-colors disabled:border-border disabled:bg-transparent disabled:text-content-muted"
    >
      <!-- The label names its object whenever there is one (Composer passes `sendLabel`). -->
      {sendLabel}
      <span aria-hidden="true" class="opacity-80">⏎</span>
    </button>
    {/if}
  </div>
</div>
