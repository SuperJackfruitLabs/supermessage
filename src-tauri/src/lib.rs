// The core carries this same attribute, and both need it: `commands.rs` wraps
// `timeline_subscribe`, so the deep `Timeline::subscribe` stream type is laid
// out in this crate too. An attribute cannot cross a crate boundary.
#![recursion_limit = "256"]

// `pub`, not merely `mod`: `tests/timeline_projection.rs` is a genuine Cargo
// integration test (a separate crate that links this one as an ordinary
// dependency, not compiled with `--cfg test`), so it can only reach
// `core::timeline::project_item`/`core::tls::install_ring_provider`/the DTOs
// in `core::dto` if this module is actually public — `#[cfg(test)] pub mod
// core;` would not do it, since that cfg is false for the non-test rlib build
// integration tests link against. Nothing here is meant as a stable external
// API; this crate is never published, and the only consumers of the `rlib`
// crate-type are this binary and its own test targets.

use serde::Serialize;
use std::sync::Arc;
use tauri::{DragDropEvent, Manager, Url, WebviewWindowBuilder, WindowEvent};

mod attention;
mod commands;
mod commands_notify;
mod commands_session;
mod commands_voice;
mod host;
mod notify;
// Only Linux reports a press (see `notify::PluginPoster`), so elsewhere nothing calls in.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
mod notify_actions;
// The tray is desktop-only (Tauri has no tray on mobile), and the tokens are only its dot.
#[cfg(desktop)]
mod tokens;
#[cfg(desktop)]
mod tray;

use crate::commands::{
    acknowledgement, agent_avatar_style, agent_avatar_variants, attachment_discard,
    attachment_send, attachment_stage, collect_mentions, connection_state, create_room,
    delete_message, edit_message, elapsed_label, ensure_recovery, first_run_advance,
    first_run_answer, first_run_script, fold_turn, has_sent, invite_user, join_room,
    join_room_by_alias, leave_room, log_from_webview, login, logout, mark_room_read,
    mark_room_read_from_list, media_audio, media_download, media_fetch, member_avatar,
    next_notification_mode, parse_matrix_link, recover_with_key, recovery_state, reset_recovery,
    restore_session, rich_blocks_from_markdown, room_avatar, room_cast, room_info, room_status,
    room_status_word, rooms_resync, roster_agent_directory, roster_filter_chips,
    roster_filter_empty_state, roster_filter_from_key, roster_filter_key, roster_filter_sections,
    roster_hidden_invitations, roster_needs_you_inbox, roster_row, roster_sections, roster_state,
    search_messages, send_gate_decision, send_message, send_reply, set_hidden_events, set_typing,
    space_display_name, space_select, spaces_list, timeline_paginate_back, timeline_resync,
    timeline_subscribe, toggle_reaction, turn, turn_duration_label, turn_record_label,
    turn_status_word, typing_view,
};
use crate::commands_notify::{
    notification_clear_all, notification_delivers, notification_permission, notification_post,
    notifications_for_focused, notifications_for_roster, send_gate_decision_to,
};
use crate::commands_session::{
    account, blocked_users, direct_room_with, ignore_user, known_people, people_label,
    report_event, report_reason_remaining, report_room, report_user, room_inviter,
    room_mention_candidates, send_permission_answer, set_room_notification_mode, set_room_pinned,
    unignore_user,
};
use crate::commands_voice::{
    attachment_mark_voice, attachment_stage_recording, voice_long_enough, voice_recording_spec,
    voice_waveform,
};
use supermessage_core::secrets::KeyringStore;
use supermessage_core::{session::Session, tls};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct CoreStatus {
    platform: &'static str,
    crypto_provider: &'static str,
    sdk_ready: bool,
}

/// M0 smoke test for the webview <-> core bridge. Replaced by real room-list
/// state once sync lands.
#[tauri::command]
async fn core_status() -> CoreStatus {
    let status = CoreStatus {
        platform: std::env::consts::OS,
        crypto_provider: tls::active_provider(),
        sdk_ready: true,
    };
    tracing::debug!(
        platform = status.platform,
        crypto_provider = status.crypto_provider,
        "core_status requested by webview"
    );
    status
}

/// Whether a webview permission request may be granted: the microphone alone (audio, no
/// video), and only while the view shows the app's own content. Everything else is denied.
/// Asked by WebKitGTK only, so unused on macOS and Windows.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn allows_user_media(audio: bool, video: bool, uri: &Url) -> bool {
    audio && !video && is_app_origin(uri)
}

/// Turns on `getUserMedia` in WebKitGTK and answers its permission requests without a prompt:
/// the microphone for the app's own origin, nothing else. Without this WebKitGTK denies every
/// request, so the composer's mic would never record.
#[cfg(target_os = "linux")]
fn allow_microphone(window: &tauri::WebviewWindow) {
    use webkit2gtk::glib::prelude::Cast;
    use webkit2gtk::{
        PermissionRequestExt, SettingsExt, UserMediaPermissionRequest,
        UserMediaPermissionRequestExt, WebViewExt,
    };
    let result = window.with_webview(|webview| {
        let view = webview.inner();
        if let Some(settings) = WebViewExt::settings(&view) {
            settings.set_enable_media_stream(true);
        }
        view.connect_permission_request(|view, request| {
            let allowed = request
                .downcast_ref::<UserMediaPermissionRequest>()
                .is_some_and(|media| {
                    let uri = WebViewExt::uri(view).and_then(|u| Url::parse(&u).ok());
                    uri.is_some_and(|uri| {
                        allows_user_media(
                            media.is_for_audio_device(),
                            media.is_for_video_device(),
                            &uri,
                        )
                    })
                });
            if allowed {
                request.allow();
            } else {
                request.deny();
            }
            true
        });
    });
    if let Err(e) = result {
        tracing::warn!(error = %e, "couldn't set up the microphone permission");
    }
}

/// The Vite dev server's fixed port — duplicated from `tauri.conf.json`'s
/// `build.devUrl` and `vite.config.js`'s `server.port` since Rust has no way
/// to read the frontend's build config; kept in sync by hand like the other
/// two copies of this number already are.
const DEV_SERVER_PORT: u16 = 1420;

/// Whether `url` is the app's own webview content — the production
/// custom-protocol origin, or (only in a `tauri dev` build, `cfg!(dev)`) the
/// local Vite dev server on its fixed port. `run`'s `on_navigation` handler
/// refuses everything else, so a webview navigation that reaches it at
/// all — from *any* future code path, not just a click on a rendered
/// message link — can never take the whole window away from the app; there
/// is no browser chrome to get back with (see `Timeline.svelte`'s
/// `messageLinks.ts` for the first-layer guard this backs up).
///
/// This is Tauri's own documented pattern for restricting webview
/// navigation (`WebviewWindowBuilder::on_navigation`'s doc example checks
/// `url.scheme() == "tauri" || (cfg!(dev) && url.host_str() ==
/// Some("localhost"))`), generalised to also cover Windows' production
/// scheme (`https://tauri.localhost` / `http://tauri.localhost`, depending
/// on `use_https_scheme`), which uses `https`/`http` rather than a `tauri:`
/// custom scheme, and to pin the dev check to the actual configured port
/// rather than any `localhost` port.
fn is_app_origin(url: &Url) -> bool {
    let is_prod_origin = url.scheme() == "tauri"
        || (matches!(url.scheme(), "http" | "https") && url.host_str() == Some("tauri.localhost"));
    let is_dev_origin = cfg!(dev)
        && url.scheme() == "http"
        && url.host_str() == Some("localhost")
        && url.port() == Some(DEV_SERVER_PORT);
    is_prod_origin || is_dev_origin
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Before any TLS is constructed — see core::tls.
    tls::install_ring_provider();

    tracing_subscriber::fmt()
        // stderr, not stdout: conventional for diagnostics, and it is what
        // makes the logs reachable when the app is launched by a supervising
        // process. `tauri-driver` pipes the app's stderr into its own output
        // but not its stdout, so a `pnpm tauri build` binary driven over
        // WebDriver was silently logging into a void — which is exactly the
        // situation where the logs are needed most.
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "supermessage=debug,matrix_sdk=info,warn".into()),
        )
        .init();

    tracing::info!(
        version = env!("CARGO_PKG_VERSION"),
        crypto_provider = tls::active_provider(),
        "starting supermessage core"
    );

    let builder = tauri::Builder::default();

    // The embedded WebDriver server, so this app's UI can be driven and its
    // DOM read without a human at the keyboard — the only way to test what a
    // row actually renders as, which is the whole symptom of the timeline bug
    // this was added for. `tauri-driver` cannot do it: macOS has no WKWebView
    // driver at all.
    //
    // Debug builds only, and not merely by convention: this is a remote-control
    // surface on the running app, and a shipped binary must not carry one.
    // `desktop` as well as the feature: the dependency is scoped to desktop
    // targets (see Cargo.toml), so on mobile the feature can be on while the
    // crate is absent, and this line would not compile.
    #[cfg(all(desktop, debug_assertions, feature = "wdio"))]
    let builder = builder.plugin(tauri_plugin_wdio_webdriver::init());

    // The MCP bridge — same reasoning as the line above, one notch stricter.
    // It is a remote-control surface *and* an arbitrary-JS surface, so it is
    // not in `default`: a debug build does not carry it unless the developer
    // asked for it with `--features mcp`.
    #[cfg(all(debug_assertions, feature = "mcp"))]
    let builder = builder.plugin(tauri_plugin_mcp_bridge::init());

    builder
        .on_page_load(|webview, payload| {
            tracing::debug!(
                label = webview.label(),
                url = %payload.url(),
                event = ?payload.event(),
                "webview page load"
            );
        })
        .plugin(tauri_plugin_opener::init())
        // Registered for its **Rust** API only (`core::attachments` calls
        // `app.dialog().file()`). No `dialog:*` permission appears in
        // `capabilities/default.json`, so the webview cannot invoke a single
        // one of this plugin's commands — which is the point: a picker the
        // webview opened would hand *the webview* a path, and the
        // attachments design (§3) exists to stop exactly that.
        .plugin(tauri_plugin_dialog::init())
        // Registered for its **Rust** API only, like the dialog plugin above: it
        // posts on macOS and Windows and answers the permission question. No
        // `notification:*` permission is granted, so the webview cannot post;
        // it asks Rust (`notify::Poster`).
        .plugin(tauri_plugin_notification::init())
        // The Rust-side drag-drop handler the attachments design §3 requires.
        // Tauri also emits its own `tauri://drag-drop` (carrying raw paths)
        // to the webview and offers no way to suppress just that —
        // `disable_drag_drop_handler()` would turn the OS handler off
        // entirely and Rust would stop seeing drops too. So the guarantee
        // this buys is about *our* IPC surface: no command and no `sm://`
        // event ever carries a path, and the frontend listens for
        // `sm://attachment/staged` rather than for Tauri's built-in event.
        //
        // `WindowEvent`, not `WebviewEvent`: for a `WebviewWindow` — the only
        // kind this app builds — the drop arrives on the window, and this is
        // the handler Tauri's own documented example uses.
        .on_window_event(|window, event| {
            if let WindowEvent::DragDrop(DragDropEvent::Drop { paths, .. }) = event {
                host::on_files_dropped(window.app_handle(), paths.clone());
            }
        })
        .setup(|app| {
            // `tauri.conf.json`'s `main` window has `create: false` — that
            // suppresses Tauri's own automatic pre-`setup` creation
            // (`tauri-2.11.5/src/app.rs`'s internal `setup` function, which
            // otherwise runs `WebviewWindowBuilder::from_config(...).build()`
            // for every configured window before this closure ever runs)
            // specifically so `on_navigation` — a Rust closure, which JSON
            // config has no way to express — can be attached here, before
            // the window is actually built. See `is_app_origin`'s doc
            // comment for what this blocks and why it matters.
            let _window =
                WebviewWindowBuilder::from_config(app.handle(), &app.config().app.windows[0])?
                    .on_navigation(|url| {
                        let allowed = is_app_origin(url);
                        if !allowed {
                            tracing::warn!(
                                url = %url,
                                "blocked a webview navigation outside the app's own origin"
                            );
                        }
                        allowed
                    })
                    .build()?;
            #[cfg(target_os = "linux")]
            allow_microphone(&_window);
            // A recording left by the last run is single-use and can no longer be staged.
            commands_voice::sweep_recordings(app.handle());

            let data_dir = app.path().app_data_dir().expect("app data dir");
            let session = Session::new(data_dir, Box::new(KeyringStore));
            // The focused timeline is managed state in its own right (the
            // timeline commands take it directly), but it is *owned* by the
            // session, which has to tear it down on logout before wiping
            // the store off disk. Registering the session's own `Arc` keeps
            // both views of it pointing at one object — see
            // `Session::focused_timeline`.
            app.manage(session.focused_timeline());
            // Same reasoning as the focused timeline above, one step further:
            // the attachment commands and the drag-drop handler both reach
            // the staged-file map directly, and `logout` has to clear it, so
            // all three must see the *one* map the session owns.
            app.manage(session.staged_attachments());
            app.manage(session);
            // Where notifications go: the Desktop Notifications protocol over the session
            // bus on Linux, which can carry answer buttons, the plugin elsewhere. The receiver is what Linux button
            // presses arrive on; it waits to be taken.
            #[cfg(target_os = "linux")]
            {
                let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
                let poster: Arc<dyn notify::Poster> = Arc::new(notify::XdgPoster::new(
                    Arc::new(notify::SessionBusBackend::new()),
                    tx,
                ));
                app.manage(poster);
                app.manage(notify::ActionInbox::new(rx));
                spawn_action_consumer(app.handle().clone());
            }
            #[cfg(not(target_os = "linux"))]
            {
                let poster: Arc<dyn notify::Poster> =
                    Arc::new(notify::PluginPoster::new(app.handle().clone()));
                app.manage(poster);
            }
            // The tray: a menu with the Needs you line and the way back into the app. Absent,
            // with a log line, where the desktop cannot show one.
            #[cfg(desktop)]
            tray::install(app.handle());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            attention::set_attention,
            attachment_stage_recording,
            attachment_mark_voice,
            voice_recording_spec,
            voice_waveform,
            voice_long_enough,
            core_status,
            ignore_user,
            unignore_user,
            blocked_users,
            report_event,
            report_room,
            report_user,
            report_reason_remaining,
            set_room_notification_mode,
            set_room_pinned,
            known_people,
            room_mention_candidates,
            direct_room_with,
            room_inviter,
            account,
            people_label,
            send_permission_answer,
            notification_permission,
            notification_clear_all,
            notifications_for_roster,
            notifications_for_focused,
            notification_delivers,
            notification_post,
            send_gate_decision_to,
            connection_state,
            login,
            restore_session,
            logout,
            recovery_state,
            ensure_recovery,
            recover_with_key,
            reset_recovery,
            rooms_resync,
            spaces_list,
            space_select,
            space_display_name,
            first_run_script,
            first_run_advance,
            first_run_answer,
            timeline_subscribe,
            timeline_paginate_back,
            timeline_resync,
            rich_blocks_from_markdown,
            parse_matrix_link,
            collect_mentions,
            send_message,
            send_gate_decision,
            send_reply,
            toggle_reaction,
            edit_message,
            delete_message,
            roster_sections,
            roster_state,
            roster_hidden_invitations,
            roster_filter_sections,
            roster_filter_chips,
            roster_filter_from_key,
            roster_filter_key,
            roster_filter_empty_state,
            roster_needs_you_inbox,
            roster_agent_directory,
            roster_row,
            room_status,
            room_status_word,
            next_notification_mode,
            agent_avatar_style,
            agent_avatar_variants,
            set_typing,
            mark_room_read,
            mark_room_read_from_list,
            join_room,
            join_room_by_alias,
            create_room,
            invite_user,
            leave_room,
            room_avatar,
            media_fetch,
            media_audio,
            media_download,
            search_messages,
            log_from_webview,
            room_info,
            turn,
            turn_record_label,
            turn_duration_label,
            turn_status_word,
            elapsed_label,
            fold_turn,
            room_cast,
            typing_view,
            has_sent,
            set_hidden_events,
            acknowledgement,
            member_avatar,
            attachment_stage,
            attachment_send,
            attachment_discard,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            // Notifications belong to the running app; none should outlive it.
            if let tauri::RunEvent::Exit = event {
                if let Some(poster) = tauri::Manager::try_state::<Arc<dyn notify::Poster>>(app) {
                    poster.withdraw_all();
                }
            }
        });
}

/// Takes the buttons the reader presses and answers each in its own task, so one slow send
/// does not hold the next press.
#[cfg(target_os = "linux")]
fn spawn_action_consumer(app: tauri::AppHandle) {
    let Some(mut inbox) = app.state::<notify::ActionInbox>().take() else {
        return;
    };
    tauri::async_runtime::spawn(async move {
        while let Some(taken) = inbox.recv().await {
            let app = app.clone();
            tauri::async_runtime::spawn(async move {
                let poster = app.state::<Arc<dyn notify::Poster>>().inner().clone();
                notify_actions::handle(
                    taken,
                    &*app.state::<Session>(),
                    &notify_actions::WindowOpener(app.clone()),
                    poster,
                )
                .await;
            });
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_permission_handler_allows_only_audio_for_the_app_origin() {
        let app = Url::parse("tauri://localhost/index.html").unwrap();
        let elsewhere = Url::parse("https://example.org/").unwrap();
        assert!(allows_user_media(true, false, &app));
        assert!(
            !allows_user_media(true, true, &app),
            "a camera is never allowed"
        );
        assert!(!allows_user_media(false, true, &app));
        assert!(!allows_user_media(false, false, &app));
        assert!(
            !allows_user_media(true, false, &elsewhere),
            "only the app's own content"
        );
    }

    #[test]
    fn is_app_origin_allows_the_tauri_custom_protocol_origin() {
        assert!(is_app_origin(
            &Url::parse("tauri://localhost/index.html").unwrap()
        ));
    }

    #[test]
    fn is_app_origin_allows_windows_production_origins() {
        assert!(is_app_origin(
            &Url::parse("https://tauri.localhost/index.html").unwrap()
        ));
        assert!(is_app_origin(
            &Url::parse("http://tauri.localhost/index.html").unwrap()
        ));
    }

    // `cfg!(dev)` is `true` for a plain `cargo test`/`cargo build` (it's
    // false only when the `tauri` crate's `custom-protocol` feature is
    // enabled, which is what `tauri build`/`tauri build --debug` turn on
    // under the hood — see `tauri-2.11.5/build.rs`), so this environment
    // can exercise the dev-origin branch directly rather than only the
    // always-on production one above.
    #[test]
    fn is_app_origin_allows_the_dev_server_on_its_configured_port() {
        assert!(is_app_origin(
            &Url::parse(&format!("http://localhost:{DEV_SERVER_PORT}/")).unwrap()
        ));
    }

    #[test]
    fn is_app_origin_refuses_the_dev_server_on_a_different_port() {
        assert!(!is_app_origin(
            &Url::parse("http://localhost:9999/").unwrap()
        ));
    }

    #[test]
    fn is_app_origin_refuses_an_external_https_origin() {
        assert!(!is_app_origin(
            &Url::parse("https://evil.example/").unwrap()
        ));
    }

    #[test]
    fn is_app_origin_refuses_a_javascript_scheme_url() {
        assert!(!is_app_origin(&Url::parse("javascript:alert(1)").unwrap()));
    }

    #[test]
    fn is_app_origin_refuses_a_lookalike_host_on_the_tauri_localhost_scheme() {
        // `https://tauri.localhost.evil.example/` has `tauri.localhost` as a
        // *label prefix* of the actual host, not the host itself — `Url`'s
        // `host_str()` returns the full registrable host
        // (`tauri.localhost.evil.example`), so this must not match.
        assert!(!is_app_origin(
            &Url::parse("https://tauri.localhost.evil.example/").unwrap()
        ));
    }
}
