//! What a finished turn's record says when it is collapsed, and how long a
//! running turn says it has been going.
//!
//! Ported from iOS `TurnRecordLabel.swift` and `ElapsedTime.swift`. The two
//! duration formats stay two functions on purpose: the running label is
//! fixed-width where it ticks ("1m 04s"), the record's is not ("1m 5s").

/// "Thought for 12s · 2 steps", "Thought for 12s", or "2 steps · 12s" for a
/// turn that only used tools.
///
/// `elapsed_ms` is the turn's own time, first event to last; `None` leaves the
/// time out rather than inventing one.
pub fn turn_record_label(thought: bool, steps: u32, elapsed_ms: Option<u64>) -> String {
    let took = elapsed_ms.map(turn_duration_label);
    let step_text = if steps == 1 {
        "1 step".to_string()
    } else {
        format!("{steps} steps")
    };
    let thought_text = match &took {
        Some(took) => format!("Thought for {took}"),
        None => "Thought".to_string(),
    };
    match (thought, steps > 0) {
        (true, true) => format!("{thought_text} \u{b7} {step_text}"),
        (true, false) => thought_text,
        _ => match took {
            Some(took) => format!("{step_text} \u{b7} {took}"),
            None => step_text,
        },
    }
}

/// "12s", or "2m 5s" past a minute. Never "0s": a turn that finished took some
/// time, and zero reads as a fault. Rounds half away from zero.
pub fn turn_duration_label(elapsed_ms: u64) -> String {
    let whole = ((elapsed_ms as f64 / 1000.0).round() as u64).max(1);
    if whole < 60 {
        format!("{whole}s")
    } else {
        format!("{}m {}s", whole / 60, whole % 60)
    }
}

/// How long a turn has been running, as the activity card says it: "8s",
/// "1m 04s", "1h 02m". The seconds are zero-padded past the first minute so
/// the label does not change width every tick. Floors; negative clamps to 0.
pub fn elapsed_label(elapsed_ms: i64) -> String {
    let total = elapsed_ms.div_euclid(1000).max(0);
    let hours = total / 3600;
    let minutes = (total % 3600) / 60;
    let seconds = total % 60;
    if hours > 0 {
        format!("{hours}h {minutes:02}m")
    } else if minutes > 0 {
        format!("{minutes}m {seconds:02}s")
    } else {
        format!("{seconds}s")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ported from `TurnRecordLabelTests.swift` (`reasoningLeadsThenTheSteps`).
    ///
    /// Mutation seen failing: forcing the plural (`if steps == 1` to `if false`)
    /// failed it.
    #[test]
    fn reasoning_leads_then_the_steps() {
        assert_eq!(
            turn_record_label(true, 2, Some(12_000)),
            "Thought for 12s \u{b7} 2 steps"
        );
        assert_eq!(
            turn_record_label(true, 1, Some(12_000)),
            "Thought for 12s \u{b7} 1 step"
        );
        assert_eq!(turn_record_label(true, 0, Some(12_000)), "Thought for 12s");
    }

    /// Ported from `TurnRecordLabelTests.swift`.
    ///
    /// Mutation seen failing: putting the time before the steps in the tools-only
    /// arm failed it.
    #[test]
    fn a_turn_that_only_used_tools_says_how_many_and_how_long() {
        assert_eq!(
            turn_record_label(false, 3, Some(65_000)),
            "3 steps \u{b7} 1m 5s"
        );
    }

    /// Ported from `TurnRecordLabelTests.swift`.
    ///
    /// Mutation seen failing: the stubbed-out functions failed it (RED run);
    /// no single-line mutant pins the `None` arms beyond that.
    #[test]
    fn no_time_known_leaves_the_time_out_rather_than_inventing_one() {
        assert_eq!(turn_record_label(true, 0, None), "Thought");
        assert_eq!(turn_record_label(false, 2, None), "2 steps");
    }

    /// Ported from `TurnRecordLabelTests.swift` (`durations`), plus 1_500 and
    /// 89_500 to pin rounding half away from zero.
    ///
    /// Mutation seen failing: `floor()` instead of `round()` failed it;
    /// removing `.max(1)` failed the 200 ms case.
    #[test]
    fn durations() {
        assert_eq!(turn_duration_label(200), "1s");
        assert_eq!(turn_duration_label(59_400), "59s");
        assert_eq!(turn_duration_label(125_000), "2m 5s");
        assert_eq!(turn_duration_label(1_500), "2s");
        assert_eq!(turn_duration_label(89_500), "1m 30s");
    }

    /// Ported from `ComposerRevampTests.swift` (`ElapsedTime`).
    ///
    /// Mutation seen failing: dropping the `:02` on the minutes, dropping it on
    /// the seconds, and replacing `div_euclid(1000).max(0)` with `/ 1000` each
    /// failed it.
    #[test]
    fn seconds_then_minutes_with_padded_seconds_then_hours() {
        assert_eq!(elapsed_label(8_900), "8s");
        assert_eq!(elapsed_label(64_000), "1m 04s");
        assert_eq!(elapsed_label(600_000), "10m 00s");
        assert_eq!(elapsed_label(3_725_000), "1h 02m");
        assert_eq!(elapsed_label(-3_000), "0s");
    }
}
