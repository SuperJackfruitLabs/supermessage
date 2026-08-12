//! Ownership seam for the logged-in Matrix account.
//!
//! One `matrix_sdk::Client` per account, owned here and never handed to the
//! webview. M0 fills this in: OIDC (MSC3861) primary with password login as
//! fallback, then `SyncService` driving Simplified Sliding Sync.

// The accessors below are the seam M0 login/sync will call into; nothing
// reaches them yet.
#![allow(dead_code)]

use matrix_sdk::Client;
use tokio::sync::RwLock;

/// Holds the active account's client, if any.
///
/// Registered as Tauri managed state so commands can reach it.
#[derive(Default)]
pub struct Session {
    client: RwLock<Option<Client>>,
}

impl Session {
    pub fn new() -> Self {
        Self::default()
    }

    /// Whether an account is currently logged in.
    pub async fn is_active(&self) -> bool {
        self.client.read().await.is_some()
    }

    /// Clones the active client handle. `Client` is internally reference
    /// counted, so this is cheap and callers must not store it long-term.
    pub async fn client(&self) -> Option<Client> {
        self.client.read().await.clone()
    }

    /// Installs the client for a freshly logged-in account, replacing any
    /// previous one.
    pub async fn set_client(&self, client: Client) {
        *self.client.write().await = Some(client);
    }

    /// Drops the active client on logout.
    pub async fn clear(&self) {
        *self.client.write().await = None;
    }
}
