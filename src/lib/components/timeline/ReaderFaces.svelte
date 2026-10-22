<script lang="ts">
  import type { ReaderFace } from "./rowMeta";

  /**
   * Who has read an own message: at most three 14px faces with a ring in the
   * ground colour, then "+N" (iOS `TimelineChrome.swift:134-175`).
   *
   * The label stays the desktop's own words until M3 brings `people_label`.
   * `faceFor` resolves an `mxc:` URI to a displayable image, or `null` while
   * it is loading or has none, in which case the initial is drawn.
   */
  export interface Props {
    readers: ReaderFace[];
    faceFor: (mxc: string) => string | null;
  }

  const MAX_FACES = 3;

  let { readers, faceFor }: Props = $props();
  let shown = $derived(readers.slice(0, MAX_FACES));
  let extra = $derived(readers.length - shown.length);
</script>

{#if readers.length > 0}
  <span class="inline-flex items-center" role="img" aria-label={`Seen by ${readers.length}`}>
    <span class="flex -space-x-1" aria-hidden="true">
      {#each shown as reader (reader.userId)}
        {@const src = reader.mxcUri ? faceFor(reader.mxcUri) : null}
        {#if src}
          <img {src} alt="" class="h-3.5 w-3.5 rounded-pill object-cover ring-1 ring-surface" />
        {:else}
          <span
            class="flex h-3.5 w-3.5 items-center justify-center rounded-pill bg-surface-raised text-[8px] leading-none font-medium text-content-muted ring-1 ring-surface"
          >
            {reader.initial ?? ""}
          </span>
        {/if}
      {/each}
    </span>
    {#if extra > 0}
      <span class="ml-1 font-sans text-meta tabular-nums text-content-faint" aria-hidden="true">+{extra}</span>
    {/if}
  </span>
{/if}
