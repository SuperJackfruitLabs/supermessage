//! The seam between "a user is logged in" and how they proved it.
//!
//! `AuthProvider` exists so a second implementation is additive rather than a
//! rewrite of session restore, token refresh, and the login UI. Only
//! [`password::PasswordAuth`] exists today.
//!
//! **It is not waiting on matrix-authentication-service.** This docstring used
//! to say a second provider arrived "once `id.agentpod.dev` deploys
//! matrix-authentication-service". MAS is a Synapse-family component and the
//! homeserver is tuwunel — swapped 2026-08-16, because Synapse is AGPLv3 and
//! this suite requires Apache/MIT. MAS is not coming
//! (`charter → decisions/2026-08-30-matrix-identity-without-mas.md`).
//!
//! What the seam is actually for is still open: the server advertises
//! `m.login.token` with `get_login_token: true`, so the suite's own issuer may
//! be able to mint a Matrix login and close the last identity silo. Unverified,
//! and a probe in the spike that gates the Organization plane. Until then
//! password login is the login path, not debt.

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
