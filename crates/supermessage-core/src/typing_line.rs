//! The sentence under the timeline that says who is typing.
//!
//! Agents are not on it: an agent that is typing is *working*, and the
//! acknowledgement dock says so (`crate::acknowledgement`). This splits the
//! typing notices into the two — the people the line names, and the agents'
//! names the dock needs.

use serde::{Deserialize, Serialize};

use crate::display_name::names_label;
use crate::dto::TypingUserDto;
use crate::room_cast::Counterpart;

/// What the typing notices come to.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TypingView {
    /// "Krishna is typing…", "Alice and Bob are typing…", or `None` when no
    /// one other than an agent is.
    pub line: Option<String>,
    /// The agents whose typing notice is up, by the name the header gives
    /// them, in notice order.
    pub agents: Vec<String>,
}

/// Split `typers` into the people the line names and the agents it does not.
///
/// A typer is called the counterpart's `name` when it is the counterpart,
/// else its `label` — the short, glyph-free form the timeline uses — never
/// the raw display name, so one agent is one name everywhere.
pub fn typing_view(
    typers: &[TypingUserDto],
    agent_ids: &[String],
    counterpart: Option<&Counterpart>,
) -> TypingView {
    let name_of = |t: &TypingUserDto| match counterpart {
        Some(c) if c.user_id == t.user_id => c.name.clone(),
        _ => t.label.clone(),
    };
    let agents = typers
        .iter()
        .filter(|t| agent_ids.contains(&t.user_id))
        .map(name_of)
        .collect();
    let people: Vec<String> = typers
        .iter()
        .filter(|t| !agent_ids.contains(&t.user_id))
        .map(name_of)
        .collect();
    let line = match people.len() {
        0 => None,
        1 => Some(format!("{} is typing…", people[0])),
        _ => Some(format!("{} are typing…", names_label(&people))),
    };
    TypingView { line, agents }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn typer(id: &str, label: &str) -> TypingUserDto {
        TypingUserDto {
            user_id: id.into(),
            display_name: Some(label.into()),
            label: label.into(),
        }
    }

    fn ids(raw: &[&str]) -> Vec<String> {
        raw.iter().map(|s| s.to_string()).collect()
    }

    fn atlas() -> Counterpart {
        Counterpart {
            user_id: "@atlas:x".into(),
            name: "Atlas".into(),
        }
    }

    /// Port of `TypingCastTests` (ComposerRevampTests.swift:516).
    ///
    /// Mutation seen failing: using `label` for the counterpart (agents came
    /// back `["atlas-bot"]`); leaving agents in `people` (line became
    /// "Krishna and atlas-bot are typing…").
    #[test]
    fn an_agent_typing_is_named_by_the_header_and_is_not_on_the_peoples_line() {
        let view = typing_view(
            &[typer("@atlas:x", "atlas-bot"), typer("@k:x", "Krishna")],
            &ids(&["@atlas:x"]),
            Some(&atlas()),
        );
        assert_eq!(view.agents, vec!["Atlas".to_string()]);
        assert_eq!(view.line.as_deref(), Some("Krishna is typing…"));
    }

    /// Port of `TypingCastTests` (ComposerRevampTests.swift:533).
    ///
    /// Mutation seen failing: treating every typer as an agent when the cast
    /// is empty (`agent_ids.is_empty() || ...` in the agents filter; line
    /// became `None`).
    #[test]
    fn with_no_cast_everyone_typing_is_on_the_line() {
        let view = typing_view(&[typer("@atlas:x", "atlas-bot")], &[], None);
        assert_eq!(view.line.as_deref(), Some("atlas-bot is typing…"));
        assert!(view.agents.is_empty());
    }

    /// Mutation seen failing: returning `Some("")` for no typers.
    #[test]
    fn nobody_typing_is_no_line() {
        let view = typing_view(&[], &ids(&["@atlas:x"]), Some(&atlas()));
        assert_eq!(
            view,
            TypingView {
                line: None,
                agents: vec![]
            }
        );
    }

    /// Ruling 1: the core's `people_label` wording, not iOS's "and N-1 others".
    ///
    /// Mutation seen failing: the 3+ arm formatted as
    /// "{first} and {n-1} others are typing…".
    #[test]
    fn two_and_more_are_named_the_cores_way() {
        let mut typers = vec![typer("@a:x", "Alice"), typer("@b:x", "Bob")];
        let line = |t: &[TypingUserDto]| typing_view(t, &[], None).line;
        assert_eq!(line(&typers).as_deref(), Some("Alice and Bob are typing…"));
        typers.push(typer("@c:x", "Carol"));
        assert_eq!(
            line(&typers).as_deref(),
            Some("Alice, Bob and 1 other are typing…")
        );
        typers.push(typer("@d:x", "Dave"));
        assert_eq!(
            line(&typers).as_deref(),
            Some("Alice, Bob and 2 others are typing…")
        );
    }

    /// The Tauri `typing_view` command returns this over JSON; the webview
    /// reads `line` and `agents`.
    ///
    /// Both field names are single words, so dropping `camelCase` is
    /// invisible here by construction; the test pins the names themselves.
    ///
    /// Mutation seen failing: `rename_all = "SCREAMING_SNAKE_CASE"` (keys
    /// became `LINE`/`AGENTS`).
    #[test]
    fn typing_view_has_the_wire_shape_the_webview_reads() {
        let view = TypingView {
            line: Some("Krishna is typing…".into()),
            agents: vec!["Atlas".into()],
        };
        let json = serde_json::to_value(&view).unwrap();
        assert_eq!(
            json,
            serde_json::json!({ "line": "Krishna is typing…", "agents": ["Atlas"] })
        );
        assert_eq!(serde_json::from_value::<TypingView>(json).unwrap(), view);
    }

    /// Port of `typingView.test.ts`: a typer is named by `label` (the
    /// timeline's rule), not by the raw display name.
    ///
    /// Mutation seen failing: naming by `display_name` falling back to the
    /// user id (line became "@chotu:x is typing…").
    #[test]
    fn a_typer_is_named_by_label_not_display_name() {
        let t = TypingUserDto {
            user_id: "@chotu:x".into(),
            display_name: None,
            label: "Super Chotu".into(),
        };
        assert_eq!(
            typing_view(&[t], &[], None).line.as_deref(),
            Some("Super Chotu is typing…")
        );
    }
}
