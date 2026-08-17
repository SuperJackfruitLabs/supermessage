<script lang="ts">
  // What an agent writes, rendered as the prose it is.
  //
  // Agents send `m.text` with no `formatted_body` — the hub does not generate
  // one — so every `**bold**`, every `---`, every bullet and fenced code block
  // arrived as literal characters and sat in the timeline as typography
  // debris. This is the fix, and it is client-side on purpose: the hub could
  // generate HTML instead, but then the guarantee about what that HTML
  // contains would have to be made over there and trusted over here.
  //
  // ## Why this is not `{@html}`
  //
  // The bubble's `{@html item.formattedBody}` is safe only because of a chain
  // of guarantees made in Rust before the string crosses IPC — ruma's
  // sanitiser, then `harden_formatted_body`'s second pass over it (see
  // `Timeline.svelte`'s doc comment, which explains why the second pass is not
  // belt-and-braces but a fix for a real ruma-html bug). That chain covers
  // `formattedBody` and nothing else.
  //
  // `item.body` has been through none of it. It is whatever an agent typed.
  // So this renders markdown to *components* rather than to markup: Streamdown
  // tokenises with `marked` and renders each token as a Svelte element, and no
  // token's text ever reaches an `{@html}`.
  //
  // Except one, which is why `renderHtml` is set explicitly below. Raw HTML
  // inside markdown reaches `{@html}` if — and only if — that prop is truthy
  // (`node_modules/svelte-streamdown/dist/Elements/Element.svelte`, the
  // `token.type === 'html'` branch). It is `undefined` by default, so the
  // branch is already dead. Passing `false` explicitly means a dependency
  // changing its own default cannot quietly open a hole in a path that carries
  // untrusted text, and a reader of this file can see the decision rather than
  // having to go and check.
  //
  // Raw HTML is therefore dropped, not escaped and not shown. An agent writing
  // `<b>x</b>` gets nothing rather than markup or literal angle brackets. That
  // is the right trade for text nobody sanitised.
  //
  // ## What is deliberately not imported
  //
  // `svelte-streamdown` depends on shiki, mermaid and katex, and none of the
  // three is in our bundle. They are opt-in: the main entry ships
  // `CodeFallback`, `MermaidFallback` and `MathFallback`
  // (`dist/Elements/fallbacks/`), and the real renderers arrive only if you
  // import them from the `svelte-streamdown/code`, `/mermaid` and `/math`
  // subpaths and pass them as `components`. Measured: adding markdown cost
  // 176K of JS (256K -> 432K), where shiki's grammars alone would have been
  // several megabytes in a desktop app that has to start instantly.
  //
  // A fenced block therefore renders as monospaced `<pre>` with no syntax
  // colouring, which is also the better answer for this application: the whole
  // palette runs on one accent (spec §3), and a code block lit up in six
  // competing hues would be the loudest thing in the window. Highlighting is
  // one import away if that ever stops being true.
  //
  // ## The theme
  //
  // Streamdown ships shadcn-flavoured Tailwind classes per element. None of
  // them belong in this application, which has its own tokens and an editorial
  // reading column (spec §6.3): serif body at a 68ch measure, mono for code,
  // one accent. So every element is re-themed onto our own variables. The
  // point is that an agent's markdown should look like it was set by the same
  // hand as everything else — headings that share the timeline's rhythm rather
  // than `text-3xl font-semibold` dropped into the middle of a conversation.

  import { Streamdown } from "svelte-streamdown";

  let { content, tone = "peer" }: { content: string; tone?: "peer" | "own" } = $props();

  /**
   * Headings inside a chat message are section markers, not page titles.
   *
   * Streamdown's defaults run to `text-3xl` with `mt-6`, which in a message
   * bubble reads as somebody shouting. These step down gently from the body
   * size and lean on weight and colour instead of scale — the same device the
   * sender line and the date divider already use.
   */
  const headings = {
    h1: { base: "mt-4 mb-2 font-serif text-body font-semibold text-content" },
    h2: { base: "mt-4 mb-2 font-serif text-body font-semibold text-content" },
    h3: { base: "mt-3 mb-1 font-serif text-body font-semibold text-content" },
    h4: { base: "mt-3 mb-1 font-mono text-label uppercase text-content-muted" },
    h5: { base: "mt-3 mb-1 font-mono text-label uppercase text-content-muted" },
    h6: { base: "mt-3 mb-1 font-mono text-label uppercase text-content-muted" },
  };

  const theme = $derived({
    ...headings,
    // `[&:not(:first-child)]` so the first paragraph sits flush with the
    // sender line above it, exactly as the plain-text branch does. Without it
    // every agent message would gain a leading gap the operator's does not.
    paragraph: { base: "[&:not(:first-child)]:mt-3" },
    strong: { base: "font-semibold" },
    em: { base: "italic" },
    del: { base: "line-through text-content-muted" },
    link: {
      base: "text-accent underline underline-offset-2 hover:text-accent/80",
      blocked: "text-content-faint",
    },
    ul: { base: "mt-2 list-disc space-y-1 pl-5" },
    ol: { base: "mt-2 list-decimal space-y-1 pl-5" },
    li: { base: "pl-1", checkbox: "mr-2 align-middle accent-[var(--color-accent)]" },
    // The one place mono is not decoration: code is quoted material and the
    // sunken ground marks it as such, matching the field behind the sheet.
    codespan: { base: "rounded bg-surface-sunken px-1 py-0.5 font-mono text-ui" },
    code: {
      base: "font-mono text-ui",
      container: "mt-3 overflow-hidden rounded-md border border-border bg-surface-sunken",
      header:
        "flex items-center justify-between border-b border-border px-3 py-1 font-mono text-meta uppercase text-content-muted",
      language: "font-mono text-meta uppercase text-content-muted",
      buttons: "text-content-muted hover:text-content",
      pre: "overflow-x-auto p-3 font-mono text-ui",
      skeleton: "animate-pulse bg-surface-sunken",
    },
    blockquote: { base: "mt-3 border-l-2 border-border-strong pl-3 text-content-muted" },
    // A rule inside a message is the same object as the timeline's date
    // divider, so it is the same hairline rather than a heavier `<hr>`.
    hr: { base: "my-4 border-0 border-t border-border" },
    table: { base: "mt-3 w-full border-collapse text-ui" },
    th: {
      base: "border-b border-border px-2 py-1 text-left font-mono text-label uppercase text-content-muted",
    },
    td: { base: "border-b border-border px-2 py-1 align-top" },
    image: { base: "mt-3", image: "max-w-full rounded-md" },
    sup: { base: "align-super text-meta" },
    sub: { base: "align-sub text-meta" },
    math: { block: "my-3 font-serif", inline: "font-serif" },
  });
</script>

<!--
  `parseIncompleteMarkdown` is what makes this usable on a turn that is still
  arriving: a half-typed `**bo` renders as text rather than as a broken
  emphasis that snaps into bold when its closing marker lands. It is the same
  component in the live panel and in the timeline for exactly that reason —
  the answer must not change shape as it stops being provisional.

  `renderHtml={false}`: see this file's doc comment. Deliberate, not default.
-->
<div class={tone === "own" ? "agent-prose agent-prose-own" : "agent-prose"}>
  <Streamdown {content} {theme} parseIncompleteMarkdown renderHtml={false} />
</div>

<style>
  /*
   * The measure comes from the block this sits in (68ch for a peer message,
   * 52ch for an own bubble), so nothing here sets a width. What it does set is
   * the one thing markdown brings that plain text did not: block children with
   * their own margins, which would otherwise collapse against the block's
   * padding and pull the first line off its baseline.
   */
  .agent-prose > :global(:first-child) {
    margin-top: 0;
  }

  .agent-prose > :global(:last-child) {
    margin-bottom: 0;
  }

  /*
   * An own bubble is a tight object on a filled ground (spec §6.3). Code and
   * quotes inside it cannot use the sunken field the way a peer block does —
   * that ground is not behind them — so they borrow the bubble's own contrast
   * instead.
   */
  .agent-prose-own :global(code),
  .agent-prose-own :global(blockquote) {
    background-color: color-mix(in srgb, var(--color-content) 8%, transparent);
  }
</style>
