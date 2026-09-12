//! The published documentation is checked against the code it describes.
//!
//! `docs/README.md` names the failure mode this exists to prevent: "a description far from its
//! code with no check". Publishing doubles that surface — the pages under `docs-site/` are the
//! ones strangers read, and nobody reading them can check them against the source.
//!
//! This audit found the cost of not doing it. `docs/agentpod-events.md` named two event types,
//! `dev.agentpod.live` and `dev.agentpod.thought`, that exist in neither this client nor
//! agentpod's hub; `docs/matrix-events.md` described as future work a refactor that had already
//! shipped. Both had been wrong for weeks, in files whose own headers claimed they were written
//! against the code.
//!
//! When this fails, the page is usually what is wrong — but not always, and that is the point.
//! A name that vanished from the code is either a docs bug or a regression, and this test cannot
//! tell you which. It can only tell you they disagree.

use std::fs;
use std::path::{Path, PathBuf};

fn repo_root() -> PathBuf {
    // CARGO_MANIFEST_DIR is <repo>/crates/supermessage-core.
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .ancestors()
        .nth(2)
        .expect("repo root is two levels above the crate")
        .to_path_buf()
}

fn read(rel: &str) -> String {
    let path = repo_root().join(rel);
    fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()))
}

/// Every `.md`/`.mdx` page of the published site, as (relative path, contents).
fn pages() -> Vec<(String, String)> {
    fn walk(dir: &Path, prefix: &str, out: &mut Vec<(String, String)>) {
        let entries =
            fs::read_dir(dir).unwrap_or_else(|e| panic!("read_dir {}: {e}", dir.display()));
        for entry in entries {
            let entry = entry.expect("dir entry");
            let name = entry.file_name().to_string_lossy().to_string();
            let rel = if prefix.is_empty() {
                name.clone()
            } else {
                format!("{prefix}/{name}")
            };
            if entry.file_type().expect("file type").is_dir() {
                walk(&entry.path(), &rel, out);
            } else if name.ends_with(".md") || name.ends_with(".mdx") {
                out.push((rel, fs::read_to_string(entry.path()).expect("read page")));
            }
        }
    }
    let mut out = Vec::new();
    walk(
        &repo_root().join("docs-site/src/content/docs"),
        "",
        &mut out,
    );
    out
}

#[test]
fn there_are_pages_to_check() {
    // Guards the vacuous pass: every test below iterates this list, so a moved or empty
    // directory would turn the whole file green while checking nothing.
    assert!(pages().len() >= 5, "found {} pages", pages().len());
}

#[test]
fn every_page_has_a_title_and_description() {
    for (file, text) in pages() {
        assert!(text.starts_with("---"), "{file} has no frontmatter");
        let end = text[3..].find("\n---").expect("frontmatter terminator") + 3;
        let front = &text[3..end];
        assert!(front.contains("title:"), "{file} frontmatter has no title");
        assert!(
            front.contains("description:"),
            "{file} frontmatter has no description"
        );
    }
}

#[test]
fn every_suite_event_type_named_is_one_the_core_renders() {
    // The renderers are the authority: an event type this client cannot render is one the docs
    // must not promise. Read out of the source rather than restated, so the two cannot drift.
    let src = read("crates/supermessage-core/src/custom_events.rs");
    let known: Vec<String> = src
        .match_indices("EVENT_TYPE: &str = \"")
        .map(|(i, m)| {
            let rest = &src[i + m.len()..];
            rest[..rest.find('"').expect("closing quote")].to_string()
        })
        .collect();
    assert!(
        known.len() >= 3,
        "found only {} event types in custom_events.rs",
        known.len()
    );

    for (file, text) in pages() {
        let mut from = 0;
        while let Some(i) = text[from..].find("dev.") {
            let start = from + i;
            let end = start
                + text[start..]
                    .find(|c: char| !(c.is_ascii_alphanumeric() || c == '.' || c == '_'))
                    .unwrap_or(text.len() - start);
            let name = text[start..end].trim_end_matches('.');
            // Only suite event types look like `dev.<vendor>.<thing>.v<N>`; a bare `dev.` in
            // prose or a hostname such as `docs.agentpod.dev` must not be treated as one.
            if name.matches('.').count() >= 3
                && name.rsplit('.').next().is_some_and(|v| v.starts_with('v'))
            {
                assert!(
                    known.iter().any(|k| k == name),
                    "{file} names suite event `{name}`, which no renderer in custom_events.rs handles"
                );
            }
            from = end.max(start + 1);
        }
    }
}

#[test]
fn every_roster_word_named_is_one_the_roster_produces() {
    // `use/rooms.md` prints these four as the states a room can be in. They come from
    // `AgentState::word`, and a page that invents a fifth is a page describing a product that
    // does not exist.
    let src = read("crates/supermessage-core/src/roster.rs");
    for word in ["needs you", "active", "idle", "quiet"] {
        assert!(
            src.contains(&format!("\"{word}\"")),
            "use/rooms.md documents the roster state `{word}`, which roster.rs does not produce"
        );
    }
}

#[test]
fn every_internal_link_resolves_to_a_page() {
    let slugs: Vec<String> = pages()
        .into_iter()
        .map(|(f, _)| {
            f.trim_end_matches(".mdx")
                .trim_end_matches(".md")
                .to_string()
        })
        .collect();

    for (file, text) in pages() {
        let mut from = 0;
        while let Some(i) = text[from..].find("](/") {
            let start = from + i + 2;
            let end = start + text[start..].find(')').expect("link terminator");
            let href = text[start..end].split('#').next().expect("split");
            let slug = href.trim_matches('/');
            let slug = if slug.is_empty() { "index" } else { slug };
            assert!(
                slugs.iter().any(|s| s == slug),
                "{file} links to {href}, which is not a page"
            );
            from = end;
        }
    }
}
