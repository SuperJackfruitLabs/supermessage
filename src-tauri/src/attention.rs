//! What the window asks of the person (parity M6, G4): the Needs you count in
//! the window title. The count is the core's `NeedsYouInbox.badge_count`, the
//! number behind the sidebar badge; the page hands it over and nothing here
//! recounts. M7 adds the tray: the same count, and how many agents are working, reach
//! the tray's lines, dot and tooltip in the core's words (`widget::needs_you_line`,
//! `widget::pulse`); nothing here words or counts anything itself.

use supermessage_core::widget;
use tauri::{AppHandle, Manager, Runtime};

#[cfg(desktop)]
use crate::tray::{TauriTray, TrayView};

const APP_NAME: &str = "supermessage";

/// The main window's title for `needs_you` waiting decisions: "(3) supermessage",
/// or just the app name at zero.
pub fn title_for(needs_you: u32) -> String {
    if needs_you == 0 {
        APP_NAME.to_string()
    } else {
        format!("({needs_you}) {APP_NAME}")
    }
}

/// What the tray says for a Needs you count and a working count, in the core's words.
#[derive(Debug, PartialEq, Eq)]
pub struct TrayWords {
    /// Menu line 1: "Nothing needs you", "1 needs you", "3 need you".
    pub needs_you: String,
    /// Menu line 2: "All quiet", "2 working".
    pub working: String,
    /// The tooltip: "2 working · 1 needs you".
    pub tooltip: String,
    /// Whether the icon wears the dot: exactly when a decision waits.
    pub dot: bool,
}

pub fn tray_words(needs_you: u32, working: u32) -> TrayWords {
    TrayWords {
        needs_you: widget::needs_you_line(needs_you, false),
        working: widget::pulse(working, 0, false),
        tooltip: widget::pulse(working, needs_you, false),
        dot: needs_you > 0,
    }
}

/// Shows the counts on the tray.
#[cfg(desktop)]
pub fn show_on_tray(view: &impl TrayView, needs_you: u32, working: u32) {
    let words = tray_words(needs_you, working);
    view.set_lines(&words.needs_you, &words.working);
    view.set_dot(words.dot);
    view.set_tooltip(&words.tooltip);
}

/// Puts the Needs you count in the main window's title, written only when it differs from the
/// one showing, and both counts on the tray. The page calls this when a count changes.
#[tauri::command]
pub fn set_attention<R: Runtime>(
    needs_you: u32,
    working: u32,
    app: AppHandle<R>,
) -> Result<(), String> {
    #[cfg(desktop)]
    if let Some(tray) = app.try_state::<TauriTray<R>>() {
        show_on_tray(&*tray, needs_you, working);
    }
    #[cfg(not(desktop))]
    let _ = working;
    let Some(window) = app.get_webview_window("main") else {
        return Ok(());
    };
    let title = title_for(needs_you);
    if window.title().map_err(|e| e.to_string())? == title {
        return Ok(());
    }
    window.set_title(&title).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(desktop)]
    use std::cell::RefCell;

    #[cfg(desktop)]
    #[derive(Default)]
    struct FakeTray {
        calls: RefCell<Vec<String>>,
    }

    #[cfg(desktop)]
    impl TrayView for FakeTray {
        fn set_lines(&self, needs_you: &str, working: &str) {
            self.calls
                .borrow_mut()
                .push(format!("lines {needs_you} / {working}"));
        }
        fn set_dot(&self, on: bool) {
            self.calls.borrow_mut().push(format!("dot {on}"));
        }
        fn set_tooltip(&self, text: &str) {
            self.calls.borrow_mut().push(format!("tooltip {text}"));
        }
    }

    #[test]
    fn title_for_zero_is_the_app_name() {
        assert_eq!(title_for(0), "supermessage");
    }

    #[test]
    fn title_for_three_leads_with_the_count() {
        assert_eq!(title_for(3), "(3) supermessage");
    }

    #[test]
    fn tray_words_at_zero_are_nothing_needs_you_and_all_quiet() {
        let w = tray_words(0, 0);
        assert_eq!(w.needs_you, "Nothing needs you");
        assert_eq!(w.working, "All quiet");
        assert_eq!(w.tooltip, "All quiet");
    }

    #[test]
    fn tray_words_name_one_and_many() {
        assert_eq!(tray_words(1, 0).needs_you, "1 needs you");
        assert_eq!(tray_words(3, 0).needs_you, "3 need you");
        assert_eq!(tray_words(0, 2).working, "2 working");
    }

    #[test]
    fn the_tooltip_joins_both() {
        assert_eq!(tray_words(1, 2).tooltip, "2 working \u{b7} 1 needs you");
    }

    #[test]
    fn the_working_line_never_carries_the_needs_you_count() {
        assert_eq!(tray_words(4, 2).working, "2 working");
    }

    #[cfg(desktop)]
    #[test]
    fn set_attention_turns_the_dot_on_only_above_zero() {
        for (n, on) in [(0, false), (1, true), (7, true)] {
            let tray = FakeTray::default();
            show_on_tray(&tray, n, 0);
            assert!(
                tray.calls.borrow().contains(&format!("dot {on}")),
                "needs_you {n}: {:?}",
                tray.calls.borrow()
            );
        }
    }

    #[cfg(desktop)]
    #[test]
    fn set_attention_sets_the_same_count_in_the_title_and_the_tray() {
        let tray = FakeTray::default();
        show_on_tray(&tray, 3, 1);
        assert_eq!(title_for(3), "(3) supermessage");
        assert_eq!(
            *tray.calls.borrow(),
            vec![
                "lines 3 need you / 1 working".to_string(),
                "dot true".to_string(),
                "tooltip 1 working \u{b7} 3 need you".to_string(),
            ]
        );
    }
}
