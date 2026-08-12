//! The seam between "a user is logged in" and how they proved it.
//!
//! `AuthProvider` exists so a second implementation (native OIDC, once
//! `id.agentpod.dev` deploys matrix-authentication-service) is additive
//! rather than a rewrite of session restore, token refresh, and the login UI.
//! Only [`password::PasswordAuth`] exists today: the target homeserver
//! (Synapse 1.152.0) advertises `m.login.password` only — no SSO, no native
//! OIDC (`/_matrix/client/v1/auth_metadata` and the MSC2965 unstable path
//! both 404).

// `AuthProvider`/`PasswordAuth` are called by `Session::login`/`restore`/
// `logout` (Task 6) now, but nothing outside `session`'s own tests calls
// those yet — the command surface that reaches them from `lib.rs` is a later
// M0 task. Revisit removing this once it does.
#![allow(dead_code)]

use async_trait::async_trait;
use matrix_sdk::Client;

use super::error::CoreResult;
use super::secrets::SecretStore;

pub mod password;

/// How a user proves who they are to a homeserver, and how that proof is
/// kept around between app launches.
#[async_trait]
pub trait AuthProvider: Send + Sync {
    /// Logs in fresh with credentials, establishing a new session on
    /// `client`.
    async fn login(&self, client: &Client, username: &str, password: &str) -> CoreResult<()>;

    /// Restores a previously persisted session onto `client`.
    ///
    /// Returns `Ok(false)` when nothing was stored — the normal first-run
    /// path, not an error.
    async fn restore(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<bool>;

    /// Persists `client`'s current session so a later [`Self::restore`] call
    /// can pick it up.
    async fn persist(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<()>;

    /// Logs out and clears any locally stored session.
    ///
    /// Clears local state even if the server-side call fails: the user still
    /// expects to be logged out locally when the network is down.
    async fn logout(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<()>;
}
