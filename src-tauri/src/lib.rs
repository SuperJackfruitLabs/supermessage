// `core::timeline::FocusedTimeline::subscribe`'s spawned task nests
// `Timeline::subscribe`'s stream type (itself wrapping `TimelineWithDropHandle`
// around an `eyeball_im` subscriber stream) inside a `pin_mut!`'d `while let`
// loop inside an `async move` block passed to `tokio::spawn` — deep enough
// that computing its layout overflows rustc's default query recursion limit.
#![recursion_limit = "256"]

mod core;

use serde::Serialize;
use tauri::Manager;

use crate::core::commands::{
    avatar_thumbnail, login, logout, restore_session, rooms_resync, send_message,
    timeline_paginate_back, timeline_resync, timeline_subscribe,
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Before any TLS is constructed — see core::tls.
    tls::install_ring_provider();

    tracing_subscriber::fmt()
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
            avatar_thumbnail,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
