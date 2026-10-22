<script lang="ts">
  /**
   * A one-line failure notice for an action that did not go through, shown
   * beside the thing the reader acted on. `role="alert"` so it is announced;
   * the danger token, never amber (amber means "you owe an answer").
   */
  let { message }: { message: string } = $props();
</script>

<p role="alert" class="mt-1.5 font-sans text-label text-danger">{message}</p>
