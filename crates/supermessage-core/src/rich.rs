//! Message bodies as a block tree, parsed once in the core.
//!
//! The timeline has two rendering paths and neither belongs in a host.
//! `formatted_body` is sanitised `org.matrix.custom.html`, sent by human
//! clients on Element. Everything an **agent** writes arrives as raw markdown
//! in `body`, because the hub generates no formatted body at all — so the
//! markdown path is the dominant one, not the fallback.
//!
//! Both become `Vec<RichBlock>` here. A host draws blocks; it parses nothing.
//! That is what keeps the rules below from being re-argued, and re-verified,
//! on every platform this app ever runs on.
//!
//! ## Raw HTML
//!
//! `body` has been through no sanitiser — it is whatever an agent typed — so
//! **no markup is ever interpreted, and none is ever shown as literal angle
//! brackets.** Two cases, which behave differently for a reason:
//!
//! - A **block** of HTML (`<div>gone</div>` on its own line) is dropped
//!   whole. The parser hands it back as one opaque span with no separate text
//!   events, so there is nothing to keep even if we wanted to.
//! - **Inline** markup (`before <b>bold</b> after`) loses its tags and keeps
//!   its words: `before bold after`.
//!
//! The second is a deliberate change from `AgentProse.svelte`, which dropped
//! the element *and* the words, because `marked` handed it `<b>bold</b>` as a
//! single opaque token. Here the tags and the text arrive as separate events,
//! and discarding the text as well would mean writing a small HTML tokeniser
//! inside a security-sensitive path purely to work out which words belong to
//! which tag. Keeping the words is no less safe — they reach the DOM as
//! characters either way, through Svelte's default escaping on desktop and as
//! a `Text` view on iOS — and it loses less of what the agent actually said.
//!
//! ## Vocabulary
//!
//! Deliberately small — no syntax highlighting, no mermaid, no math. That is
//! not an omission to fill in later: the whole palette runs on one accent
//! (console spec §3), and a code block lit up in six competing hues would be
//! the loudest thing on the screen. Shiki's grammars alone are several
//! megabytes, which a desktop app that must start instantly cannot spend and
//! a phone should not carry.

use matrix_sdk::ruma::html::{ElementData, Html, NodeData, NodeRef};
use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag};

/// How deeply containers may nest before the parser stops descending.
///
/// This walks untrusted input into a recursive structure, and that structure
/// crosses an FFI boundary into hosts that walk it again to draw it. A
/// document nested a thousand quotes deep would overflow the stack somewhere
/// along that chain, and where exactly depends on the platform — the worst
/// kind of bug to own. Past this depth the content is flattened to its plain
/// text: visibly degraded, never a crash, and never silently empty.
pub const MAX_RICH_DEPTH: usize = 16;

/// One block-level element.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "block")]
pub enum RichBlock {
    Paragraph {
        inlines: Vec<RichInline>,
    },
    Heading {
        level: u8,
        inlines: Vec<RichInline>,
    },
    CodeBlock {
        language: Option<String>,
        text: String,
    },
    BlockQuote {
        blocks: Vec<RichBlock>,
    },
    List {
        ordered: bool,
        start: u32,
        items: Vec<RichListItem>,
    },
    ThematicBreak,
    Table {
        header: Vec<RichTableCell>,
        rows: Vec<RichTableRow>,
    },
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichListItem {
    pub blocks: Vec<RichBlock>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichTableRow {
    pub cells: Vec<RichTableCell>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct RichTableCell {
    pub inlines: Vec<RichInline>,
}

/// One inline-level element.
#[derive(Debug, Clone, PartialEq, serde::Serialize, uniffi::Enum)]
#[serde(rename_all = "camelCase", tag = "inline")]
pub enum RichInline {
    Text {
        text: String,
    },
    Emphasis {
        inlines: Vec<RichInline>,
    },
    Strong {
        inlines: Vec<RichInline>,
    },
    Code {
        text: String,
    },
    Link {
        href: String,
        inlines: Vec<RichInline>,
    },
    Break,
}

/// Parse agent markdown into blocks.
///
/// Tables are on because agents produce them constantly. Nothing else is:
/// every other extension is a new syntax for a block this vocabulary has no
/// member for, and a syntax that parses to nothing is worse than one that does
/// not parse — it deletes the author's text instead of showing it.
pub fn blocks_from_markdown(source: &str) -> Vec<RichBlock> {
    let mut options = Options::empty();
    options.insert(Options::ENABLE_TABLES);
    let events: Vec<Event> = Parser::new_ext(source, options).collect();
    Walker {
        events,
        pos: 0,
        depth: 0,
    }
    .blocks(false)
}

/// A cursor over the event stream.
///
/// The events are collected up front and walked by index rather than pulled
/// from the iterator. Recursive descent over a peekable iterator means
/// threading a lifetime through every method for no gain; a message body is
/// small enough that the vector costs nothing.
pub(crate) struct Walker<'a> {
    pub(crate) events: Vec<Event<'a>>,
    pub(crate) pos: usize,
    pub(crate) depth: usize,
}

impl<'a> Walker<'a> {
    fn next(&mut self) -> Option<Event<'a>> {
        let event = self.events.get(self.pos).cloned();
        if event.is_some() {
            self.pos += 1;
        }
        event
    }

    fn peek(&self) -> Option<Event<'a>> {
        self.events.get(self.pos).cloned()
    }

    /// Blocks up to the `End` that closes the container we are inside.
    ///
    /// `nested` distinguishes "stop at the next `End`" from "run to the end of
    /// the stream". Each `block_for` consumes its own children's `End`s, so
    /// the only `End` visible at this level is our own closer.
    fn blocks(&mut self, nested: bool) -> Vec<RichBlock> {
        let mut out = Vec::new();
        while let Some(event) = self.peek() {
            match event {
                Event::End(_) => {
                    self.pos += 1;
                    if nested {
                        break;
                    }
                }
                Event::Start(tag) => {
                    self.pos += 1;
                    if let Some(block) = self.block_for(tag) {
                        out.push(block);
                    }
                }
                Event::Rule => {
                    self.pos += 1;
                    out.push(RichBlock::ThematicBreak);
                }
                // Bare text at block level: a tight list item, or a fragment
                // with no wrapping paragraph. Wrapping it is what stops the
                // words vanishing.
                Event::Text(text) => {
                    self.pos += 1;
                    if !text.trim().is_empty() {
                        out.push(RichBlock::Paragraph {
                            inlines: vec![RichInline::Text {
                                text: text.to_string(),
                            }],
                        });
                    }
                }
                _ => {
                    self.pos += 1;
                }
            }
        }
        out
    }

    fn block_for(&mut self, tag: Tag<'a>) -> Option<RichBlock> {
        match tag {
            Tag::Paragraph => {
                let inlines = self.inlines();
                (!inlines.is_empty()).then_some(RichBlock::Paragraph { inlines })
            }
            Tag::Heading { level, .. } => {
                let inlines = self.inlines();
                Some(RichBlock::Heading {
                    level: level as u8,
                    inlines,
                })
            }
            Tag::CodeBlock(kind) => {
                let language = match kind {
                    CodeBlockKind::Fenced(lang) if !lang.is_empty() => Some(lang.to_string()),
                    _ => None,
                };
                // Passed through unchanged: indentation and the trailing
                // newline are part of the code, not formatting noise.
                let mut text = String::new();
                while let Some(event) = self.next() {
                    match event {
                        Event::End(_) => break,
                        Event::Text(chunk) => text.push_str(&chunk),
                        _ => {}
                    }
                }
                Some(RichBlock::CodeBlock { language, text })
            }
            Tag::BlockQuote(_) => self.nested(|walker| RichBlock::BlockQuote {
                blocks: walker.blocks(true),
            }),
            Tag::List(first) => {
                let ordered = first.is_some();
                let start = u32::try_from(first.unwrap_or(1)).unwrap_or(1);
                self.nested(|walker| {
                    let mut items = Vec::new();
                    while let Some(event) = walker.peek() {
                        match event {
                            Event::End(_) => {
                                walker.pos += 1;
                                break;
                            }
                            Event::Start(Tag::Item) => {
                                walker.pos += 1;
                                items.push(RichListItem {
                                    blocks: walker.blocks(true),
                                });
                            }
                            _ => walker.pos += 1,
                        }
                    }
                    RichBlock::List {
                        ordered,
                        start,
                        items,
                    }
                })
            }
            Tag::Table(_) => {
                let mut header = Vec::new();
                let mut rows = Vec::new();
                while let Some(event) = self.peek() {
                    match event {
                        Event::End(_) => {
                            self.pos += 1;
                            break;
                        }
                        Event::Start(Tag::TableHead) => {
                            self.pos += 1;
                            header = self.cells();
                        }
                        Event::Start(Tag::TableRow) => {
                            self.pos += 1;
                            rows.push(RichTableRow {
                                cells: self.cells(),
                            });
                        }
                        _ => self.pos += 1,
                    }
                }
                Some(RichBlock::Table { header, rows })
            }
            // An HTML block, or any tag with no member in this vocabulary.
            // Consumed whole so its contents cannot leak into the next block.
            _ => {
                self.skip_container();
                None
            }
        }
    }

    fn cells(&mut self) -> Vec<RichTableCell> {
        let mut cells = Vec::new();
        while let Some(event) = self.peek() {
            match event {
                Event::End(_) => {
                    self.pos += 1;
                    break;
                }
                Event::Start(Tag::TableCell) => {
                    self.pos += 1;
                    cells.push(RichTableCell {
                        inlines: self.inlines(),
                    });
                }
                _ => self.pos += 1,
            }
        }
        cells
    }

    /// Descend one container level, or flatten if that would breach the cap.
    ///
    /// Flattening keeps the words: the container's entire text becomes one
    /// paragraph. An over-nested document degrades to something a reader can
    /// still read, which is the whole point of having a cap rather than a
    /// rejection.
    fn nested<F>(&mut self, build: F) -> Option<RichBlock>
    where
        F: FnOnce(&mut Self) -> RichBlock,
    {
        if self.depth + 1 > MAX_RICH_DEPTH {
            let text = self.text_until_end();
            return (!text.trim().is_empty()).then_some(RichBlock::Paragraph {
                inlines: vec![RichInline::Text { text }],
            });
        }
        self.depth += 1;
        let block = build(self);
        self.depth -= 1;
        Some(block)
    }

    /// Every word inside the container whose `Start` was just consumed.
    fn text_until_end(&mut self) -> String {
        let mut out = String::new();
        let mut open = 1usize;
        while let Some(event) = self.next() {
            match event {
                Event::Start(_) => open += 1,
                Event::End(_) => {
                    open -= 1;
                    if open == 0 {
                        break;
                    }
                }
                Event::Text(text) | Event::Code(text) => out.push_str(&text),
                Event::SoftBreak | Event::HardBreak => out.push(' '),
                _ => {}
            }
        }
        out
    }

    /// Consume a container whole, keeping nothing.
    fn skip_container(&mut self) {
        let mut open = 1usize;
        while let Some(event) = self.next() {
            match event {
                Event::Start(_) => open += 1,
                Event::End(_) => {
                    open -= 1;
                    if open == 0 {
                        break;
                    }
                }
                _ => {}
            }
        }
    }

    /// Inlines up to the `End` that closes the current inline container.
    fn inlines(&mut self) -> Vec<RichInline> {
        let mut out: Vec<RichInline> = Vec::new();
        while let Some(event) = self.next() {
            match event {
                Event::End(_) => break,
                Event::Text(text) => push_text(&mut out, &text),
                Event::Code(text) => out.push(RichInline::Code {
                    text: text.to_string(),
                }),
                Event::SoftBreak => push_text(&mut out, " "),
                Event::HardBreak => out.push(RichInline::Break),
                // Tags go, words stay — see the module doc comment.
                Event::Html(_) | Event::InlineHtml(_) => {}
                Event::Start(Tag::Emphasis) => out.push(RichInline::Emphasis {
                    inlines: self.inlines(),
                }),
                Event::Start(Tag::Strong) => out.push(RichInline::Strong {
                    inlines: self.inlines(),
                }),
                Event::Start(Tag::Link { dest_url, .. }) => out.push(RichInline::Link {
                    href: dest_url.to_string(),
                    inlines: self.inlines(),
                }),
                // An inline container with no member here — strikethrough, an
                // image, a footnote. Its own styling is lost; its text is not.
                Event::Start(_) => {
                    let inner = self.inlines();
                    out.extend(inner);
                }
                _ => {}
            }
        }
        out
    }
}

/// Append text, merging into the previous run rather than fragmenting it.
///
/// Dropping an inline tag leaves the text on either side of it as separate
/// events; without merging, `before <b>bold</b> after` would arrive as three
/// `Text` inlines and a host would have to know not to space them apart.
fn push_text(out: &mut Vec<RichInline>, text: &str) {
    if text.is_empty() {
        return;
    }
    if let Some(RichInline::Text { text: last }) = out.last_mut() {
        last.push_str(text);
    } else {
        out.push(RichInline::Text {
            text: text.to_string(),
        });
    }
}

/// Walk an **already sanitised** formatted body into the same block tree
/// markdown produces.
///
/// The input has been through `harden_formatted_body` — ruma's Compat
/// sanitiser, then this project's own second pass over it. This function is
/// therefore **not** a security boundary and must not be mistaken for one: it
/// translates, it does not sanitise. Anything handed to it unsanitised is a
/// bug at the call site, not something to defend against here.
///
/// Uses the parser matrix-sdk already brings in (`ruma-html` over `html5ever`),
/// so this adds no dependency and cannot disagree with the sanitiser about
/// what the document contains.
pub fn blocks_from_sanitised_html(html: &str) -> Vec<RichBlock> {
    let parsed = Html::parse(html);
    DomWalker { depth: 0 }.blocks(parsed.children())
}

struct DomWalker {
    depth: usize,
}

/// Elements that carry inline meaning. Everything else at block level is
/// treated as transparent — `html`, `body` and `div` wrap content without
/// changing it, and swallowing their children would delete the message.
fn is_inline_tag(name: &str) -> bool {
    matches!(
        name,
        "em" | "i"
            | "strong"
            | "b"
            | "code"
            | "a"
            | "br"
            | "span"
            | "font"
            | "u"
            | "s"
            | "del"
            | "ins"
            | "sup"
            | "sub"
            | "small"
            | "mark"
    )
}

fn is_block_tag(name: &str) -> bool {
    matches!(
        name,
        "p" | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "hr"
            | "pre"
            | "blockquote"
            | "ul"
            | "ol"
            | "table"
    )
}

fn attr(element: &ElementData, name: &str) -> Option<String> {
    element
        .attrs
        .borrow()
        .iter()
        .find(|a| a.name.local.as_ref() == name)
        .map(|a| a.value.to_string())
}

fn tag_name(element: &ElementData) -> String {
    element.name.local.as_ref().to_ascii_lowercase()
}

/// Every word beneath a node, tags discarded.
///
/// Bounded even though the sanitiser already caps element depth: this is the
/// one function here that recurses without consulting `DomWalker::depth`, and
/// it would be reached with an unbounded tree the day someone calls the
/// public entry point with input that never went through the sanitiser.
fn text_content(node: &NodeRef, depth: usize) -> String {
    let mut out = String::new();
    collect_text(node, depth, &mut out);
    out
}

fn collect_text(node: &NodeRef, depth: usize, out: &mut String) {
    if depth == 0 {
        return;
    }
    for child in node.children() {
        match child.data() {
            NodeData::Text(text) => out.push_str(&text.borrow()),
            NodeData::Element(_) => collect_text(&child, depth - 1, out),
            _ => {}
        }
    }
}

fn flush_paragraph(out: &mut Vec<RichBlock>, pending: &mut Vec<RichInline>) {
    if pending.is_empty() {
        return;
    }
    let inlines = std::mem::take(pending);
    let empty = inlines.iter().all(|i| match i {
        RichInline::Text { text } => text.trim().is_empty(),
        _ => false,
    });
    if !empty {
        out.push(RichBlock::Paragraph { inlines });
    }
}

impl DomWalker {
    fn blocks(&mut self, nodes: impl Iterator<Item = NodeRef>) -> Vec<RichBlock> {
        let mut out = Vec::new();
        let mut pending: Vec<RichInline> = Vec::new();
        for node in nodes {
            match node.data() {
                NodeData::Text(text) => push_text(&mut pending, &text.borrow()),
                NodeData::Element(element) => {
                    let name = tag_name(element);
                    if is_block_tag(&name) {
                        flush_paragraph(&mut out, &mut pending);
                        if let Some(block) = self.block_for(&node, element, &name) {
                            out.push(block);
                        }
                    } else if is_inline_tag(&name) {
                        self.inline_node(&node, &mut pending);
                    } else {
                        // A wrapper with no meaning of its own.
                        flush_paragraph(&mut out, &mut pending);
                        let inner = self.blocks(node.children());
                        out.extend(inner);
                    }
                }
                _ => {}
            }
        }
        flush_paragraph(&mut out, &mut pending);
        out
    }

    fn block_for(
        &mut self,
        node: &NodeRef,
        element: &ElementData,
        name: &str,
    ) -> Option<RichBlock> {
        match name {
            "p" => {
                let inlines = self.inlines(node.children());
                (!inlines.is_empty()).then_some(RichBlock::Paragraph { inlines })
            }
            "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
                let level = name[1..].parse::<u8>().unwrap_or(1);
                Some(RichBlock::Heading {
                    level,
                    inlines: self.inlines(node.children()),
                })
            }
            "hr" => Some(RichBlock::ThematicBreak),
            "pre" => Some(self.code_block(node)),
            "blockquote" => self.nested(node, |walker| RichBlock::BlockQuote {
                blocks: walker.blocks(node.children()),
            }),
            "ul" | "ol" => {
                let ordered = name == "ol";
                let start = if ordered {
                    attr(element, "start")
                        .and_then(|v| v.trim().parse::<u32>().ok())
                        .unwrap_or(1)
                } else {
                    1
                };
                self.nested(node, |walker| {
                    let mut items = Vec::new();
                    for child in node.children() {
                        if let NodeData::Element(el) = child.data() {
                            if tag_name(el) == "li" {
                                items.push(RichListItem {
                                    blocks: walker.blocks(child.children()),
                                });
                            }
                        }
                    }
                    RichBlock::List {
                        ordered,
                        start,
                        items,
                    }
                })
            }
            "table" => {
                let mut header = Vec::new();
                let mut rows = Vec::new();
                self.walk_rows(node, &mut header, &mut rows);
                Some(RichBlock::Table { header, rows })
            }
            _ => None,
        }
    }

    /// Collect every `tr` beneath a table, wherever `thead`/`tbody` put it.
    ///
    /// The first row made of `th` cells is the header; everything else is a
    /// row. Keying on the cell tag rather than on `thead` is what makes a
    /// table written without a `thead` — which is most of them — still render
    /// with its header.
    fn walk_rows(
        &mut self,
        node: &NodeRef,
        header: &mut Vec<RichTableCell>,
        rows: &mut Vec<RichTableRow>,
    ) {
        for child in node.children() {
            let NodeData::Element(element) = child.data() else {
                continue;
            };
            match tag_name(element).as_str() {
                "thead" | "tbody" | "tfoot" => self.walk_rows(&child, header, rows),
                "tr" => {
                    let mut cells = Vec::new();
                    let mut all_header = true;
                    for cell in child.children() {
                        if let NodeData::Element(el) = cell.data() {
                            match tag_name(el).as_str() {
                                "th" => cells.push(RichTableCell {
                                    inlines: self.inlines(cell.children()),
                                }),
                                "td" => {
                                    all_header = false;
                                    cells.push(RichTableCell {
                                        inlines: self.inlines(cell.children()),
                                    });
                                }
                                _ => {}
                            }
                        }
                    }
                    if all_header && !cells.is_empty() && header.is_empty() {
                        *header = cells;
                    } else if !cells.is_empty() {
                        rows.push(RichTableRow { cells });
                    }
                }
                _ => {}
            }
        }
    }

    fn code_block(&mut self, node: &NodeRef) -> RichBlock {
        let mut language = None;
        for child in node.children() {
            if let NodeData::Element(element) = child.data() {
                if tag_name(element) == "code" {
                    language = attr(element, "class").and_then(|classes| {
                        classes
                            .split_whitespace()
                            .find_map(|c| c.strip_prefix("language-").map(str::to_string))
                    });
                }
            }
        }
        RichBlock::CodeBlock {
            language,
            text: text_content(node, MAX_RICH_DEPTH),
        }
    }

    /// Descend one container level, or flatten if that would breach the cap.
    fn nested<F>(&mut self, node: &NodeRef, build: F) -> Option<RichBlock>
    where
        F: FnOnce(&mut Self) -> RichBlock,
    {
        if self.depth + 1 > MAX_RICH_DEPTH {
            let text = text_content(node, MAX_RICH_DEPTH);
            return (!text.trim().is_empty()).then_some(RichBlock::Paragraph {
                inlines: vec![RichInline::Text { text }],
            });
        }
        self.depth += 1;
        let block = build(self);
        self.depth -= 1;
        Some(block)
    }

    fn inlines(&mut self, nodes: impl Iterator<Item = NodeRef>) -> Vec<RichInline> {
        let mut out = Vec::new();
        for node in nodes {
            self.inline_node(&node, &mut out);
        }
        out
    }

    fn inline_node(&mut self, node: &NodeRef, out: &mut Vec<RichInline>) {
        match node.data() {
            NodeData::Text(text) => push_text(out, &text.borrow()),
            NodeData::Element(element) => match tag_name(element).as_str() {
                "em" | "i" => out.push(RichInline::Emphasis {
                    inlines: self.inlines(node.children()),
                }),
                "strong" | "b" => out.push(RichInline::Strong {
                    inlines: self.inlines(node.children()),
                }),
                "code" => out.push(RichInline::Code {
                    text: text_content(node, MAX_RICH_DEPTH),
                }),
                "br" => out.push(RichInline::Break),
                "a" => out.push(RichInline::Link {
                    href: attr(element, "href").unwrap_or_default(),
                    inlines: self.inlines(node.children()),
                }),
                // No inline of its own: its styling is lost, its words are not.
                _ => {
                    let inner = self.inlines(node.children());
                    out.extend(inner);
                }
            },
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn text(s: &str) -> RichInline {
        RichInline::Text { text: s.into() }
    }

    /// Deepest block nesting in a tree. Used by the cap tests; also the shape
    /// a host's renderer walks, so if this can recurse safely so can it.
    fn depth_of(blocks: &[RichBlock]) -> usize {
        blocks
            .iter()
            .map(|b| match b {
                RichBlock::BlockQuote { blocks } => 1 + depth_of(blocks),
                RichBlock::List { items, .. } => {
                    1 + items.iter().map(|i| depth_of(&i.blocks)).max().unwrap_or(0)
                }
                // A leaf block adds no nesting. MAX_RICH_DEPTH caps how deeply
                // *containers* may sit inside one another, which is the axis
                // that can overflow a recursive renderer.
                _ => 0,
            })
            .max()
            .unwrap_or(0)
    }

    #[test]
    fn a_paragraph_becomes_one_paragraph_block() {
        let blocks = blocks_from_markdown("hello world");
        assert_eq!(
            blocks,
            vec![RichBlock::Paragraph {
                inlines: vec![text("hello world")]
            }]
        );
    }

    #[test]
    fn emphasis_and_strong_nest_their_inlines() {
        let blocks = blocks_from_markdown("a *b* and **c**");
        let RichBlock::Paragraph { inlines } = &blocks[0] else {
            panic!("expected a paragraph, got {blocks:?}");
        };
        assert_eq!(inlines[0], text("a "));
        assert_eq!(
            inlines[1],
            RichInline::Emphasis {
                inlines: vec![text("b")]
            }
        );
        assert_eq!(inlines[2], text(" and "));
        assert_eq!(
            inlines[3],
            RichInline::Strong {
                inlines: vec![text("c")]
            }
        );
    }

    #[test]
    fn a_fenced_block_keeps_its_language_and_exact_text() {
        // The text must survive byte-for-byte: a code block that loses its
        // trailing newline or collapses its indentation is wrong in a way that
        // reads as a rendering bug rather than as a parser fault.
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
            vec![RichBlock::CodeBlock {
                language: None,
                text: "plain\n".into()
            }]
        );
    }

    #[test]
    fn an_ordered_list_carries_its_start_number() {
        // Not always 1: an agent numbering steps from 3 means 3.
        let blocks = blocks_from_markdown("3. third\n4. fourth");
        let RichBlock::List {
            ordered,
            start,
            items,
        } = &blocks[0]
        else {
            panic!("expected a list, got {blocks:?}");
        };
        assert!(ordered);
        assert_eq!(*start, 3);
        assert_eq!(items.len(), 2);
    }

    #[test]
    fn a_bullet_list_reports_ordered_false_and_start_one() {
        let blocks = blocks_from_markdown("- a\n- b");
        let RichBlock::List {
            ordered,
            start,
            items,
        } = &blocks[0]
        else {
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
    fn inline_markup_is_dropped_while_the_words_it_wrapped_are_kept() {
        // `body` has been through no sanitiser, so no markup may be
        // interpreted and none may be shown as literal angle brackets either.
        //
        // Deviation from AgentProse.svelte, recorded deliberately: the desktop
        // dropped the element *and the words inside it*, because `marked` hands
        // back `<b>bold</b>` as one opaque token. Here the tags and the text
        // arrive as separate events, and discarding the text too would mean
        // building a small HTML tokeniser inside a security-sensitive path to
        // decide which text belongs to which tag. Keeping the words is no less
        // safe — they reach the DOM as characters either way — and loses less
        // of what the agent actually said. Both hosts get this rule, since both
        // now parse here.
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
        assert!(
            !joined.contains('<'),
            "angle bracket survived in {joined:?}"
        );
        assert!(
            !joined.contains("&lt;"),
            "escaped markup survived in {joined:?}"
        );
        assert!(
            joined.contains("bold"),
            "the words inside the element were dropped: {joined:?}"
        );
    }

    #[test]
    fn a_standalone_html_block_is_dropped_and_produces_no_block() {
        let blocks = blocks_from_markdown("<div>gone</div>");
        assert_eq!(blocks, vec![]);
    }

    #[test]
    fn nesting_past_the_cap_flattens_to_plain_text_instead_of_recursing() {
        // Untrusted input: a document nested deeply enough to blow the stack
        // in this parser, or in either host's renderer, must degrade rather
        // than crash.
        let source = "> ".repeat(MAX_RICH_DEPTH + 10) + "deep";
        let blocks = blocks_from_markdown(&source);
        assert!(!blocks.is_empty(), "everything was dropped");
        assert!(
            depth_of(&blocks) <= MAX_RICH_DEPTH,
            "nested to {} past a cap of {MAX_RICH_DEPTH}",
            depth_of(&blocks)
        );
    }

    #[test]
    fn text_inside_over_deep_nesting_is_kept_not_discarded() {
        // Degrading must not mean losing the words. A reader should see the
        // sentence, flattened — not an empty quote.
        let source = "> ".repeat(MAX_RICH_DEPTH + 10) + "the actual sentence";
        let rendered = format!("{:?}", blocks_from_markdown(&source));
        assert!(
            rendered.contains("the actual sentence"),
            "text was dropped along with the nesting: {rendered}"
        );
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
    fn a_heading_carries_its_level() {
        let blocks = blocks_from_markdown("### three");
        assert_eq!(
            blocks,
            vec![RichBlock::Heading {
                level: 3,
                inlines: vec![text("three")]
            }]
        );
    }

    #[test]
    fn inline_code_is_its_own_inline_not_plain_text() {
        let blocks = blocks_from_markdown("run `cargo test` now");
        let RichBlock::Paragraph { inlines } = &blocks[0] else {
            panic!("expected a paragraph, got {blocks:?}");
        };
        assert_eq!(
            inlines[1],
            RichInline::Code {
                text: "cargo test".into()
            }
        );
    }

    #[test]
    fn empty_input_produces_no_blocks_rather_than_an_empty_paragraph() {
        assert_eq!(blocks_from_markdown(""), vec![]);
        assert_eq!(blocks_from_markdown("   \n\n  "), vec![]);
    }

    #[test]
    fn a_formatted_paragraph_becomes_a_paragraph_block() {
        let blocks = blocks_from_sanitised_html("<p>hello</p>");
        assert_eq!(
            blocks,
            vec![RichBlock::Paragraph {
                inlines: vec![text("hello")]
            }]
        );
    }

    #[test]
    fn strong_and_em_map_to_the_same_inlines_markdown_produces() {
        // The whole point of one vocabulary: a human on Element and an agent
        // writing markdown must produce the same tree for the same emphasis,
        // or a host ends up with two styling paths that drift apart.
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
        // Matrix formatted bodies are frequently a bare fragment, not a
        // document. Dropping unwrapped text would silently delete the message.
        let blocks = blocks_from_sanitised_html("just text");
        assert_eq!(
            blocks,
            vec![RichBlock::Paragraph {
                inlines: vec![text("just text")]
            }]
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
            RichInline::Link {
                href: "https://e.org/".into(),
                inlines: vec![text("go")]
            }
        );
    }

    #[test]
    fn html_nesting_past_the_cap_flattens_the_same_way_markdown_does() {
        let html = "<blockquote>".repeat(MAX_RICH_DEPTH + 10)
            + "deep"
            + "</blockquote>".repeat(MAX_RICH_DEPTH + 10).as_str();
        let blocks = blocks_from_sanitised_html(&html);
        assert!(!blocks.is_empty(), "everything was dropped");
        assert!(
            depth_of(&blocks) <= MAX_RICH_DEPTH,
            "nested to {} past a cap of {MAX_RICH_DEPTH}",
            depth_of(&blocks)
        );
    }

    #[test]
    fn an_element_outside_the_vocabulary_contributes_its_text_and_nothing_else() {
        // The sanitiser already removed the dangerous elements. What survives
        // and has no block of its own — a <span>, a <font> — must not vanish
        // and take the words inside it along.
        let blocks = blocks_from_sanitised_html("<p><span>kept</span></p>");
        assert_eq!(
            blocks,
            vec![RichBlock::Paragraph {
                inlines: vec![text("kept")]
            }]
        );
    }

    #[test]
    fn a_formatted_list_carries_its_start_and_ordering() {
        let blocks = blocks_from_sanitised_html(r#"<ol start="3"><li>a</li><li>b</li></ol>"#);
        let RichBlock::List {
            ordered,
            start,
            items,
        } = &blocks[0]
        else {
            panic!("expected a list, got {blocks:?}");
        };
        assert!(ordered);
        assert_eq!(*start, 3);
        assert_eq!(items.len(), 2);
    }

    #[test]
    fn a_formatted_heading_carries_its_level() {
        assert_eq!(
            blocks_from_sanitised_html("<h4>four</h4>"),
            vec![RichBlock::Heading {
                level: 4,
                inlines: vec![text("four")]
            }]
        );
    }
}
