mod core;

use serde::Serialize;
use tauri::Manager;

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
            // `Session` construction only; the command surface that logs in
            // through it lands in a later M0 task.
            let data_dir = app.path().app_data_dir().expect("app data dir");
            app.manage(Session::new(data_dir, Box::new(KeyringStore)));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![core_status])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
