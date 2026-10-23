//! What the window asks of the person (parity M6, G4): the Needs you count in
//! the window title. The count is the core's `NeedsYouInbox.badge_count`, the
//! number behind the sidebar badge; the page hands it over and nothing here
//! recounts. M7 adds the tray to `set_attention`.

use tauri::{AppHandle, Manager, Runtime};

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

/// Puts the Needs you count in the main window's title. The title is written
/// only when it differs from the one showing.
#[tauri::command]
pub fn set_attention<R: Runtime>(needs_you: u32, app: AppHandle<R>) -> Result<(), String> {
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

    #[test]
    fn title_for_zero_is_the_app_name() {
        assert_eq!(title_for(0), "supermessage");
    }

    #[test]
    fn title_for_three_leads_with_the_count() {
        assert_eq!(title_for(3), "(3) supermessage");
    }
}
