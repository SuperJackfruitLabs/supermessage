<script lang="ts">
  /**
   * A stretch of membership changes folded into one line, which opens.
   *
   * The core decides when a run of membership changes is a screenful (three
   * or more sentences) and says it as one summary, "5 membership changes ·
   * Strategy Sam, Rakesh and 1 other". This is that line: the same quiet row
   * as `LogLine`, with a chevron that says it opens, and a button because it
   * does. Opening shows each run's own sentence in its place (the host keeps
   * which stretches are open; the core knows nothing of it).
   *
   * `text` is built from members' own unbounded display names, so it carries
   * the same three-part guard `LogLine` does (`min-w-0` + `max-w` +
   * `break-words`); the chevron sits beside the text rather than inside it so
   * a long name wraps without pushing it off the row.
   */
  export interface Props {
    text: string;
    onOpen: () => void;
  }

  let { text, onOpen }: Props = $props();
</script>

<div class="flex justify-center py-2">
  <button
    type="button"
    aria-label="Shows each change"
    onclick={onOpen}
    class="flex min-h-11 min-w-0 max-w-[68ch] cursor-pointer items-center gap-1 rounded-control px-2 text-center font-sans text-meta text-content-faint hover:text-content-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
  >
    <span class="min-w-0 break-words">{text}</span>
    <svg
      class="shrink-0"
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z" />
    </svg>
  </button>
</div>
