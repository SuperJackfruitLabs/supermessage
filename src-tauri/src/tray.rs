//! The system tray icon and its menu (parity M7, G4).
//!
//! iOS has no tray, so this is the desktop equivalent of its widgets: a place that shows,
//! without opening the app, whether anything needs the reader. The words in the menu come
//! from the core (`widget::needs_you_line`, `widget::pulse`); the app counts nothing.
//!
//! - **Everything is in the menu.** Tauri emits no tray click events on Linux, so the menu
//!   opens on both buttons on every platform (`show_menu_on_left_click`), and its lines carry
//!   what a tooltip would.
//! - **The tooltip is macOS and Windows only.** Linux does not support one.
//! - **An amber dot** on the icon means a decision is waiting, and nothing else. It is the
//!   app's own 32x32 icon with the dot painted onto its pixels at start-up ([`dot_icon`]),
//!   never a second hand-drawn image, in the **dark** theme's `signal`: a panel's theme is
//!   unknown, and the brighter amber reads on a light and a dark panel alike.
//! - **The tray can fail to exist.** On Linux it needs libayatana-appindicator (or
//!   libappindicator) at runtime, which may not be installed. [`install`] logs and carries
//!   on without one.
//! - **A tray can exist and show nothing.** GNOME without its AppIndicator extension runs no
//!   `org.kde.StatusNotifierWatcher`, so the item is created and nobody displays it. On Linux
//!   the tray counts as available only when the session bus has an owner for that name
//!   ([`probe_watcher`]); where it has none, "keep running" is unavailable, as with no tray.
//!
//! It is built from Rust only and the webview is granted no tray permission.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};

use supermessage_core::widget;
use tauri::image::Image;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{TrayIcon, TrayIconBuilder};
use tauri::{AppHandle, Emitter, Manager, Runtime};

use crate::tokens::SIGNAL_DARK_RGBA;

/// The menu item ids.
pub const NEEDS_YOU_LINE: &str = "needs-you-line";
pub const PULSE_LINE: &str = "pulse-line";
pub const OPEN_NEEDS_YOU: &str = "open-needs-you";
pub const OPEN_APP: &str = "open-app";
pub const QUIT: &str = "quit";

/// The page listens for this and chooses the destination in `to`.
pub const NAVIGATE_EVENT: &str = "sm://navigate";

/// The sidebar destination for Needs you (`Destination` in `stores/destinations.svelte.ts`).
const NEEDS_YOU_DESTINATION: &str = "needsYou";

/// The app's own icon, which the tray wears.
#[cfg(not(target_os = "macos"))]
const ICON_PNG: &[u8] = include_bytes!("../icons/32x32.png");

/// macOS's menu bar recolours a template image by its alpha alone, and the app icon is an
/// opaque rounded square, which would show as a blob. This is the mark itself, black on
/// transparent at 64 px (`tray-icon` scales it to the bar's 18 pt height, about 3.6 px a
/// point), with the top-right corner left free for the dot. Made by
/// `assets/build-tray-template.py`.
#[cfg(target_os = "macos")]
const ICON_PNG: &[u8] = include_bytes!("../tray/template@2x.png");

/// The dot's radius, and the ring of the icon's transparent margin kept clear around it, at
/// the 32 px size. A larger icon scales both.
const DOT_RADIUS: i64 = 5;
const DOT_RING: i64 = 1;

/// `rgba` (`width` x `height`, four bytes a pixel) with a filled `colour` dot in the
/// top-right corner. The dot has radius 5 px and is ringed by 1 px made transparent, so it
/// stays apart from the glyph (at 32 px; the sizes scale with `width / 32`). The dot and its ring lie in a square flush
/// with the corner; no pixel outside that square is touched.
pub fn dot_icon(rgba: &[u8], width: u32, height: u32, colour: [u8; 4]) -> Vec<u8> {
    let mut out = rgba.to_vec();
    let scale = i64::from(width) / 32;
    let (radius, ring) = (DOT_RADIUS * scale.max(1), DOT_RING * scale.max(1));
    let reach = radius + ring;
    let (cx, cy) = (i64::from(width) - 1 - reach, reach);
    for y in (cy - reach).max(0)..=(cy + reach).min(i64::from(height) - 1) {
        for x in (cx - reach).max(0)..=(cx + reach).min(i64::from(width) - 1) {
            let distance_squared = (x - cx).pow(2) + (y - cy).pow(2);
            let at = (y as usize * width as usize + x as usize) * 4;
            if distance_squared <= radius * radius {
                out[at..at + 4].copy_from_slice(&colour);
            } else if distance_squared <= reach * reach {
                out[at..at + 4].copy_from_slice(&[0, 0, 0, 0]);
            }
        }
    }
    out
}

/// What the tray shows. [`TauriTray`] is the real one.
pub trait TrayView {
    /// The two menu lines, as the core words them.
    fn set_lines(&self, needs_you: &str, working: &str);
    /// Whether the icon wears the amber dot.
    fn set_dot(&self, on: bool);
    /// The tooltip. Not every platform has one.
    fn set_tooltip(&self, text: &str);
}

/// The menu, and handles on the two lines that change.
struct Items<R: Runtime> {
    menu: Menu<R>,
    needs_you: MenuItem<R>,
    working: MenuItem<R>,
}

fn build_menu<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<Items<R>> {
    // *(no iOS copy)*: the verbs. The two lines are the core's zero-state words, until the
    // page hands over the counts.
    let needs_you = MenuItem::with_id(
        app,
        NEEDS_YOU_LINE,
        widget::needs_you_line(0, false),
        false,
        None::<&str>,
    )?;
    let working = MenuItem::with_id(
        app,
        PULSE_LINE,
        widget::pulse(0, 0, false),
        false,
        None::<&str>,
    )?;
    let open_needs_you =
        MenuItem::with_id(app, OPEN_NEEDS_YOU, "Open Needs you", true, None::<&str>)?;
    let open_app = MenuItem::with_id(app, OPEN_APP, "Open supermessage", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT, "Quit supermessage", true, None::<&str>)?;
    let first = PredefinedMenuItem::separator(app)?;
    let second = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(
        app,
        &[
            &needs_you,
            &working,
            &first,
            &open_needs_you,
            &open_app,
            &second,
            &quit,
        ],
    )?;
    Ok(Items {
        menu,
        needs_you,
        working,
    })
}

/// The tray icon over the real windowing system.
pub struct TauriTray<R: Runtime = tauri::Wry> {
    icon: TrayIcon<R>,
    needs_you: MenuItem<R>,
    working: MenuItem<R>,
    plain: Image<'static>,
    dotted: Image<'static>,
}

/// Builds the tray with its menu. The menu opens on both mouse buttons; choosing an item is
/// handled by [`on_menu_event`].
pub fn build<R: Runtime>(app: &AppHandle<R>) -> tauri::Result<TauriTray<R>> {
    let decoded = Image::from_bytes(ICON_PNG)?;
    let base = decoded.rgba().to_vec();
    let plain = Image::new_owned(base.clone(), decoded.width(), decoded.height());
    let dotted = Image::new_owned(
        dot_icon(&base, decoded.width(), decoded.height(), SIGNAL_DARK_RGBA),
        decoded.width(),
        decoded.height(),
    );
    let Items {
        menu,
        needs_you,
        working,
    } = build_menu(app)?;
    let builder = TrayIconBuilder::with_id("main")
        .icon(plain.clone())
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| on_menu_event(app, event.id().as_ref()));
    // The menu bar expects a template image (it tints it for light and dark bars).
    #[cfg(target_os = "macos")]
    let builder = builder.icon_as_template(true);
    // Linux has no tooltip; the lines in the menu say it instead.
    #[cfg(any(target_os = "macos", target_os = "windows"))]
    let builder = builder.tooltip(widget::pulse(0, 0, false));
    Ok(TauriTray {
        icon: builder.build(app)?,
        needs_you,
        working,
        plain,
        dotted,
    })
}

/// Builds the tray and hands it to the app as managed state, or says why there is none.
/// A desktop without the library or the host the tray needs runs the app without one: the
/// window title still carries the count.
pub fn install(app: &AppHandle) {
    // The Linux backend loads libayatana-appindicator at runtime and panics, rather than
    // errors, when it is absent.
    match catch_unwind(AssertUnwindSafe(|| build(app))) {
        Ok(Ok(tray)) => {
            app.manage(tray);
        }
        Ok(Err(error)) => tracing::warn!(%error, "no system tray; continuing without one"),
        Err(_) => tracing::warn!(
            "no system tray (is libayatana-appindicator installed?); continuing without one"
        ),
    }
}

impl<R: Runtime> TrayView for TauriTray<R> {
    fn set_lines(&self, needs_you: &str, working: &str) {
        if let Err(error) = self.needs_you.set_text(needs_you) {
            tracing::warn!(%error, "could not update the tray's Needs you line");
        }
        if let Err(error) = self.working.set_text(working) {
            tracing::warn!(%error, "could not update the tray's pulse line");
        }
    }

    fn set_dot(&self, on: bool) {
        let icon = if on { &self.dotted } else { &self.plain };
        // On macOS `set_icon` drops the template flag (`tray-icon` 0.24 passes
        // `icon_is_template: false`), and the glyph would be drawn black on a dark menu bar.
        // This sets the image and the flag together, so the bar never shows it untinted.
        #[cfg(target_os = "macos")]
        let set = self
            .icon
            .set_icon_with_as_template(Some(icon.clone()), true);
        #[cfg(not(target_os = "macos"))]
        let set = self.icon.set_icon(Some(icon.clone()));
        if let Err(error) = set {
            tracing::warn!(%error, "could not update the tray icon");
        }
    }

    fn set_tooltip(&self, text: &str) {
        #[cfg(any(target_os = "macos", target_os = "windows"))]
        if let Err(error) = self.icon.set_tooltip(Some(text)) {
            tracing::warn!(%error, "could not update the tray tooltip");
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        let _ = text;
    }
}

/// What closing the main window does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CloseAction {
    /// Hide the window and keep the app (sync, notifications, the tray) running.
    Hide,
    /// Close it, which ends the app: Tauri exits when its last window is destroyed, on every
    /// platform (macOS included; its "stay open with no windows" convention is not applied).
    Close,
}

/// `Hide` exactly when the reader chose to keep running.
pub fn close_action(keep_running: bool) -> CloseAction {
    if keep_running {
        CloseAction::Hide
    } else {
        CloseAction::Close
    }
}

/// The reader's "keep running in the tray" choice, sent by the page at start and on change.
/// Off until the page says otherwise.
#[derive(Default)]
pub struct CloseBehaviour {
    keep_running: AtomicBool,
    told: AtomicBool,
}

impl CloseBehaviour {
    pub fn set(&self, keep_running: bool) {
        self.keep_running.store(keep_running, Ordering::SeqCst);
    }

    pub fn keep_running(&self) -> bool {
        self.keep_running.load(Ordering::SeqCst)
    }

    /// True exactly once per run: the first time the window hides, which is when the reader
    /// needs telling where the app went.
    pub fn first_hide(&self) -> bool {
        !self.told.swap(true, Ordering::SeqCst)
    }
}

/// The well-known name of the StatusNotifier watcher, which a panel that shows tray items
/// (KDE, GNOME's AppIndicator extension) owns.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub const STATUS_NOTIFIER_WATCHER: &str = "org.kde.StatusNotifierWatcher";

/// What the session bus said about [`STATUS_NOTIFIER_WATCHER`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub enum WatcherProbe {
    /// Someone owns it: a panel can show the tray.
    Owned,
    /// Nobody owns it: the tray item exists and nothing shows it.
    NoOwner,
    /// The bus did not answer (no bus, a timeout, a refusal). Says nothing either way.
    Failed,
}

/// Asks a session bus whether a well-known name has an owner.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub trait NameOwners {
    fn name_has_owner(&self, name: &str) -> Result<bool, String>;
}

#[cfg(target_os = "linux")]
impl NameOwners for crate::notify::SessionBusBackend {
    fn name_has_owner(&self, name: &str) -> Result<bool, String> {
        crate::notify::SessionBusBackend::name_has_owner(self, name).map_err(|e| e.to_string())
    }
}

/// Asks `bus` whether a StatusNotifier watcher runs. Blocks for one bus round trip.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
pub fn probe_watcher(bus: &impl NameOwners) -> WatcherProbe {
    match bus.name_has_owner(STATUS_NOTIFIER_WATCHER) {
        Ok(true) => WatcherProbe::Owned,
        Ok(false) => WatcherProbe::NoOwner,
        Err(error) => {
            tracing::warn!(%error, "could not ask the session bus for a tray watcher; assuming the tray shows");
            WatcherProbe::Failed
        }
    }
}

/// Whether the tray can be seen: it was created, and (Linux) the bus did not say that nothing
/// shows it. A failed probe keeps the tray available, which is how the app behaved before it
/// asked.
pub fn tray_shown(tray_exists: bool, watcher: WatcherProbe) -> bool {
    tray_exists && watcher != WatcherProbe::NoOwner
}

/// The last watcher probe's answer, for the close handler. Until a probe answers, the tray
/// counts as shown.
pub struct TrayHost {
    watcher: std::sync::Mutex<WatcherProbe>,
}

impl Default for TrayHost {
    fn default() -> Self {
        Self {
            watcher: std::sync::Mutex::new(WatcherProbe::Failed),
        }
    }
}

impl TrayHost {
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub fn record(&self, probe: WatcherProbe) {
        *self.watcher.lock().unwrap_or_else(|e| e.into_inner()) = probe;
    }

    pub fn watcher(&self) -> WatcherProbe {
        *self.watcher.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Whether this app's tray can be seen now, by the last probe ([`tray_shown`]).
pub fn tray_available<R: Runtime>(app: &AppHandle<R>) -> bool {
    let watcher = app
        .try_state::<TrayHost>()
        .map_or(WatcherProbe::Failed, |host| host.watcher());
    tray_shown(app.try_state::<TauriTray<R>>().is_some(), watcher)
}

/// Asks the session bus again whether a watcher runs, and records the answer (Linux, with a
/// tray). One bus round trip on the notifier's connection, bounded by its timeout: call it off
/// the main thread. Elsewhere it does nothing, and the tray counts as shown when it exists.
pub fn refresh_tray_host<R: Runtime>(app: &AppHandle<R>) {
    #[cfg(target_os = "linux")]
    {
        if app.try_state::<TauriTray<R>>().is_none() {
            return;
        }
        let (Some(host), Some(bus)) = (
            app.try_state::<TrayHost>(),
            app.try_state::<std::sync::Arc<crate::notify::SessionBusBackend>>(),
        ) else {
            return;
        };
        host.record(probe_watcher(&**bus));
    }
    #[cfg(not(target_os = "linux"))]
    let _ = app;
}

/// What the app says the first time its window hides to the tray. Short and plain, no iOS
/// counterpart and none in the core *(desktop-only copy)*.
/// The body names no place the app "stays": on GNOME without the AppIndicator extension the tray
/// is invisible, and on macOS it is the menu bar.
pub const STILL_RUNNING_TITLE: &str = "supermessage is still running";
#[cfg(not(target_os = "macos"))]
pub const STILL_RUNNING_BODY: &str =
    "Open it again to bring the window back. To close it, choose Quit from the tray menu.";
#[cfg(target_os = "macos")]
pub const STILL_RUNNING_BODY: &str =
    "Open it again to bring the window back. To close it, choose Quit from its menu bar icon.";

/// The first-hide notice: about the app, for no account and no room. Where the server offers
/// a click (Linux), the click brings the window back (`notify_actions::KEY_REVEAL`).
pub fn still_running_note() -> crate::notify::PostedNote {
    crate::notify::PostedNote {
        id: "tray:still-running".into(),
        room_id: String::new(),
        title: STILL_RUNNING_TITLE.into(),
        body: STILL_RUNNING_BODY.into(),
        actions: Vec::new(),
        user_info: crate::notify_actions::reveal_user_info(),
        account: None,
    }
}

/// A window the close handler can keep open and out of sight.
pub trait CloseTarget {
    /// Stops the close and hides the window.
    fn keep_hidden(&self);
}

/// Decides one close request: with "keep running" chosen and a tray to come back by, the window
/// is hidden instead of closed, and `tell` runs the first time that happens. Otherwise nothing
/// is done and the close goes through. Returns whether the window was kept.
pub fn on_close_request(
    chosen: bool,
    tray_exists: bool,
    behaviour: &CloseBehaviour,
    window: &dyn CloseTarget,
    tell: impl FnOnce(),
) -> bool {
    if effective_close(chosen, tray_exists) != CloseAction::Hide {
        return false;
    }
    window.keep_hidden();
    if behaviour.first_hide() {
        tell();
    }
    true
}

/// A second launch of the app: the running one comes to the front, hidden or not.
pub fn reveal_running_app(shell: &dyn Shell) {
    shell.show_window();
}

/// Handles `CloseRequested` for the main window (see [`on_close_request`]). The first-hide
/// notice is posted off this thread: a post is a bus round trip.
pub fn handle_close_requested<R: Runtime>(window: &tauri::Window<R>, api: &tauri::CloseRequestApi) {
    struct Target<'a, R: Runtime>(&'a tauri::Window<R>, &'a tauri::CloseRequestApi);
    impl<R: Runtime> CloseTarget for Target<'_, R> {
        fn keep_hidden(&self) {
            self.1.prevent_close();
            let _ = self.0.hide();
        }
    }
    let app = window.app_handle();
    let Some(behaviour) = app.try_state::<CloseBehaviour>() else {
        return;
    };
    on_close_request(
        behaviour.keep_running(),
        tray_available(app),
        &behaviour,
        &Target(window, api),
        || {
            let Some(poster) = app.try_state::<std::sync::Arc<dyn crate::notify::Poster>>() else {
                return;
            };
            let poster = std::sync::Arc::clone(&poster);
            std::thread::spawn(move || {
                let _ = poster.post(&still_running_note());
            });
        },
    );
}

/// The reader's choice, held to what the desktop can honour: no tray, no hiding.
pub fn effective_close(chosen: bool, tray_exists: bool) -> CloseAction {
    close_action(chosen && tray_exists)
}

/// The page tells Rust whether the reader wants the app to keep running when the window is
/// closed, at start, on every change and each time the setting is shown. Returns whether it
/// can: false where the tray could not be created or (Linux) nothing shows it, and then closing
/// still quits. Async, so the watcher probe's bus round trip runs off the main thread.
#[tauri::command]
pub async fn window_close_behaviour<R: Runtime>(keep_running: bool, app: AppHandle<R>) -> bool {
    if let Some(setting) = app.try_state::<CloseBehaviour>() {
        setting.set(keep_running);
    }
    let probing = app.clone();
    let _ = tauri::async_runtime::spawn_blocking(move || refresh_tray_host(&probing)).await;
    tray_available(&app)
}

/// What a menu choice does to the app.
pub trait Shell {
    /// Shows, unminimises and focuses the main window.
    fn show_window(&self);
    /// Tells the page to go to the sidebar destination `to`.
    fn navigate(&self, to: &str);
    /// Ends the process.
    fn quit(&self);
}

impl<R: Runtime> Shell for AppHandle<R> {
    fn show_window(&self) {
        let Some(window) = self.get_webview_window("main") else {
            return;
        };
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }

    fn navigate(&self, to: &str) {
        if let Some(window) = self.get_webview_window("main") {
            let _ = window.emit(NAVIGATE_EVENT, serde_json::json!({ "to": to }));
        }
    }

    fn quit(&self) {
        self.exit(0);
    }
}

/// Carries out the menu item `id`.
pub fn handle_menu(id: &str, shell: &impl Shell) {
    match id {
        OPEN_NEEDS_YOU => {
            shell.show_window();
            shell.navigate(NEEDS_YOU_DESTINATION);
        }
        OPEN_APP => shell.show_window(),
        QUIT => shell.quit(),
        _ => {}
    }
}

fn on_menu_event<R: Runtime>(app: &AppHandle<R>, id: &str) {
    handle_menu(id, app);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use tauri::menu::MenuItemKind;

    const SIZE: u32 = 32;
    const AMBER: [u8; 4] = [0xE8, 0xA3, 0x3D, 0xFF];

    /// A made-up icon with no transparent pixel, so any change shows.
    fn icon() -> Vec<u8> {
        (0..SIZE * SIZE)
            .flat_map(|i| [(i % 251) as u8, (i % 241) as u8, (i % 239) as u8, 0xFF])
            .collect()
    }

    fn pixel(rgba: &[u8], x: u32, y: u32) -> &[u8] {
        let at = ((y * SIZE + x) * 4) as usize;
        &rgba[at..at + 4]
    }

    #[test]
    fn dot_icon_paints_the_signal_colour_in_the_top_right() {
        let painted = dot_icon(&icon(), SIZE, SIZE, AMBER);
        // The dot's centre, six pixels in from the right and top edges.
        assert_eq!(pixel(&painted, SIZE - 7, 6), AMBER);
        // Radius 5: its edge is the colour, the ring beyond it is clear.
        assert_eq!(pixel(&painted, SIZE - 7 + 5, 6), AMBER);
        assert_eq!(pixel(&painted, SIZE - 7 + 6, 6), [0, 0, 0, 0]);
        // Nothing lands in the top-left.
        assert_ne!(pixel(&painted, 6, 6), AMBER);
    }

    #[test]
    fn dot_icon_scales_with_a_larger_icon() {
        let size = 64u32;
        let before = vec![0xFF; (size * size * 4) as usize];
        let after = dot_icon(&before, size, size, AMBER);
        let at = |x: u32, y: u32| {
            let i = ((y * size + x) * 4) as usize;
            after[i..i + 4].to_vec()
        };
        // Radius 10 about (64 - 1 - 12, 12).
        assert_eq!(at(51, 12), AMBER);
        assert_eq!(at(51 + 10, 12), AMBER);
        assert_eq!(at(51 + 11, 12), [0, 0, 0, 0]);
    }

    #[test]
    fn dot_icon_leaves_the_rest_of_the_icon_untouched() {
        let before = icon();
        let after = dot_icon(&before, SIZE, SIZE, AMBER);
        // The dot and its ring fit a 13 px square flush with the top-right corner.
        let inside = |x: u32, y: u32| x >= SIZE - 13 && y < 13;
        let mut changed = 0;
        for y in 0..SIZE {
            for x in 0..SIZE {
                if inside(x, y) {
                    changed += usize::from(pixel(&before, x, y) != pixel(&after, x, y));
                } else {
                    assert_eq!(pixel(&before, x, y), pixel(&after, x, y), "({x}, {y})");
                }
            }
        }
        assert!(changed > 0, "the dot was painted");
    }

    #[test]
    fn the_menu_has_its_items_in_order() {
        let app = tauri::test::mock_app();
        let items = build_menu(app.handle()).unwrap().menu.items().unwrap();
        let seen: Vec<(String, Option<String>)> = items
            .iter()
            .map(|item| match item {
                MenuItemKind::MenuItem(i) => (i.id().0.clone(), Some(i.text().unwrap())),
                MenuItemKind::Predefined(_) => ("separator".into(), None),
                _ => panic!("an unexpected kind of item"),
            })
            .collect();
        let line = |id: &str, text: &str| (id.to_string(), Some(text.to_string()));
        assert_eq!(
            seen,
            vec![
                line(NEEDS_YOU_LINE, "Nothing needs you"),
                line(PULSE_LINE, "All quiet"),
                ("separator".into(), None),
                line(OPEN_NEEDS_YOU, "Open Needs you"),
                line(OPEN_APP, "Open supermessage"),
                ("separator".into(), None),
                line(QUIT, "Quit supermessage"),
            ]
        );
        let enabled: Vec<bool> = items
            .iter()
            .filter_map(|item| item.as_menuitem().map(|i| i.is_enabled().unwrap()))
            .collect();
        assert_eq!(
            enabled,
            [false, false, true, true, true],
            "the two lines only inform"
        );
    }

    #[derive(Default)]
    struct FakeShell {
        calls: RefCell<Vec<String>>,
    }

    impl Shell for FakeShell {
        fn show_window(&self) {
            self.calls.borrow_mut().push("show".into());
        }
        fn navigate(&self, to: &str) {
            self.calls.borrow_mut().push(format!("navigate {to}"));
        }
        fn quit(&self) {
            self.calls.borrow_mut().push("quit".into());
        }
    }

    fn chosen(id: &str) -> Vec<String> {
        let shell = FakeShell::default();
        handle_menu(id, &shell);
        shell.calls.into_inner()
    }

    #[test]
    fn close_hides_only_when_keep_running_is_on() {
        assert_eq!(close_action(true), CloseAction::Hide);
        assert_eq!(close_action(false), CloseAction::Close);
    }

    #[test]
    fn closing_quits_by_default() {
        let app = tauri::test::mock_app();
        app.manage(CloseBehaviour::default());
        assert_eq!(effective_close(false, true), CloseAction::Close);
    }

    #[test]
    fn closing_hides_when_chosen_and_the_tray_exists() {
        assert_eq!(effective_close(true, true), CloseAction::Hide);
    }

    #[test]
    fn with_no_tray_the_setting_is_ignored_and_closing_quits() {
        let app = tauri::test::mock_app();
        app.manage(CloseBehaviour::default());
        let can =
            tauri::async_runtime::block_on(window_close_behaviour(true, app.handle().clone()));
        assert!(!can, "the page is told it cannot keep running");
        assert!(
            app.state::<CloseBehaviour>().keep_running(),
            "the choice is remembered"
        );
        assert_eq!(effective_close(true, false), CloseAction::Close);
    }

    #[test]
    fn the_tray_is_shown_when_it_exists_unless_the_bus_says_nobody_shows_it() {
        assert!(tray_shown(true, WatcherProbe::Owned), "an owner: available");
        assert!(
            !tray_shown(true, WatcherProbe::NoOwner),
            "no owner: unavailable"
        );
        assert!(
            tray_shown(true, WatcherProbe::Failed),
            "a failed probe: available"
        );
        for probe in [
            WatcherProbe::Owned,
            WatcherProbe::NoOwner,
            WatcherProbe::Failed,
        ] {
            assert!(!tray_shown(false, probe), "no tray at all: unavailable");
        }
    }

    struct Bus(Result<bool, String>);
    impl NameOwners for Bus {
        fn name_has_owner(&self, name: &str) -> Result<bool, String> {
            assert_eq!(name, STATUS_NOTIFIER_WATCHER);
            self.0.clone()
        }
    }

    #[test]
    fn the_probe_maps_the_buss_answer() {
        assert_eq!(probe_watcher(&Bus(Ok(true))), WatcherProbe::Owned);
        assert_eq!(probe_watcher(&Bus(Ok(false))), WatcherProbe::NoOwner);
        assert_eq!(
            probe_watcher(&Bus(Err("timeout".into()))),
            WatcherProbe::Failed
        );
    }

    #[test]
    fn with_no_watcher_recorded_the_setting_is_ignored() {
        let host = TrayHost::default();
        assert_eq!(
            host.watcher(),
            WatcherProbe::Failed,
            "until a probe answers, available"
        );
        host.record(WatcherProbe::NoOwner);
        assert_eq!(
            effective_close(true, tray_shown(true, host.watcher())),
            CloseAction::Close
        );
        host.record(WatcherProbe::Owned);
        assert_eq!(
            effective_close(true, tray_shown(true, host.watcher())),
            CloseAction::Hide
        );
    }

    #[derive(Default)]
    struct FakeWindow {
        kept: std::cell::Cell<u32>,
    }
    impl CloseTarget for FakeWindow {
        fn keep_hidden(&self) {
            self.kept.set(self.kept.get() + 1);
        }
    }

    #[test]
    fn with_a_tray_and_the_choice_closing_hides_and_tells_once() {
        let (window, behaviour) = (FakeWindow::default(), CloseBehaviour::default());
        let told = std::cell::Cell::new(0);
        for _ in 0..2 {
            assert!(on_close_request(true, true, &behaviour, &window, || {
                told.set(told.get() + 1)
            }));
        }
        assert_eq!(window.kept.get(), 2, "every close is kept");
        assert_eq!(told.get(), 1, "the notice is posted the first time only");
    }

    #[test]
    fn without_the_choice_or_a_tray_closing_goes_through_silently() {
        for (chosen, tray) in [(false, true), (true, false), (false, false)] {
            let (window, behaviour) = (FakeWindow::default(), CloseBehaviour::default());
            let kept = on_close_request(chosen, tray, &behaviour, &window, || {
                panic!("nothing to tell")
            });
            assert!(!kept);
            assert_eq!(window.kept.get(), 0);
        }
    }

    #[test]
    fn a_second_launch_reveals_the_running_window() {
        let shell = FakeShell::default();
        reveal_running_app(&shell);
        assert_eq!(shell.calls.into_inner(), ["show"]);
    }

    #[test]
    fn open_needs_you_emits_navigate() {
        assert_eq!(chosen(OPEN_NEEDS_YOU), ["show", "navigate needsYou"]);
    }

    #[test]
    fn open_supermessage_shows_the_window_and_goes_nowhere() {
        assert_eq!(chosen(OPEN_APP), ["show"]);
    }

    #[test]
    fn quit_exits_and_the_lines_do_nothing() {
        assert_eq!(chosen(QUIT), ["quit"]);
        assert!(chosen(NEEDS_YOU_LINE).is_empty());
        assert!(chosen(PULSE_LINE).is_empty());
    }
}
