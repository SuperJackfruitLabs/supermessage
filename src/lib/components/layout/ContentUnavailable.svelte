<script lang="ts">
  import type { Snippet } from "svelte";

  /**
   * Nothing to show, and why (iOS `ContentUnavailableView`): a glyph, a title, a description and
   * optionally an action, centred in the area they replace. The glyph is decoration; the title and
   * description are what assistive technology reads, as one element when `role` is `status`.
   */
  export interface Props {
    title: string;
    description?: string;
    /** The glyph, drawn in `currentColor`; hidden from assistive technology. */
    glyph: Snippet;
    action?: Snippet;
    role?: "status";
    testid?: string;
  }

  let { title, description, glyph, action, role, testid }: Props = $props();
</script>

<div class="flex flex-col items-center justify-center gap-3 p-8 text-center" {role} data-testid={testid}>
  <span class="inline-flex" aria-hidden="true">{@render glyph()}</span>
  <h3 class="text-ui-lg font-semibold text-content">{title}</h3>
  {#if description}
    <p class="text-ui text-content-muted">{description}</p>
  {/if}
  {#if action}{@render action()}{/if}
</div>
