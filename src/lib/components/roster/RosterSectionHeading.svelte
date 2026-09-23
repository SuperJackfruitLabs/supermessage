<script lang="ts">
  /**
   * A roster section's heading.
   *
   * Rendered only when the core gave the section a title. It names one
   * *only when something sits above or beside it* — a lone section on a
   * quiet fleet gets no heading, because "Everything else" above the whole
   * roster labels the absence of a section. `core::roster` decides that;
   * this component never asks whether to draw itself.
   *
   * `attention` is the one place amber appears in the roster's chrome, and
   * it means what it means everywhere else: something is owed.
   */
  export interface Props {
    title: string;
    detail: string | null;
    attention: boolean;
  }

  let { title, detail, attention }: Props = $props();
</script>

<h2
  class="flex items-baseline gap-2 px-4 pt-3 pb-1 text-label
    {attention ? 'text-signal' : 'text-content-faint'}"
>
  <span class="min-w-0 truncate">{title}</span>
  {#if detail}
    <span class="shrink-0 text-meta tabular-nums">{detail}</span>
  {/if}
</h2>
