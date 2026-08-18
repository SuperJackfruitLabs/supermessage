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
use tauri::{DragDropEvent, Manager, Url, WebviewWindowBuilder, WindowEvent};

use supermessage_core::attachments;
mod commands;
mod host;

use crate::commands::{
    attachment_discard, attachment_send, attachment_stage, connection_state, create_room,
    invite_user, join_room, join_room_by_alias, leave_room, log_from_webview, login, logout,
    mark_room_read, media_download, media_fetch, member_avatar, restore_session, room_avatar,
    room_info, rooms_resync, search_messages, send_message, send_reply, set_typing, space_select,
    spaces_list, timeline_paginate_back, timeline_resync, timeline_subscribe, toggle_reaction,
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
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            core_status,
            connection_state,
            login,
            restore_session,
            logout,
            rooms_resync,
            spaces_list,
            space_select,
            timeline_subscribe,
            timeline_paginate_back,
            timeline_resync,
            send_message,
            send_reply,
            toggle_reaction,
            set_typing,
            mark_room_read,
            join_room,
            join_room_by_alias,
            create_room,
            invite_user,
            leave_room,
            room_avatar,
            media_fetch,
            media_download,
            search_messages,
            log_from_webview,
            room_info,
            member_avatar,
            attachment_stage,
            attachment_send,
            attachment_discard,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod tests {
    use super::*;

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
