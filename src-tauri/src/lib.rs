// `core::timeline::FocusedTimeline::subscribe`'s spawned task nests
// `Timeline::subscribe`'s stream type (itself wrapping `TimelineWithDropHandle`
// around an `eyeball_im` subscriber stream) inside a `pin_mut!`'d `while let`
// loop inside an `async move` block passed to `tokio::spawn` — deep enough
// that computing its layout overflows rustc's default query recursion limit.
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
pub mod core;

use serde::Serialize;
use tauri::{Manager, Url, WebviewWindowBuilder};

use crate::core::commands::{
    login, logout, mark_room_read, media_fetch, member_avatar, restore_session, room_avatar,
    room_info, rooms_resync, send_message, send_reply, set_typing, timeline_paginate_back,
    timeline_resync, timeline_subscribe, toggle_reaction,
};
use crate::core::secrets::KeyringStore;
use crate::core::{session::Session, tls};

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

    tauri::Builder::default()
        .on_page_load(|webview, payload| {
            tracing::debug!(
                label = webview.label(),
                url = %payload.url(),
                event = ?payload.event(),
                "webview page load"
            );
        })
        .plugin(tauri_plugin_opener::init())
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
            app.manage(session);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            core_status,
            login,
            restore_session,
            logout,
            rooms_resync,
            timeline_subscribe,
            timeline_paginate_back,
            timeline_resync,
            send_message,
            send_reply,
            toggle_reaction,
            set_typing,
            mark_room_read,
            room_avatar,
            media_fetch,
            room_info,
            member_avatar,
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
