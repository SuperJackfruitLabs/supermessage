<script lang="ts">
  // A message body, drawn from blocks the core already parsed.
  //
  // ## Why there is no `{@html}` in this file
  //
  // There used to be two rendering paths, and each needed its own argument for
  // why it was safe. `formattedBody` went through `{@html}`, safe only because
  // of a chain of guarantees made in Rust before the string crossed IPC — and
  // that chain covered `formattedBody` and nothing else. `body` went through
  // `AgentProse`, which tokenised markdown and rendered *components* precisely
  // so no token's text could reach an `{@html}`.
  //
  // Both are gone. `core::rich` parses both forms into one block tree, so what
  // arrives here is data, and every string in it reaches the DOM through
  // Svelte's default `{...}` escaping. There is no escape hatch left to guard,
  // which is the point: the rule that raw HTML is dropped rather than escaped
  // is now made once, in Rust, where iOS and Android inherit it instead of
  // re-arguing it.
  //
  // ## Why plain elements
  //
  // `<p>`, `<ul>`, `<pre>`, `<em>` and the rest, with no classes of their own.
  // `Timeline.svelte` wraps this in `.message-html`, whose `:global` rules
  // already style exactly this vocabulary — the table overflow guard, the
  // code-block scroller, the list indents. Emitting the same DOM the sanitised
  // HTML used to means the typography did not have to move with the parser.
  //
  // Keyed `{#each}` by index throughout: these lists are positional, and a
  // block has no identity of its own to key on.
  import type { RichBlock, RichInline } from "$lib/ipc";

  let { blocks }: { blocks: RichBlock[] } = $props();
</script>

{#snippet inlineNodes(nodes: RichInline[])}
  {#each nodes as node, i (i)}
    {#if node.inline === "text"}{node.text}{:else if node.inline === "emphasis"}<em
        >{@render inlineNodes(node.inlines)}</em
      >{:else if node.inline === "strong"}<strong>{@render inlineNodes(node.inlines)}</strong
      >{:else if node.inline === "code"}<code>{node.text}</code>{:else if node.inline === "link"}<a
        href={node.href}>{@render inlineNodes(node.inlines)}</a
      >{:else if node.inline === "break"}<br />{/if}
  {/each}
{/snippet}

{#snippet blockNodes(nodes: RichBlock[])}
  {#each nodes as block, i (i)}
    {#if block.block === "paragraph"}
      <p>{@render inlineNodes(block.inlines)}</p>
    {:else if block.block === "heading"}
      <!--
        The level is the core's, bounded 1-6 by both parsers. Rendered through
        an explicit ladder rather than `<svelte:element this={`h${level}`}>`,
        so a level that somehow arrived out of range degrades to a paragraph
        instead of producing an `<h9>` the browser treats as unknown.
      -->
      {#if block.level === 1}<h1>{@render inlineNodes(block.inlines)}</h1>
      {:else if block.level === 2}<h2>{@render inlineNodes(block.inlines)}</h2>
      {:else if block.level === 3}<h3>{@render inlineNodes(block.inlines)}</h3>
      {:else if block.level === 4}<h4>{@render inlineNodes(block.inlines)}</h4>
      {:else if block.level === 5}<h5>{@render inlineNodes(block.inlines)}</h5>
      {:else if block.level === 6}<h6>{@render inlineNodes(block.inlines)}</h6>
      {:else}<p>{@render inlineNodes(block.inlines)}</p>{/if}
    {:else if block.block === "codeBlock"}
      <!--
        No syntax highlighting, deliberately: the whole palette runs on one
        accent, and a code block lit in six competing hues would be the
        loudest thing on screen. `language` is carried for a host that wants
        it; this one shows the code.
      -->
      <pre><code>{block.text}</code></pre>
    {:else if block.block === "blockQuote"}
      <blockquote>{@render blockNodes(block.blocks)}</blockquote>
    {:else if block.block === "list"}
      {#if block.ordered}
        <ol start={block.start}>
          {#each block.items as listItem, j (j)}
            <li>{@render blockNodes(listItem.blocks)}</li>
          {/each}
        </ol>
      {:else}
        <ul>
          {#each block.items as listItem, j (j)}
            <li>{@render blockNodes(listItem.blocks)}</li>
          {/each}
        </ul>
      {/if}
    {:else if block.block === "thematicBreak"}
      <hr />
    {:else if block.block === "table"}
      <table>
        {#if block.header.length > 0}
          <thead>
            <tr>
              {#each block.header as cell, j (j)}
                <th>{@render inlineNodes(cell.inlines)}</th>
              {/each}
            </tr>
          </thead>
        {/if}
        <tbody>
          {#each block.rows as row, j (j)}
            <tr>
              {#each row.cells as cell, k (k)}
                <td>{@render inlineNodes(cell.inlines)}</td>
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}
  {/each}
{/snippet}

{@render blockNodes(blocks)}
