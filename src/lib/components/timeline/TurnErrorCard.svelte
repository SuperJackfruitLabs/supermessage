<script lang="ts">
  import type { TurnErrorCard } from "$lib/ipc";
  import { attemptCount, attemptsToShow, moreAttemptsLabel, repeatsLabel } from "../turnErrorView";

  /**
   * An agent's failed turn — `ItemView` `turnError`.
   *
   * Everything on it was decided by `core::turn_error`: the kind's wording,
   * the headline's source, which attempts were the same and how many times.
   * This draws it. Every string came from whoever sent the message, so every
   * one is plain-text interpolation — never `{@html}`, an `href`, a `src` or
   * a style — and `break-words` keeps a long unbroken value inside the card.
   *
   * `danger`, not `signal`: amber means a decision the reader owes, and a
   * failed turn is not one (docs/design-language.md §2).
   */
  let { card }: { card: TurnErrorCard } = $props();

  let expanded = $state(false);
  const more = $derived(moreAttemptsLabel(card));
  const shown = $derived(attemptsToShow(card, expanded));
  const repeats = $derived(repeatsLabel(card));
</script>

<div class="turn-error-card font-sans" data-testid="turn-error-card">
  <p class="m-0 text-label break-words">
    <span class="font-medium text-danger">{card.label}</span>
    {#if card.source}<span class="text-content-muted">· {card.source}</span>{/if}
  </p>
  <p class="selectable m-0 mt-1 break-words whitespace-pre-wrap text-content">{card.message}</p>
  {#if repeats}
    <p class="m-0 mt-1 text-meta text-content-muted">{repeats}</p>
  {/if}
  {#if shown.length > 0}
    <ul class="m-0 mt-2 list-none space-y-0.5 p-0 text-meta text-content-muted">
      {#each shown as attempt, i (i)}
        {@const count = attemptCount(attempt)}
        <li class="selectable break-words" title={attempt.message}>
          <span class="text-content">{attempt.source}</span>
          · {attempt.label}{#if count}<span class="tabular-nums"> {count}</span>{/if}
        </li>
      {/each}
    </ul>
  {/if}
  {#if more}
    <button
      type="button"
      class="mt-1 rounded-control text-meta text-content-muted transition-colors hover:text-content"
      aria-expanded={expanded}
      onclick={() => (expanded = !expanded)}
    >
      {expanded ? "Show fewer" : more}
    </button>
  {/if}
</div>

<style>
  .turn-error-card {
    border: 1px solid var(--color-border);
    border-left: 2px solid var(--color-danger);
    border-radius: var(--radius-card);
    background-color: var(--color-surface-raised);
    padding: 0.5rem 0.75rem;
  }
</style>
