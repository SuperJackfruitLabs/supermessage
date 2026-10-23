//! A turn in the Live Activity's words, for the ongoing notification on
//! Android and the desktop's stand-in.
//!
//! Wording from iOS `FleetCard.swift:183-230`, over the core's own
//! [`TurnSummary`] (iOS's `FleetCard` reads the hub's pushed state, a
//! different input).
//!
//! Ruling: the elapsed time uses the core's one format,
//! [`elapsed_label`] ("1m 04s", zero-padded). iOS's own
//! `FleetCard.duration(seconds:)` (`:263-268`) does not pad ("1m 4s"); the
//! core's format wins so every host ticks and reads the same.
//!
//! Rulings: a running turn reads as running even when a tool has failed
//! (`in_progress` is checked first); a turn that timed out ([`TurnEnding::TimedOut`],
//! "Stopped" in `turn_status_word`) never gets a card; a failed turn's
//! "At step c of t" uses `c = completed_steps` (owner default OQ3).

use serde::{Deserialize, Serialize};

use crate::turn_label::elapsed_label;
use crate::turns::{TurnEnding, TurnSummary};

/// What a card says: a headline, a subtitle and which way it leans.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct TurnCard {
    pub headline: String,
    pub subtitle: String,
    pub in_progress: bool,
    pub failed: bool,
}

/// The card for one turn, or `None` for a turn the core gave up on.
pub fn turn_card(summary: &TurnSummary, agent_name: &str) -> Option<TurnCard> {
    if summary.ended_by == Some(TurnEnding::TimedOut) {
        return None;
    }
    let c = summary.completed_steps;
    let t = summary.total_steps;
    let counted = c > 0 && t > 0;
    if summary.in_progress {
        let headline = if summary.current_step_title.is_empty() {
            "Working".to_string()
        } else {
            summary.current_step_title.clone()
        };
        let subtitle = if counted {
            format!("{agent_name} · step {} of {t}", c.min(t))
        } else {
            agent_name.to_string()
        };
        return Some(TurnCard {
            headline,
            subtitle,
            in_progress: true,
            failed: false,
        });
    }
    let elapsed = summary
        .ended_at_ms
        .filter(|&end| end >= summary.started_at_ms)
        .map(|end| elapsed_label((end - summary.started_at_ms) as i64));
    if let Some(step) = &summary.failed_step {
        let parts: Vec<String> = [
            counted.then(|| format!("At step {} of {t}", c.min(t))),
            elapsed.map(|e| format!("after {e}")),
        ]
        .into_iter()
        .flatten()
        .collect();
        let subtitle = if parts.is_empty() {
            if step.is_empty() {
                "Failed".to_string()
            } else {
                step.clone()
            }
        } else {
            capitalised(&parts.join(" · "))
        };
        return Some(TurnCard {
            headline: format!("{agent_name} failed"),
            subtitle,
            in_progress: false,
            failed: true,
        });
    }
    let mut parts = vec![match elapsed {
        Some(e) => format!("Done in {e}"),
        None => "Done".to_string(),
    }];
    if t > 0 {
        parts.push(if t == 1 {
            "1 step".to_string()
        } else {
            format!("{t} steps")
        });
    }
    Some(TurnCard {
        headline: format!("{agent_name} finished"),
        subtitle: parts.join(" · "),
        in_progress: false,
        failed: false,
    })
}

/// The first character upper-cased (`FleetCard.swift:228`).
fn capitalised(line: &str) -> String {
    let mut chars = line.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NAME: &str = "Artistic Lyra";

    fn summary() -> TurnSummary {
        TurnSummary {
            room_id: "!r:example.org".into(),
            turn_id: 1,
            revision: 1,
            in_progress: false,
            ended_by: Some(TurnEnding::Done),
            started_at_ms: 1_000,
            ended_at_ms: None,
            last_activity_ms: 1_000,
            answering: false,
            has_thought: false,
            total_steps: 0,
            completed_steps: 0,
            failed_step: None,
            current_step_title: String::new(),
            step_is_tool: false,
            record_label: None,
            agent_id: None,
        }
    }

    fn running(title: &str, c: u32, t: u32) -> TurnSummary {
        TurnSummary {
            in_progress: true,
            ended_by: None,
            current_step_title: title.into(),
            completed_steps: c,
            total_steps: t,
            ..summary()
        }
    }

    fn ended(ending: TurnEnding, took_ms: Option<u64>, t: u32) -> TurnSummary {
        TurnSummary {
            ended_by: Some(ending),
            ended_at_ms: took_ms.map(|d| 1_000 + d),
            total_steps: t,
            ..summary()
        }
    }

    fn failed(text: &str, took_ms: Option<u64>, c: u32, t: u32) -> TurnSummary {
        TurnSummary {
            failed_step: Some(text.into()),
            completed_steps: c,
            ..ended(TurnEnding::Done, took_ms, t)
        }
    }

    fn words(card: Option<TurnCard>) -> (String, String) {
        let card = card.expect("a card");
        (card.headline, card.subtitle)
    }

    /// `FleetCardTests.swift:146-157`.
    ///
    /// Mutation seen failing: dropping the `min(c, t)` clamp reads "step 9 of
    /// 7"; dropping the "Working" fallback leaves an empty headline.
    #[test]
    fn the_heros_words_while_working() {
        let card = turn_card(&running("Running the tests", 3, 7), NAME).unwrap();
        assert_eq!(card.headline, "Running the tests");
        assert_eq!(card.subtitle, "Artistic Lyra · step 3 of 7");
        assert!(card.in_progress && !card.failed);
        assert_eq!(
            words(turn_card(&running("Running the tests", 0, 7), NAME)).1,
            "Artistic Lyra"
        );
        assert_eq!(
            words(turn_card(&running("Running the tests", 9, 7), NAME)).1,
            "Artistic Lyra · step 7 of 7"
        );
        assert_eq!(words(turn_card(&running("", 3, 7), NAME)).0, "Working");
    }

    /// `FleetCardTests.swift:159-173`.
    ///
    /// Mutation seen failing: `turn_duration_label` ("1m 4s") for
    /// `elapsed_label` fails the "1m 04s" case.
    #[test]
    fn a_finish_reads_like_a_delivery() {
        assert_eq!(
            words(turn_card(&ended(TurnEnding::Done, Some(237_000), 7), NAME)),
            (
                "Artistic Lyra finished".into(),
                "Done in 3m 57s · 7 steps".into()
            )
        );
        assert_eq!(
            words(turn_card(&ended(TurnEnding::Done, Some(64_000), 1), NAME)).1,
            "Done in 1m 04s · 1 step"
        );
        assert_eq!(
            words(turn_card(&ended(TurnEnding::Done, None, 7), NAME)).1,
            "Done · 7 steps"
        );
        assert_eq!(
            words(turn_card(&ended(TurnEnding::Done, Some(45_000), 0), NAME)).1,
            "Done in 45s"
        );
        assert_eq!(
            turn_card(&ended(TurnEnding::Reply, Some(237_000), 7), NAME),
            turn_card(&ended(TurnEnding::Done, Some(237_000), 7), NAME)
        );
    }

    /// `FleetCardTests.swift:163-164`.
    ///
    /// Mutation seen failing: dropping the capitalisation reads "after 1m
    /// 12s" in the c = 0 case.
    #[test]
    fn a_failure_says_where_and_after_how_long() {
        let card = turn_card(&failed("cargo test", Some(72_000), 4, 7), "Writer Quill").unwrap();
        assert_eq!(card.headline, "Writer Quill failed");
        assert_eq!(card.subtitle, "At step 4 of 7 · after 1m 12s");
        assert!(card.failed && !card.in_progress);
        assert_eq!(
            words(turn_card(
                &failed("cargo test", Some(72_000), 0, 7),
                "Writer Quill"
            ))
            .1,
            "After 1m 12s"
        );
        assert_eq!(
            words(turn_card(&failed("cargo test", None, 0, 7), "Writer Quill")).1,
            "cargo test"
        );
        assert_eq!(
            words(turn_card(&failed("", None, 0, 7), "Writer Quill")).1,
            "Failed"
        );
    }

    /// Mutation seen failing: letting `TimedOut` through yields a card.
    #[test]
    fn a_turn_the_core_gave_up_on_has_no_card() {
        assert_eq!(
            turn_card(&ended(TurnEnding::TimedOut, Some(5_000), 3), NAME),
            None
        );
        let timed_out_failed = TurnSummary {
            ended_by: Some(TurnEnding::TimedOut),
            ..failed("cargo test", Some(5_000), 1, 3)
        };
        assert_eq!(turn_card(&timed_out_failed, NAME), None);
    }

    /// Mutation seen failing: checking `failed_step` before `in_progress`
    /// makes the card say "failed".
    #[test]
    fn a_running_turn_with_a_failed_tool_still_reads_as_running() {
        let turn = TurnSummary {
            failed_step: Some("cargo test".into()),
            ..running("Retrying", 2, 5)
        };
        let card = turn_card(&turn, NAME).unwrap();
        assert!(card.in_progress && !card.failed);
        assert_eq!(card.headline, "Retrying");
    }
}
