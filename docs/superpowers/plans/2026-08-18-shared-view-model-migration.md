# Shared View-Model Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the ~1,900 lines of view-model logic that are wire contracts — not presentation — out of the Svelte app and into the Rust core, exposed as resolved DTOs, so iOS and Android cannot disagree with desktop about what an untrusted payload means.

**Architecture:** Each migrated module becomes a focused Rust module in `crates/supermessage-core/src/`, its TypeScript tests ported as the specification. The timeline's element type becomes `TimelineRow { item, view }`, so the render decision and parsed rich text travel with every item instead of being recomputed per render. Migration is vertical and module-by-module: each task ports the Rust, exposes it, rewires the Svelte consumer, deletes the TypeScript, and leaves `cargo test` + `pnpm test` green.

**Tech Stack:** Rust (matrix-sdk 0.18, `ruma::html` for the already-vendored HTML parser, `pulldown-cmark` 0.13 for markdown), UniFFI 0.28, Tauri 2 commands, Svelte 5 runes, vitest.

**Spec:** [`docs/superpowers/specs/2026-08-18-native-ios-app-design.md`](../specs/2026-08-18-native-ios-app-design.md) — Part A (§2, §3). Part B (the SwiftUI app) is a separate plan and depends on this one landing first.

## Global Constraints

- **Licences:** every runtime dependency must be MIT / Apache-2.0 / BSD, or MPL-2.0 used unmodified. `pulldown-cmark` is MIT. CI runs a dependency-licence gate — a non-conforming crate fails the build.
- **Rich-text nesting is capped at 16 levels.** Content deeper than that is flattened to its plain text. This parses untrusted input into a recursive structure; without the cap it is a stack overflow.
- **Custom-event output is text only.** Every `label`, `value`, `prompt` and `text` a renderer produces is display text. No host may route it into markup, an `href`, an `src`, or a style. Renderers read named fields one level at a time and never walk a payload recursively.
- **Raw HTML inside agent markdown is dropped — not escaped, not shown.** An agent writing `<b>x</b>` gets nothing.
- **No syntax highlighting, no mermaid, no math.** The block vocabulary is exactly: paragraph, heading, code block, blockquote, list, thematic break, table.
- **Serde naming:** core DTOs carry `#[serde(rename_all = "camelCase")]` so the webview sees camelCase. UniFFI generates camelCase Swift from snake_case Rust automatically.
- **A test that has never failed is not yet a regression test.** For every test: mutate the implementation, watch it fail, restore, and record what you saw in the commit message. For anything touching ordering, concurrency or a boundary, run the mutated version several times. This project has shipped four worthless green tests; falsification is the standard.
- **Both suites green at every commit:** `cargo test` (from repo root, all three workspace members) and `pnpm test`. Plus `cargo clippy -- -D warnings` and `pnpm check`.
- **Never verify the app with a bare `cargo run`.** Tauri debug builds load `http://localhost:1420`; without Vite the webview loads nothing and every `invoke` fails. Use `pnpm tauri dev`.

## File Structure

**Created in `crates/supermessage-core/src/`:**

| File | Responsibility |
|---|---|
| `rich.rs` | `RichBlock`/`RichInline` types; markdown → blocks; sanitised HTML → blocks; the depth cap |
| `custom_events.rs` | The renderer registry, versioning, field bounding, decision validation, three shipped renderers |
| `item_view.rs` | `ItemView`, `TimelineRow`, and `view_for(item) -> ItemView` |
| `room_identity.rs` | Agent sigil/role extraction from a room name |
| `matrix_links.rs` | `matrix.to` URI parsing |
| `mentions.rs` | Detecting a mention of the logged-in user |
| `room_preview.rs` | Last-message preview line, including the pending-decision case |
| `invitation.rs` | Membership → affordance |

**Modified:** `crates/supermessage-core/src/lib.rs` (module declarations), `timeline.rs` (projection returns `TimelineRow`), `dto.rs` (`TimelineRow`), `crates/supermessage-ffi/src/lib.rs` + `diff.rs` (new surface), `src-tauri/src/commands.rs` + `lib.rs` (new commands), `src-tauri/src/host.rs` (event payload type).

**Deleted at the end of their task:** `src/lib/components/{customEvents,timelineItemView,roomIdentity,matrixLinks,mentions,roomPreview,invitationView}.ts` and their `.test.ts` siblings; `src/lib/components/AgentProse.svelte`; the `svelte-streamdown` dependency.

---

### Task 1: Prove UniFFI can generate a recursive Swift enum

The spec names this the first task for a reason: `RichBlock` contains `Vec<RichBlock>`, and if UniFFI 0.28 cannot express that in Swift, the entire rich-text design changes shape before anything is built on it. Find out in twenty minutes rather than in task 6.

**Files:**
- Modify: `crates/supermessage-ffi/src/lib.rs` (temporary probe types, removed at the end of this task)

**Interfaces:**
- Produces: a recorded yes/no in the task's commit message. No lasting code.

- [ ] **Step 1: Add a minimal recursive pair of types to the FFI crate**

```rust
// TEMPORARY — deleted at the end of this task. Proving UniFFI 0.28 can
// express a type that contains a Vec of itself.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum ProbeBlock {
    Leaf { text: String },
    Nest { blocks: Vec<ProbeBlock> },
}
```

- [ ] **Step 2: Build the library and generate bindings**

Run: `cargo build -p supermessage-ffi --lib && cargo run -q -p supermessage-ffi --bin uniffi-bindgen -- generate --library target/debug/libsupermessage_ffi.a --language swift --out-dir /tmp/probe-bindings`

Expected: either a clean generate, or a hard error naming the recursive type.

- [ ] **Step 3: Inspect the generated Swift**

Run: `grep -n "ProbeBlock" /tmp/probe-bindings/supermessage_ffi.swift | head -20`

Expected (success): a declaration reading `public indirect enum ProbeBlock`. The `indirect` keyword is the whole question — Swift cannot compile a recursive value type without it.

- [ ] **Step 4: Compile the generated Swift to be sure it is not merely emitted**

Run: `swiftc -typecheck /tmp/probe-bindings/supermessage_ffi.swift 2>&1 | head -20`

Expected: no error mentioning "recursive" or "indirect". (Other errors about missing C symbols are expected and irrelevant — this is a typecheck of one file out of its module.)

- [ ] **Step 5: Remove the probe types and record the answer**

Delete `ProbeBlock`. Then commit:

```bash
git add crates/supermessage-ffi/src/lib.rs
git commit -m "chore(ffi): confirm UniFFI generates indirect Swift enums

Recursive RichBlock is the load-bearing assumption of the rich-text
design. Probed with a minimal Vec-of-self enum: <PASTE THE ACTUAL
grep AND swiftc OUTPUT HERE>."
```

**If it failed:** stop and report. The fallback is in the spec §3.3.1 — a flat pre-order `Vec<RichToken>` with explicit open/close markers that each host folds into a tree. That changes tasks 2, 3 and 6 and needs a spec amendment before proceeding.

---

### Task 2: `rich.rs` — the types and the markdown parser

**Files:**
- Create: `crates/supermessage-core/src/rich.rs`
- Modify: `crates/supermessage-core/src/lib.rs` (add `pub mod rich;`)
- Modify: `crates/supermessage-core/Cargo.toml` (add `pulldown-cmark`)

**Interfaces:**
- Produces:
  - `pub enum RichBlock { Paragraph { inlines: Vec<RichInline> }, Heading { level: u8, inlines: Vec<RichInline> }, CodeBlock { language: Option<String>, text: String }, BlockQuote { blocks: Vec<RichBlock> }, List { ordered: bool, start: u32, items: Vec<RichListItem> }, ThematicBreak, Table { header: Vec<RichTableCell>, rows: Vec<RichTableRow> } }`
  - `pub struct RichListItem { pub blocks: Vec<RichBlock> }`
  - `pub struct RichTableRow { pub cells: Vec<RichTableCell> }`
  - `pub struct RichTableCell { pub inlines: Vec<RichInline> }`
  - `pub enum RichInline { Text { text: String }, Emphasis { inlines: Vec<RichInline> }, Strong { inlines: Vec<RichInline> }, Code { text: String }, Link { href: String, inlines: Vec<RichInline> }, Break }`
  - `pub const MAX_RICH_DEPTH: usize = 16;`
  - `pub fn blocks_from_markdown(source: &str) -> Vec<RichBlock>`

- [ ] **Step 1: Add the dependency**

In `crates/supermessage-core/Cargo.toml`, under `[dependencies]`:

```toml
# Markdown -> RichBlock (core::rich). MIT. Agents send `m.text` with no
# `formatted_body` — the hub generates none — so this is the parser for the
# *dominant* reading path in the app, not an edge case.
#
# `default-features = false` drops the `html` feature: this crate never
# renders markdown to HTML, it renders to a block tree that each host draws
# natively. Pulling the HTML writer in would ship a second, unused
# serialisation of untrusted text.
pulldown-cmark = { version = "0.13", default-features = false }
```

- [ ] **Step 2: Write the failing tests**

Create `crates/supermessage-core/src/rich.rs` with only the test module:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    fn text(s: &str) -> RichInline {
        RichInline::Text { text: s.into() }
    }

    #[test]
    fn a_paragraph_becomes_one_paragraph_block() {
        let blocks = blocks_from_markdown("hello world");
        assert_eq!(
            blocks,
            vec![RichBlock::Paragraph { inlines: vec![text("hello world")] }]
        );
    }

    #[test]
    fn emphasis_and_strong_nest_their_inlines() {
        let blocks = blocks_from_markdown("a *b* and **c**");
        let RichBlock::Paragraph { inlines } = &blocks[0] else {
            panic!("expected a paragraph, got {blocks:?}");
        };
        assert_eq!(inlines[0], text("a "));
        assert_eq!(inlines[1], RichInline::Emphasis { inlines: vec![text("b")] });
        assert_eq!(inlines[2], text(" and "));
        assert_eq!(inlines[3], RichInline::Strong { inlines: vec![text("c")] });
    }

    #[test]
    fn a_fenced_block_keeps_its_language_and_exact_text() {
        // The text must survive byte-for-byte: a code block that loses its
        // trailing newline or collapses its indentation is wrong in a way
        // that reads as a rendering bug.
        let blocks = blocks_from_markdown("```rust\nfn main() {\n    ok();\n}\n```");
        assert_eq!(
            blocks,
            vec![RichBlock::CodeBlock {
                language: Some("rust".into()),
                text: "fn main() {\n    ok();\n}\n".into(),
            }]
        );
    }

    #[test]
    fn a_fence_with_no_language_reports_none_rather_than_empty_string() {
        let blocks = blocks_from_markdown("```\nplain\n```");
        assert_eq!(
            blocks,
            vec![RichBlock::CodeBlock { language: None, text: "plain\n".into() }]
        );
    }

    #[test]
    fn an_ordered_list_carries_its_start_number() {
        // Not always 1: an agent numbering steps from 3 means 3.
        let blocks = blocks_from_markdown("3. third\n4. fourth");
        let RichBlock::List { ordered, start, items } = &blocks[0] else {
            panic!("expected a list, got {blocks:?}");
        };
        assert!(ordered);
        assert_eq!(*start, 3);
        assert_eq!(items.len(), 2);
    }

    #[test]
    fn a_bullet_list_reports_ordered_false_and_start_one() {
        let blocks = blocks_from_markdown("- a\n- b");
        let RichBlock::List { ordered, start, items } = &blocks[0] else {
            panic!("expected a list, got {blocks:?}");
        };
        assert!(!ordered);
        assert_eq!(*start, 1);
        assert_eq!(items.len(), 2);
    }

    #[test]
    fn a_link_keeps_its_href_separate_from_its_text() {
        let blocks = blocks_from_markdown("see [the docs](https://example.org/x)");
        let RichBlock::Paragraph { inlines } = &blocks[0] else {
            panic!("expected a paragraph, got {blocks:?}");
        };
        assert_eq!(
            inlines[1],
            RichInline::Link {
                href: "https://example.org/x".into(),
                inlines: vec![text("the docs")],
            }
        );
    }

    #[test]
    fn raw_html_is_dropped_entirely_rather_than_escaped_or_shown() {
        // The rule from AgentProse.svelte: `body` has been through no
        // sanitiser. An agent writing markup gets nothing — not markup, and
        // not literal angle brackets either.
        let blocks = blocks_from_markdown("before <b>bold</b> after");
        let RichBlock::Paragraph { inlines } = &blocks[0] else {
            panic!("expected a paragraph, got {blocks:?}");
        };
        let joined: String = inlines
            .iter()
            .map(|i| match i {
                RichInline::Text { text } => text.clone(),
                other => panic!("unexpected inline: {other:?}"),
            })
            .collect();
        assert!(!joined.contains('<'), "angle bracket survived in {joined:?}");
        assert!(!joined.contains("&lt;"), "escaped markup survived in {joined:?}");
        assert!(!joined.contains("bold"), "element text survived in {joined:?}");
    }

    #[test]
    fn a_standalone_html_block_is_dropped_and_produces_no_block() {
        let blocks = blocks_from_markdown("<div>gone</div>");
        assert_eq!(blocks, vec![]);
    }

    #[test]
    fn nesting_past_the_cap_flattens_to_plain_text_instead_of_recursing() {
        // Untrusted input: a document nested deeply enough to blow the stack
        // in the parser or in either host's renderer must degrade, not crash.
        let source = "> ".repeat(MAX_RICH_DEPTH + 10) + "deep";
        let blocks = blocks_from_markdown(&source);
        // It must terminate, produce something, and that something must not
        // itself be nested past the cap.
        assert!(!blocks.is_empty());
        assert!(depth_of(&blocks) <= MAX_RICH_DEPTH);
    }

    fn depth_of(blocks: &[RichBlock]) -> usize {
        blocks
            .iter()
            .map(|b| match b {
                RichBlock::BlockQuote { blocks } => 1 + depth_of(blocks),
                RichBlock::List { items, .. } => {
                    1 + items.iter().map(|i| depth_of(&i.blocks)).max().unwrap_or(0)
                }
                _ => 1,
            })
            .max()
            .unwrap_or(0)
    }

    #[test]
    fn a_table_keeps_its_header_separate_from_its_rows() {
        let blocks = blocks_from_markdown("| a | b |\n|---|---|\n| 1 | 2 |");
        let RichBlock::Table { header, rows } = &blocks[0] else {
            panic!("expected a table, got {blocks:?}");
        };
        assert_eq!(header.len(), 2);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].cells.len(), 2);
    }

    #[test]
    fn a_thematic_break_survives() {
        assert_eq!(blocks_from_markdown("---"), vec![RichBlock::ThematicBreak]);
    }

    #[test]
    fn empty_input_produces_no_blocks_rather_than_an_empty_paragraph() {
        assert_eq!(blocks_from_markdown(""), vec![]);
        assert_eq!(blocks_from_markdown("   \n\n  "), vec![]);
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cargo test -p supermessage-core rich::`
Expected: compile failure — `RichBlock`, `RichInline`, `MAX_RICH_DEPTH` and `blocks_from_markdown` do not exist.

- [ ] **Step 4: Write the types**

At the top of `rich.rs`, above the test module:

```rust
//! Message bodies as a block tree, parsed once in the core.
//!
//! The timeline has two rendering paths and neither belongs in a host.
//! `formatted_body` is sanitised `org.matrix.custom.html`, sent by human
//! clients on Element. Everything an **agent** writes arrives as raw markdown
//! in `body`, because the hub generates no formatted body at all — so the
//! markdown path is the dominant one, not the fallback.
//!
//! Both become `Vec<RichBlock>` here. A host draws blocks; it parses nothing.
//! That is what keeps the rule below from having to be re-argued, and
//! re-verified, on every platform this app ever runs on:
//!
//! **Raw HTML inside agent markdown is dropped — not escaped, not shown.**
//! `body` has been through no sanitiser; it is whatever an agent typed. An
//! agent writing `<b>x</b>` gets nothing, rather than markup or a pair of
//! literal angle brackets. See `AgentProse.svelte`'s doc comment, which made
//! this decision first and explains why escaping is the wrong trade for text
//! nobody sanitised.
//!
//! The vocabulary is deliberately small — no syntax highlighting, no mermaid,
//! no math. That is not an omission to fill in later: the whole palette runs
//! on one accent (console spec §3), and a code block lit up in six competing
//! hues would be the loudest thing on the screen.

use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag, TagEnd};

/// How deeply blocks may nest before the parser stops descending.
///
/// This walks untrusted input into a recursive structure, and the structure
/// crosses an FFI boundary into hosts that will walk it again to draw it. A
/// document nested a thousand quotes deep would overflow the stack somewhere
/// in that chain; where exactly depends on the platform, which is the worst
/// kind of bug to have. Past this depth, content is flattened to its plain
/// text — visibly degraded, never a crash.
pub const MAX_RICH_DEPTH: usize = 16;

/// One block-level element.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "block")]
pub enum RichBlock {
    Paragraph { inlines: Vec<RichInline> },
    Heading { level: u8, inlines: Vec<RichInline> },
    CodeBlock { language: Option<String>, text: String },
    BlockQuote { blocks: Vec<RichBlock> },
    List { ordered: bool, start: u32, items: Vec<RichListItem> },
    ThematicBreak,
    Table { header: Vec<RichTableCell>, rows: Vec<RichTableRow> },
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichListItem { pub blocks: Vec<RichBlock> }

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichTableRow { pub cells: Vec<RichTableCell> }

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichTableCell { pub inlines: Vec<RichInline> }

/// One inline-level element.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "inline")]
pub enum RichInline {
    Text { text: String },
    Emphasis { inlines: Vec<RichInline> },
    Strong { inlines: Vec<RichInline> },
    Code { text: String },
    Link { href: String, inlines: Vec<RichInline> },
    Break,
}
```

- [ ] **Step 5: Write the markdown parser**

Add to `rich.rs`:

```rust
/// Parse agent markdown into blocks.
///
/// Tables and strikethrough-free GFM: `Options::ENABLE_TABLES` is on because
/// agents produce tables constantly, and nothing else is, because every other
/// extension is a new syntax for a block this vocabulary has no member for.
pub fn blocks_from_markdown(source: &str) -> Vec<RichBlock> {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    let parser = Parser::new_ext(source, options);
    let mut walker = Walker { depth: 0 };
    walker.blocks(&mut parser.into_iter().peekable())
}

struct Walker {
    depth: usize,
}
```

Implement `Walker` so that it consumes the event stream and builds blocks.
The rules it must enforce, each of which has a test above:

- `Event::Html` and `Event::InlineHtml` are **discarded outright** — not
  turned into text, not escaped.
- `Tag::CodeBlock(CodeBlockKind::Fenced(lang))` yields `language: None` when
  `lang` is empty, `Some(lang)` otherwise; the accumulated text is passed
  through unchanged.
- `Tag::List(Some(n))` is `ordered: true, start: n as u32`; `Tag::List(None)`
  is `ordered: false, start: 1`.
- On entering a nesting tag (`BlockQuote`, `List`, `Item`), increment
  `self.depth`. If it would exceed `MAX_RICH_DEPTH`, do not recurse: consume
  events until the matching `TagEnd` and emit a single
  `RichBlock::Paragraph` containing the concatenated `Event::Text` from
  inside it.
- Whitespace-only input yields no blocks: a `Paragraph` whose inlines are all
  empty `Text` is dropped.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cargo test -p supermessage-core rich::`
Expected: 13 passed.

- [ ] **Step 7: Falsify each test**

For each of the 13 tests, break the implementation in the way the test claims
to catch, confirm the failure, and restore. The four that matter most, because
their assertions are the easiest to write as tautologies:

1. `raw_html_is_dropped_entirely…` — change the `Event::Html` arm to push
   `RichInline::Text { text: html.to_string() }`. Must fail.
2. `nesting_past_the_cap_flattens…` — change `MAX_RICH_DEPTH` to `usize::MAX`.
   Must fail (or overflow the stack, which is also a failure).
3. `a_fenced_block_keeps_its_language_and_exact_text` — `.trim()` the
   accumulated code text. Must fail.
4. `an_ordered_list_carries_its_start_number` — hardcode `start: 1`. Must fail.

- [ ] **Step 8: Commit**

```bash
cargo fmt && cargo clippy -p supermessage-core -- -D warnings
git add crates/supermessage-core/src/rich.rs crates/supermessage-core/src/lib.rs crates/supermessage-core/Cargo.toml
git commit -m "feat(core): parse agent markdown into a block tree

Agents send m.text with no formatted_body, so markdown is the dominant
reading path, not a fallback. Parsing it in the core means the rule that
raw HTML is dropped rather than escaped is made once instead of on every
platform.

Falsified all 13: <RECORD WHAT EACH MUTATION PRODUCED>."
```

---

### Task 3: `rich.rs` — the HTML path

`formatted_body` is already sanitised twice before it reaches here (ruma's
`HtmlSanitizerMode::Compat`, then `harden_formatted_body`'s second pass). This
task walks the sanitised DOM into the same block vocabulary, so a host has one
thing to draw regardless of which client sent the message.

**Files:**
- Modify: `crates/supermessage-core/src/rich.rs`

**Interfaces:**
- Consumes: `RichBlock`, `RichInline`, `MAX_RICH_DEPTH` from Task 2.
- Produces: `pub fn blocks_from_sanitised_html(html: &str) -> Vec<RichBlock>`

- [ ] **Step 1: Write the failing tests**

Add to the `tests` module in `rich.rs`:

```rust
#[test]
fn a_formatted_paragraph_becomes_a_paragraph_block() {
    let blocks = blocks_from_sanitised_html("<p>hello</p>");
    assert_eq!(
        blocks,
        vec![RichBlock::Paragraph { inlines: vec![text("hello")] }]
    );
}

#[test]
fn strong_and_em_map_to_the_same_inlines_markdown_produces() {
    // The whole point of one vocabulary: a human on Element and an agent
    // writing markdown must produce the same tree for the same emphasis, or
    // a host ends up with two styling paths that drift.
    let from_html = blocks_from_sanitised_html("<p><em>a</em><strong>b</strong></p>");
    let from_md = blocks_from_markdown("*a***b**");
    assert_eq!(from_html, from_md);
}

#[test]
fn a_pre_code_block_carries_its_language_from_the_class_attribute() {
    let blocks = blocks_from_sanitised_html(
        r#"<pre><code class="language-rust">fn x() {}</code></pre>"#,
    );
    assert_eq!(
        blocks,
        vec![RichBlock::CodeBlock {
            language: Some("rust".into()),
            text: "fn x() {}".into(),
        }]
    );
}

#[test]
fn bare_text_with_no_wrapping_element_still_produces_a_paragraph() {
    // Matrix formatted bodies are frequently a bare fragment, not a document.
    let blocks = blocks_from_sanitised_html("just text");
    assert_eq!(
        blocks,
        vec![RichBlock::Paragraph { inlines: vec![text("just text")] }]
    );
}

#[test]
fn an_anchor_keeps_href_and_text_apart() {
    let blocks = blocks_from_sanitised_html(r#"<p><a href="https://e.org/">go</a></p>"#);
    let RichBlock::Paragraph { inlines } = &blocks[0] else {
        panic!("expected a paragraph, got {blocks:?}");
    };
    assert_eq!(
        inlines[0],
        RichInline::Link { href: "https://e.org/".into(), inlines: vec![text("go")] }
    );
}

#[test]
fn html_nesting_past_the_cap_flattens_the_same_way_markdown_does() {
    let html = "<blockquote>".repeat(MAX_RICH_DEPTH + 10)
        + "deep"
        + &"</blockquote>".repeat(MAX_RICH_DEPTH + 10);
    let blocks = blocks_from_sanitised_html(&html);
    assert!(!blocks.is_empty());
    assert!(depth_of(&blocks) <= MAX_RICH_DEPTH);
}

#[test]
fn an_element_outside_the_vocabulary_contributes_its_text_and_nothing_else() {
    // The sanitiser already removed the dangerous elements. What survives and
    // has no block of its own — a <span>, a <font> — must not vanish silently
    // along with the words inside it.
    let blocks = blocks_from_sanitised_html("<p><span>kept</span></p>");
    assert_eq!(
        blocks,
        vec![RichBlock::Paragraph { inlines: vec![text("kept")] }]
    );
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test -p supermessage-core rich::`
Expected: compile failure — `blocks_from_sanitised_html` does not exist.

- [ ] **Step 3: Implement using the already-vendored parser**

`matrix_sdk::ruma::html::Html` is already a dependency (`timeline.rs:478` uses
`Html::parse`), and `ruma-html 0.8` / `html5ever 0.39` are already in the tree.
No new dependency.

```rust
/// Walk an **already sanitised** formatted body into the same block tree
/// markdown produces.
///
/// The input has been through `harden_formatted_body` — ruma's Compat
/// sanitiser plus this project's own second pass over it. This function is
/// therefore not a security boundary and must not pretend to be one: it does
/// not sanitise, it translates. Anything it is handed unsanitised is a bug at
/// the call site.
pub fn blocks_from_sanitised_html(html: &str) -> Vec<RichBlock> {
    let parsed = matrix_sdk::ruma::html::Html::parse(html);
    let mut walker = Walker { depth: 0 };
    walker.blocks_from_dom(&parsed)
}
```

Element mapping, each with a test above:
`p` → `Paragraph`; `h1`–`h6` → `Heading { level }`; `pre > code` →
`CodeBlock` with `language` read from a `language-*` class; `blockquote` →
`BlockQuote`; `ul`/`ol` → `List` (`ol`'s `start` attribute, defaulting to 1);
`hr` → `ThematicBreak`; `table`/`thead`/`tbody`/`tr`/`th`/`td` → `Table`;
`em`/`i` → `Emphasis`; `strong`/`b` → `Strong`; `code` (not inside `pre`) →
`Code`; `a` → `Link`; `br` → `Break`. Any other surviving element contributes
its children's inlines and no block of its own. Loose text at the top level is
wrapped in a `Paragraph`. The same `MAX_RICH_DEPTH` rule applies.

- [ ] **Step 4: Run to verify they pass**

Run: `cargo test -p supermessage-core rich::`
Expected: 20 passed.

- [ ] **Step 5: Falsify**

- `strong_and_em_map_to_the_same_inlines…` — map `<em>` to `Strong`. Must fail. This is the test most at risk of being a tautology; confirm it actually compares two differently-produced trees.
- `a_pre_code_block_carries_its_language…` — ignore the class attribute. Must fail.
- `an_element_outside_the_vocabulary…` — drop unknown elements' children. Must fail.

- [ ] **Step 6: Commit**

```bash
cargo fmt && cargo clippy -p supermessage-core -- -D warnings
git add crates/supermessage-core/src/rich.rs
git commit -m "feat(core): walk sanitised formatted bodies into the same blocks

One vocabulary for both paths, so a message from Element and a message
from an agent draw through identical host code. Uses the ruma-html parser
already in the tree; adds no dependency and does no sanitising — the input
has already been hardened twice.

Falsified: <RECORD>."
```

---

### Task 4: `item_view.rs` — the render decision, in Rust

Ports `src/lib/components/timelineItemView.ts` (425 lines). Its `ItemView` union
is the semantic classification of a Matrix event, and every host must agree on
it. The `customEvent` variant is stubbed to `Placeholder` here and filled in by
Task 6 — that keeps this task reviewable on its own.

**Files:**
- Create: `crates/supermessage-core/src/item_view.rs`
- Modify: `crates/supermessage-core/src/lib.rs`
- Reference: `src/lib/components/timelineItemView.ts`, `src/lib/components/timelineItemView.test.ts`

**Interfaces:**
- Consumes: `RichBlock` (Task 2/3), `TimelineItemDto` (`dto.rs:244`).
- Produces:
  - `pub enum ItemView { Bubble { muted: bool, blocks: Vec<RichBlock> }, Emote, System { text: String }, UnreadMarker, Placeholder { text: String }, Image { alt: String, width: Option<u32>, height: Option<u32> }, MediaFile { label: MediaFileLabel, filename: String, size: Option<u64>, mimetype: Option<String> }, CustomEvent { view: CustomEventView }, None }`
  - `pub enum MediaFileLabel { File, Audio, Video }`
  - `pub fn view_for(item: &TimelineItemDto) -> ItemView`
  - `pub fn attributed_name(item: &TimelineItemDto) -> String`
  - `pub fn membership_verb(detail: Option<&str>) -> String`
  - `pub fn can_reply_or_react(item: &TimelineItemDto) -> bool`
  - `pub fn reply_preview_excerpt(body: Option<&str>) -> Option<String>`
  - `pub fn display_reaction_key(key: &str) -> String`
  - `pub fn display_event_type(event_type: Option<&str>) -> String`

- [ ] **Step 1: Port the TypeScript test file first**

Open `src/lib/components/timelineItemView.test.ts` and translate every case to
Rust in `item_view.rs`'s `#[cfg(test)] mod tests`. **Port the cases, not the
style** — a vitest `it.each` table becomes a Rust loop over a slice of tuples,
and each assertion keeps its original intent and its original comment.

Do not skip a case because it looks trivial. `displayReactionKey` and
`replyPreviewExcerpt` both have boundary cases involving multi-unit characters,
and this project has already shipped one worthless test whose "naive cut landed
exactly on a code-point boundary because the limit was even and the character
two units wide".

Where the TypeScript checks the `customEvent` branch, assert
`ItemView::CustomEvent { .. }` is **not** produced yet and the item falls to
`Placeholder` — then change those assertions in Task 6. Mark each with
`// TASK 6: becomes CustomEvent` so they are easy to find.

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test -p supermessage-core item_view::`
Expected: compile failure — nothing in the module exists.

- [ ] **Step 3: Implement, following the TypeScript structure**

Keep `MEMBERSHIP_VERBS` and `MEDIA_FILE_LABELS` as the same lookup tables the
TypeScript has (`timelineItemView.ts:71-95`), verbatim in content:

```rust
/// Short verb phrases for the membership-change `detail` values that matter
/// to a reader. Ported verbatim from `timelineItemView.ts` — these strings
/// are user-visible copy, and changing one is a product decision, not a
/// translation detail.
fn membership_verb(detail: Option<&str>) -> String {
    match detail {
        Some("joined") => "joined the room",
        Some("left") => "left the room",
        Some("invited") => "was invited",
        Some("banned") => "was banned",
        Some("unbanned") => "was unbanned",
        Some("kicked") => "was removed",
        Some("kickedAndBanned") => "was removed and banned",
        Some("invitationAccepted") => "accepted the invite",
        Some("invitationRejected") => "rejected the invite",
        Some("invitationRevoked") => "had their invite revoked",
        Some("knocked") => "asked to join",
        Some("knockAccepted") => "was let in",
        Some("knockRetracted") => "withdrew their request to join",
        Some("knockDenied") => "was denied entry",
        _ => "changed their membership",
    }
    .to_string()
}
```

`view_for` computes `blocks` for the `Bubble` arm: `blocks_from_sanitised_html`
when `formatted_body` is `Some`, otherwise `blocks_from_markdown` over `body`.
`Image`'s `alt` falls back through `media.filename`, then `body`, to `"Image"` —
never empty.

- [ ] **Step 4: Run to verify they pass**

Run: `cargo test -p supermessage-core item_view::`
Expected: all ported cases pass.

- [ ] **Step 5: Cross-check against the TypeScript, mechanically**

Run: `grep -c "it(\|test(" src/lib/components/timelineItemView.test.ts` and
`cargo test -p supermessage-core item_view:: 2>&1 | tail -3`

The Rust count must be **greater than or equal to** the TypeScript count. If it
is lower, cases were dropped; find them and port them.

- [ ] **Step 6: Falsify**

Mutate at least these, confirming each fails:
- Return `Bubble` for an item whose `kind` is a membership change.
- Make `Image`'s `alt` fall back to `""` instead of `"Image"`.
- Swap two entries in `MEMBERSHIP_VERBS`.
- Make `reply_preview_excerpt` cut on a byte index rather than a char boundary, and run the multi-unit-character case several times.

- [ ] **Step 7: Commit**

```bash
cargo fmt && cargo clippy -p supermessage-core -- -D warnings
git add crates/supermessage-core/src/item_view.rs crates/supermessage-core/src/lib.rs
git commit -m "feat(core): classify a timeline item into a render decision

Ports timelineItemView.ts. The customEvent arm is a Placeholder until the
registry lands in the next task; every other variant is complete and its
TypeScript tests are ported alongside.

Falsified: <RECORD>."
```

---

### Task 5: `custom_events.rs` — the registry, versioning and decision validation

Ports the machinery of `src/lib/components/customEvents.ts` (682 lines) without
its three concrete renderers, which are Task 6's second half. This is the module
the whole migration exists for.

**Files:**
- Create: `crates/supermessage-core/src/custom_events.rs`
- Modify: `crates/supermessage-core/src/lib.rs`
- Reference: `src/lib/components/customEvents.ts`, `src/lib/components/customEvents.test.ts`

**Interfaces:**
- Produces:
  - `pub struct CustomEventField { pub label: String, pub value: String }`
  - `pub struct CustomEventDecisionOption { pub label: String, pub id: String }`
  - `pub struct CustomEventDecision { pub prompt: String, pub options: Vec<CustomEventDecisionOption> }`
  - `pub enum CustomEventView { Rendered { fields: Vec<CustomEventField>, newer_version: bool, decision: Option<CustomEventDecision> }, Fallback { body: String }, Placeholder { text: String } }`
  - `pub trait CustomEventRenderer: Send + Sync { fn event_type(&self) -> &str; fn max_known_schema_version(&self) -> u32; fn render(&self, content: &serde_json::Value, body: Option<&str>) -> Option<CustomEventRenderResult>; }`
  - `pub struct CustomEventRenderResult { pub fields: Vec<CustomEventField>, pub decision: Option<CustomEventDecision> }`
  - `pub fn safe_string_field(content: &serde_json::Value, key: &str, max_chars: usize) -> Option<String>`
  - `pub fn resolve_custom_event(registry: &CustomEventRegistry, event_type: Option<&str>, content: Option<&serde_json::Value>, body: Option<&str>) -> CustomEventView`

- [ ] **Step 1: Port the TypeScript tests**

Translate `customEvents.test.ts` case for case. The cases that must survive
translation intact, because each encodes a decision rather than a behaviour:

- An unregistered event type falls through to `Fallback { body }` when a
  plain-text `body` exists, and to `Placeholder` when it does not.
- A registered type whose `schema_version` **exceeds** `max_known_schema_version`
  still renders, with `newer_version: true` — best-effort, flagged, never
  silently pretending nothing changed.
- A renderer returning an empty `fields` list is treated **exactly like an
  unrecognised type**: it falls through to the body/placeholder chain.
- A malformed `decision` (not an object, no `prompt`, empty or non-array
  `options`, an option missing `id` or `label`) yields `decision: None` — a
  bogus Allow button is worse than no button.
- An option's `id` is **not truncated**; its `label` **is** bounded.
- `safe_string_field` returns `None` for a non-string, and truncates on a
  character boundary, never mid-character.

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test -p supermessage-core custom_events::`
Expected: compile failure.

- [ ] **Step 3: Implement**

The module doc comment must carry across the two-axis versioning rationale from
`customEvents.ts:35-90` — major version baked into the event type string,
minor as a `schema_version` integer in `content`, and the three rejected
alternatives with their reasons. That argument was co-designed with Kaambaan
and is the reason a client one minor version behind degrades gracefully rather
than treating an additive change as a wholly unknown type. Do not paraphrase it
away.

Two rules the code must make structurally true, not merely observe:

```rust
/// Read one named string field, bounded.
///
/// Renderers use this rather than walking `content` themselves. That single
/// discipline — read named fields, one level at a time, never recurse — is
/// what makes a huge or deeply nested payload harmless *without* a runtime
/// depth or size guard: a renderer that never descends cannot be made to
/// descend a thousand levels.
pub fn safe_string_field(
    content: &serde_json::Value,
    key: &str,
    max_chars: usize,
) -> Option<String> { /* … */ }
```

and `boundDecision`'s port, which validates rather than trusts: a renderer's
declared return type is *"a promise about the renderer's intent, never a
guarantee about its output"*.

- [ ] **Step 4: Run to verify they pass**

Run: `cargo test -p supermessage-core custom_events::`

- [ ] **Step 5: Falsify — this is the module where it matters most**

Every one of these mutations must produce a failure:
- Make `boundDecision` return the decision unvalidated. The malformed-decision cases must fail.
- Treat an empty `fields` list as `Rendered`. The empty-fields case must fail.
- Drop the `newer_version` flag (always `false`). The version case must fail.
- Truncate an option's `id`. That case must fail.
- Make `safe_string_field` slice by byte index. Run the multi-unit-character case **five times** and confirm it fails every time — the project's history has a boundary test that passed by luck.

- [ ] **Step 6: Commit**

```bash
cargo fmt && cargo clippy -p supermessage-core -- -D warnings
git add crates/supermessage-core/src/custom_events.rs crates/supermessage-core/src/lib.rs
git commit -m "feat(core): move the custom-event registry into the core

This is the module the migration exists for: it parses arbitrary JSON from
anyone who can send to the room, and it is where a permission request
becomes a decision. Three hand-written copies of that — desktop, iOS,
Android — would drift, and the drift renders a wrong approval prompt.

Falsified all validation paths: <RECORD>."
```

---

### Task 6: The three shipped renderers, and `ItemView::CustomEvent`

**Files:**
- Modify: `crates/supermessage-core/src/custom_events.rs`
- Modify: `crates/supermessage-core/src/item_view.rs`
- Reference: `customEvents.ts:494-630`

**Interfaces:**
- Consumes: Task 5's trait and `resolve_custom_event`; Task 4's `ItemView`.
- Produces: `pub fn default_registry() -> CustomEventRegistry`, and `ItemView::CustomEvent { view }` now populated.

- [ ] **Step 1: Port the renderers' tests**

The three types, with their constants kept as exported constants so a reader
can find them by name as they can today:

- `DEMO_NOTE_EVENT_TYPE = "dev.supermessage.demo.note.v1"`
- `TURN_ACTIVITY_EVENT_TYPE = "dev.agentpod.turn.v1"`
- `PERMISSION_REQUEST_EVENT_TYPE = "dev.agentpod.permission.v1"`

The permission renderer is the one that sets a `decision`, with
`prompt: format!("Allow {title}?")`. Port its test cases exactly, including
whatever the TypeScript asserts about a request with no options.

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test -p supermessage-core custom_events::`

- [ ] **Step 3: Implement the three renderers and `default_registry()`**

- [ ] **Step 4: Wire `ItemView::CustomEvent`**

In `item_view.rs`, replace the `Placeholder` stub: when
`item.kind == "customMessage"`, call `resolve_custom_event` with the registry,
`item.detail` as the event type, `item.custom_payload` as the content, and
`item.body` as the fallback. Change the assertions marked `// TASK 6` in Task
4's tests to expect `ItemView::CustomEvent`.

- [ ] **Step 5: Run the whole core suite**

Run: `cargo test -p supermessage-core`
Expected: all green, including the Task 4 tests now asserting the real variant.

- [ ] **Step 6: Falsify**

- Register the permission renderer under the wrong event type. Its cases must fail.
- Make the permission renderer omit its `decision`. Must fail.
- Pass `item.body` as the content instead of `item.custom_payload`. The `ItemView` cases must fail.

- [ ] **Step 7: Commit**

```bash
cargo fmt && cargo clippy -p supermessage-core -- -D warnings
git add crates/supermessage-core/src/custom_events.rs crates/supermessage-core/src/item_view.rs
git commit -m "feat(core): port the three shipped custom-event renderers

Demo note, turn activity, and the permission request that sets a decision.
ItemView::CustomEvent is now populated, so the classification is complete.

Falsified: <RECORD>."
```

---

### Task 7: `TimelineRow` — carry the view with the item

The performance-critical change. A host must not call back into the core per
visible row: that is an FFI round trip per row per scroll frame, which a lazy
list cannot absorb. Computing once at construction also means a message's
markdown is parsed once in its lifetime instead of on every re-render.

**Files:**
- Modify: `crates/supermessage-core/src/dto.rs` (add `TimelineRow`)
- Modify: `crates/supermessage-core/src/timeline.rs` (projection sites at `:313`, `:1404`, `:1461`)
- Modify: `crates/supermessage-core/src/event.rs` (`CoreEvent::TimelineDiff` payload type)
- Modify: `crates/supermessage-ffi/src/diff.rs`, `crates/supermessage-ffi/src/lib.rs`
- Modify: `src-tauri/src/host.rs`, `src-tauri/src/commands.rs`

**Interfaces:**
- Produces: `pub struct TimelineRow { pub item: TimelineItemDto, pub view: ItemView }`; `DiffEnvelope<TimelineRow>` replaces `DiffEnvelope<TimelineItemDto>` on the timeline channel; `TimelineSnapshot::items: Vec<TimelineRow>`.

- [ ] **Step 1: Write the failing test**

In `dto.rs`'s test module:

```rust
#[test]
fn a_timeline_row_carries_its_view_beside_its_item() {
    // The view travels with the item so a host never calls back per row.
    // If this struct ever loses `view`, every host silently regains an FFI
    // round trip per visible row per scroll frame.
    let item = minimal_dto("$e1");
    let row = TimelineRow { view: crate::item_view::view_for(&item), item };
    assert_eq!(row.item.id, "$e1");
    assert!(matches!(row.view, crate::item_view::ItemView::Bubble { .. }));
}

#[test]
fn a_row_serialises_its_item_fields_at_the_top_of_item_not_flattened() {
    // The webview reads `row.item.id` and `row.view`. Flattening would be a
    // silent breaking change to every consumer.
    let item = minimal_dto("$e1");
    let row = TimelineRow { view: crate::item_view::view_for(&item), item };
    let json = serde_json::to_value(&row).unwrap();
    assert!(json.get("item").is_some(), "expected an `item` key in {json}");
    assert!(json.get("view").is_some(), "expected a `view` key in {json}");
    assert_eq!(json["item"]["id"], "$e1");
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cargo test -p supermessage-core dto::`
Expected: `TimelineRow` does not exist.

- [ ] **Step 3: Add the type and change the projection**

```rust
/// A timeline item together with the render decision the core made for it.
///
/// The view travels with the item rather than being asked for per row. A
/// host calling `view_for` itself would pay an FFI round trip per visible
/// row per scroll frame — the one cost profile a lazy list cannot absorb —
/// and would re-parse the message's markdown on every re-render.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TimelineRow {
    pub item: TimelineItemDto,
    pub view: crate::item_view::ItemView,
}
```

Change `project_event_item` and the other two construction sites to return
`TimelineRow`. Follow the compiler: `DiffEnvelope<TimelineItemDto>` →
`DiffEnvelope<TimelineRow>` propagates through `event.rs`, the FFI's
`TimelineDiffOp`/`TimelineSnapshot`, and `host.rs`.

`crates/supermessage-ffi/src/diff.rs`'s `From` impls are exhaustive with no
wildcard arm, so the compiler will list every site.

- [ ] **Step 4: Run the whole Rust suite**

Run: `cargo test`
Expected: green across all three workspace members. `cargo test -p supermessage-core dto::` covers the two new cases.

- [ ] **Step 5: Regenerate and check in the Swift bindings**

Run: `./scripts/build-xcframework.sh --debug`
Expected: builds both slices and writes `apple/Generated/*.swift`. The generated Swift is checked in on purpose — commit it with the Rust change that moved it.

- [ ] **Step 6: Falsify**

- Remove `view` from `TimelineRow`. Both new tests must fail.
- Add `#[serde(flatten)]` to `item`. The serialisation test must fail.

- [ ] **Step 7: Commit**

```bash
cargo fmt && cargo clippy -- -D warnings
git add -A crates src-tauri apple/Generated
git commit -m "feat(core): carry the render decision with each timeline item

TimelineRow { item, view } replaces the bare DTO on the timeline channel,
so a host draws from a decision the core already made rather than asking
per row. Bindings regenerated.

Falsified: <RECORD>."
```

---

### Task 8: Expose the new surface, and rewire the timeline in Svelte

The first vertical slice: after this task the desktop app renders from
`ItemView` and `RichBlock`, `{@html}` is gone from the timeline, and two
TypeScript modules are deleted.

**Files:**
- Modify: `src-tauri/src/commands.rs`, `src-tauri/src/lib.rs` (`generate_handler!`)
- Modify: `src/lib/ipc.ts`, `src/lib/stores/timeline.svelte.ts`
- Modify: `src/lib/components/Timeline.svelte`, `src/lib/components/LiveTurn.svelte`
- Create: `src/lib/components/RichText.svelte`
- Delete: `src/lib/components/timelineItemView.ts` + `.test.ts`, `src/lib/components/customEvents.ts` + `.test.ts`, `src/lib/components/AgentProse.svelte`
- Modify: `package.json` (drop `svelte-streamdown`)

**Interfaces:**
- Consumes: `TimelineRow`, `ItemView`, `RichBlock` over the existing timeline diff channel — **no new command is needed**, because the view now arrives with the item.
- Produces: `RichText.svelte` taking `blocks: RichBlock[]`.

- [ ] **Step 1: Write the failing frontend test**

Create `src/lib/components/richText.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { render } from "@testing-library/svelte";
import RichText from "./RichText.svelte";

describe("RichText", () => {
  it("renders a paragraph's text", () => {
    const { getByText } = render(RichText, {
      blocks: [{ block: "paragraph", inlines: [{ inline: "text", text: "hello" }] }],
    });
    expect(getByText("hello")).toBeTruthy();
  });

  it("renders a fenced block as pre, preserving its exact text", () => {
    const { container } = render(RichText, {
      blocks: [{ block: "codeBlock", language: "rust", text: "fn x() {\n  ok();\n}\n" }],
    });
    const pre = container.querySelector("pre");
    expect(pre?.textContent).toBe("fn x() {\n  ok();\n}\n");
  });

  it("renders a link with its href and never with inner markup", () => {
    const { container } = render(RichText, {
      blocks: [{
        block: "paragraph",
        inlines: [{
          inline: "link",
          href: "https://e.org/",
          inlines: [{ inline: "text", text: "<b>go</b>" }],
        }],
      }],
    });
    const a = container.querySelector("a");
    expect(a?.getAttribute("href")).toBe("https://e.org/");
    // The core drops raw HTML, but a block's text is still untrusted text:
    // it must reach the DOM as characters, never as markup.
    expect(a?.querySelector("b")).toBeNull();
    expect(a?.textContent).toBe("<b>go</b>");
  });

  it("contains no {@html} path at all", async () => {
    // Structural, not behavioural: the whole point of blocks is that the
    // timeline no longer has an HTML escape hatch. A future edit that
    // reintroduces one should break a test, not pass review.
    const source = await import("./RichText.svelte?raw");
    expect(source.default).not.toContain("@html");
  });
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `pnpm test src/lib/components/richText.test.ts`
Expected: FAIL — `RichText.svelte` does not exist.

- [ ] **Step 3: Write `RichText.svelte`**

A recursive component: `{#each blocks as block}` switching on `block.block`,
with an inline renderer switching on `inline.inline` and recursing for
`emphasis`, `strong` and `link`. Text reaches the DOM through Svelte's default
`{...}` escaping. **No `{@html}` anywhere.**

- [ ] **Step 4: Run to verify it passes**

Run: `pnpm test src/lib/components/richText.test.ts`

- [ ] **Step 5: Rewire `Timeline.svelte`**

Replace `viewFor(item)` with `row.view` (three call sites — confirm with
`grep -n "viewFor" src/lib/components/Timeline.svelte`). Replace the
`{@html item.formattedBody}` branch and the `<AgentProse content={item.body}>`
branch with a single `<RichText blocks={view.blocks} />` inside the `bubble`
arm. Replace `LiveTurn.svelte`'s `<AgentProse>` the same way — a live turn's
text is markdown too, so it calls a new command:

In `src-tauri/src/commands.rs`:

```rust
/// Parse a live turn's partial markdown into blocks.
///
/// The landed message gets its blocks from `TimelineRow`, but a turn still
/// arriving has no timeline item yet — it is a `LivePayload`. Same parser,
/// so a turn does not change appearance the instant it lands.
#[tauri::command]
pub fn rich_blocks_from_markdown(source: String) -> Vec<RichBlock> {
    supermessage_core::rich::blocks_from_markdown(&source)
}
```

Register it in `generate_handler!` and add the wrapper to `src/lib/ipc.ts`.
Add the same method to `crates/supermessage-ffi/src/lib.rs` as
`Core::rich_blocks_from_markdown`, so iOS has it too.

- [ ] **Step 6: Delete the migrated TypeScript**

```bash
rm src/lib/components/timelineItemView.ts src/lib/components/timelineItemView.test.ts
rm src/lib/components/customEvents.ts src/lib/components/customEvents.test.ts
rm src/lib/components/AgentProse.svelte
pnpm remove svelte-streamdown
```

- [ ] **Step 7: Run everything**

Run: `pnpm check && pnpm test && cargo test`
Expected: all green. `pnpm check` will name every remaining importer of the deleted modules — fix each by reading `row.view` instead.

- [ ] **Step 8: Verify against the running app**

Run: `pnpm tauri dev`

Confirm by eye, because none of the above proves it: an agent's message renders
with its bullets and bold as formatting rather than as literal `**`; a fenced
block renders monospaced; a message from Element with a formatted body renders
the same way; the demo-note custom event still renders its fields.

- [ ] **Step 9: Falsify**

- Point `RichText.svelte`'s paragraph arm at the wrong field. The first test must fail.
- Reintroduce `{@html}` in `RichText.svelte`. The structural test must fail.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: render the timeline from core-resolved views and blocks

Timeline.svelte now draws from TimelineRow.view instead of computing the
decision itself, and from RichBlock instead of {@html}. That removes the
most carefully-guarded escape hatch in the desktop app along with the
comment chain justifying it, and deletes 1,107 lines of TypeScript whose
job the core now does for every platform.

svelte-streamdown dropped: 176K of JS the core replaced.

Falsified: <RECORD>."
```

---

### Task 9: `room_identity.rs`

Ports `src/lib/components/roomIdentity.ts` (281 lines) — the suite convention
by which `🧠 Buddhimaan — Squad Lead` is a sigil, a name and a role.

**Files:**
- Create: `crates/supermessage-core/src/room_identity.rs`
- Modify: `crates/supermessage-core/src/lib.rs`, `crates/supermessage-ffi/src/lib.rs`, `src-tauri/src/commands.rs`, `src-tauri/src/lib.rs`
- Modify: `src/lib/ipc.ts`, and every importer named by `pnpm check`
- Delete: `src/lib/components/roomIdentity.ts` + `.test.ts`

**Interfaces:**
- Produces: `pub struct RoomIdentity { pub sigil: Option<String>, pub name: String, pub role: Option<String> }`; `pub fn room_identity(room_name: &str) -> RoomIdentity`; command `room_identity(name: String) -> RoomIdentity`; `Core::room_identity(&self, name: String) -> RoomIdentity`.

- [ ] **Step 1: Port `roomIdentity.test.ts` case for case into `room_identity.rs`.** Sigil extraction is grapheme-aware — an emoji sigil is frequently multi-code-point (`🧠` is one, but flag and ZWJ sequences are not), so port every boundary case and add one for a ZWJ sequence if the TypeScript lacks it.

- [ ] **Step 2: Run to verify they fail.** `cargo test -p supermessage-core room_identity::`

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to verify they pass.** `cargo test -p supermessage-core room_identity::`

- [ ] **Step 5: Expose it.** Add the Tauri command, register it in `generate_handler!`, add the `Core` method, add the `ipc.ts` wrapper.

- [ ] **Step 6: Rewire and delete.** Replace importers with the command; `rm src/lib/components/roomIdentity.ts src/lib/components/roomIdentity.test.ts`.

Callers are synchronous today and the command is async — cache the resolved
identity in the store that already holds the room (`rooms.svelte.ts`) rather
than awaiting inside a render. Resolve on room-summary change, keep it beside
the summary.

- [ ] **Step 7: Run everything.** `pnpm check && pnpm test && cargo test`

- [ ] **Step 8: Falsify.** Return the whole name as `name` with no sigil split; the sigil cases must fail. Truncate a multi-code-point sigil to its first code point; the ZWJ case must fail.

- [ ] **Step 9: Commit.**

```bash
cargo fmt && cargo clippy -- -D warnings
git add -A
git commit -m "feat(core): resolve a room's agent identity in the core

Falsified: <RECORD>."
```

---

### Task 10: `matrix_links.rs`

Ports `src/lib/components/matrixLinks.ts` (186 lines) — `matrix.to` URI parsing.
Protocol parsing, and therefore shared by definition.

**Files:**
- Create: `crates/supermessage-core/src/matrix_links.rs`
- Modify: `lib.rs`, FFI, commands, `ipc.ts`
- Delete: `src/lib/components/matrixLinks.ts` + `.test.ts`

**Interfaces:**
- Produces: `pub enum MatrixLink { User { id: String }, Room { id_or_alias: String, via: Vec<String> }, Event { room_id_or_alias: String, event_id: String, via: Vec<String> } }`; `pub fn parse_matrix_link(href: &str) -> Option<MatrixLink>`.

- [ ] **Step 1: Port `matrixLinks.test.ts`.** Keep every malformed-input case — this parses hrefs that arrive from strangers.

- [ ] **Step 2: Run to verify they fail.**

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run to verify they pass.**

- [ ] **Step 5: Expose, rewire, delete.**

- [ ] **Step 6: Falsify.** Accept any `https://matrix.to/` URL regardless of fragment shape; the malformed cases must fail.

- [ ] **Step 7: Commit.**

---

### Task 11: `mentions.rs`

Ports `src/lib/components/mentions.ts` (138 lines).

**Files:**
- Create: `crates/supermessage-core/src/mentions.rs`
- Delete: `src/lib/components/mentions.ts` + `.test.ts`

**Interfaces:**
- Produces: `pub fn mentions_user(item: &TimelineItemDto, own_user_id: &str, own_display_name: Option<&str>) -> bool`

- [ ] **Step 1: Port `mentions.test.ts`.**
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Expose, rewire, delete.**
- [ ] **Step 6: Falsify.** Match on a substring rather than a word boundary; the "name appears inside a longer word" case must fail.
- [ ] **Step 7: Commit.**

---

### Task 12: `room_preview.rs`

Ports `src/lib/components/roomPreview.ts` (125 lines). Depends on Task 5,
because a room's preview line reads `Approval needed` when a decision is
pending, and that keys off `DECISION_BEARING_EVENT_TYPES`.

**Files:**
- Create: `crates/supermessage-core/src/room_preview.rs`
- Delete: `src/lib/components/roomPreview.ts` + `.test.ts`

**Interfaces:**
- Consumes: `custom_events::default_registry`, `DECISION_BEARING_EVENT_TYPES`.
- Produces: `pub struct RoomPreview { pub text: String, pub is_own: bool, pub names_sender: bool, pub pending_decision: bool }`; `pub fn room_preview(summary: &RoomSummary) -> RoomPreview`.

- [ ] **Step 1: Port `roomPreview.test.ts`.** The `pending_decision` case is the one that matters: it is what puts amber on a room row, and amber means the operator owes someone an answer.
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement.** `DECISION_BEARING_EVENT_TYPES` is an empty set today — port it as empty, with its comment explaining why, rather than guessing at contents.
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Expose, rewire, delete.** `RoomList.svelte` is the consumer.
- [ ] **Step 6: Falsify.** Set `pending_decision: true` unconditionally; the negative cases must fail. Then set it `false` unconditionally; the positive case must fail.
- [ ] **Step 7: Commit.**

---

### Task 13: `invitation.rs`

Ports `src/lib/components/invitationView.ts` (75 lines).

**Files:**
- Create: `crates/supermessage-core/src/invitation.rs`
- Delete: `src/lib/components/invitationView.ts` + `.test.ts`

**Interfaces:**
- Produces: `pub enum RoomAffordance { Compose, RespondToInvitation, ReadOnly }`; `pub fn room_affordance(membership: Membership) -> RoomAffordance`.

- [ ] **Step 1: Port `invitationView.test.ts`.**
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run to verify they pass.**
- [ ] **Step 5: Expose, rewire, delete.** `+page.svelte:917` is the consumer.
- [ ] **Step 6: Falsify.** Return `Compose` for an invited room; that case must fail — it would show a composer where a join button belongs.
- [ ] **Step 7: Commit.**

---

### Task 14: Close out — docs, bindings, and a full pass

**Files:**
- Modify: `docs/tech-stack.md`, `AGENTS.md`
- Modify: `apple/Generated/*.swift` (regenerated)

- [ ] **Step 1: Correct `docs/tech-stack.md`**

Per spec §9, these entries describe a webview on the phone and are now false.
Remove or rewrite each, and say what replaced it:

- The "Mobile skin: **Framework7 Svelte** (v9)" row and the Konsta UI fallback row.
- Key decision 4, "Framework7 as mobile skin".
- Risk "iOS keyboard doesn't resize WKWebView — #1 'web tell' in a chat app".
- Risk "Webview ceiling ≈ 85–90% native-adjacent".
- Risk "Framework7 single-maintainer risk".
- Risk "IPC cost of streaming timelines to the webview".
- "UI skins are the *only* platform-branched layer (~20% of UI code)".

Hard requirement #2 and the licence bar stay, unchanged and still binding.

- [ ] **Step 2: Update `AGENTS.md`'s test counts**

It currently claims 288 Rust and 327 frontend tests. Run both suites, read the
real numbers off the output, and write those.

- [ ] **Step 3: Regenerate and check in the bindings**

Run: `./scripts/build-xcframework.sh`
Expected: `apple/Generated/*.swift` reflects the full new surface — `RichBlock`,
`ItemView`, `CustomEventView`, `TimelineRow`, `RoomIdentity`, `MatrixLink`,
`RoomPreview`, `RoomAffordance`.

- [ ] **Step 4: Confirm nothing was left behind**

Run: `ls src/lib/components/*.ts | wc -l` — expect 17, down from 24.

Run: `grep -rn "@html" src/lib/components/ src/routes/` — expect no hit in the
timeline path. Any remaining hit must be a deliberate one with a comment
explaining its guarantee.

- [ ] **Step 5: Full verification**

Run: `cargo fmt --check && cargo clippy -- -D warnings && cargo test && pnpm check && pnpm test && pnpm build`
Expected: all green.

- [ ] **Step 6: Verify the running app one more time**

Run: `pnpm tauri dev`

Walk the surfaces the migration touched: room list previews and their sigils,
a timeline with an agent message, a formatted message from Element, a custom
event, an invited room's join affordance, a `matrix.to` link, a mention.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: correct tech-stack.md for a native iOS client

Framework7, the webview ceiling, the keyboard-resize hack and the IPC
streaming cost all described a webview on the phone. They no longer apply.

Part A of the native iOS design is complete: 1,912 lines of view-model
TypeScript now live in the core, with their tests, and seven modules are
gone from the frontend."
```

---

## Self-Review

**Spec coverage.** §3.2's seven migrating modules each have a task: `customEvents` (5, 6), `timelineItemView` (4), `roomIdentity` (9), `matrixLinks` (10), `mentions` (11), `roomPreview` (12), `invitationView` (13). §3.3.1 rich text is tasks 2–3, with its depth cap and its recursive-enum risk as task 1. §3.3.2's DTOs are task 5. §3.3.3's `ItemView` and `TimelineRow` are tasks 4 and 7, including the rejected per-row-call alternative. §3.4's rewiring is task 8 for the timeline and steps 5–6 of each module task thereafter, and the `{@html}` removal is task 8. §9's `tech-stack.md` corrections are task 14.

**Not covered here, by design.** Everything in spec §4–§8 and §10 is Part B (the SwiftUI app) and belongs to its own plan. Part A only regenerates bindings so Part B starts against a current surface.

**Type consistency.** `TimelineRow { item, view }` is named identically in tasks 7, 8 and the spec. `CustomEventView`'s three variants (`Rendered`, `Fallback`, `Placeholder`) are consistent across tasks 5, 6 and 12. `ItemView`'s nine variants are fixed in task 4 and only `CustomEvent` changes in task 6. `blocks_from_markdown` / `blocks_from_sanitised_html` are named the same in tasks 2, 3, 4 and 8.

**One gap found and closed while reviewing:** `LiveTurn.svelte` also renders markdown through `AgentProse`, but a live turn has no timeline item to carry blocks. Task 8 step 5 adds `rich_blocks_from_markdown` as both a Tauri command and a `Core` method so a streaming turn parses through the same code as the landed message and does not change appearance the instant it lands.
