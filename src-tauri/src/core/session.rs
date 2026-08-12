//! Ownership seam for the logged-in Matrix account.
//!
//! One `matrix_sdk::Client` per account, owned here and never handed to the
//! webview. `Session` builds the client with an encrypted SQLCipher store,
//! drives login/restore/logout through the [`AuthProvider`] trait, and hands
//! out cheap clones of the client handle to the rest of the core.

// `Session`'s methods are exercised by this module's own tests, but nothing
// outside them calls in yet — `lib.rs` only constructs a `Session`, it
// doesn't drive login/restore/logout, since the command surface that would
// is a later M0 task. Revisit removing this once it does.
#![allow(dead_code)]

use std::path::PathBuf;

use matrix_sdk::Client;
use tokio::sync::RwLock;

use super::auth::password::PasswordAuth;
use super::auth::AuthProvider;
use super::error::{CoreError, CoreResult};
use super::secrets::{generate_passphrase, SecretStore, KEY_HOMESERVER_URL, KEY_STORE_PASSPHRASE};
use super::tls;

/// Holds the active account's client, if any.
///
/// Registered as Tauri managed state so commands can reach it.
pub struct Session {
    data_dir: PathBuf,
    store: Box<dyn SecretStore>,
    auth: PasswordAuth,
    client: RwLock<Option<Client>>,
}

impl Session {
    pub fn new(data_dir: PathBuf, store: Box<dyn SecretStore>) -> Self {
        Self {
            data_dir,
            store,
            auth: PasswordAuth,
            client: RwLock::new(None),
        }
    }

    /// Logs in fresh with a username and password, building a new client
    /// backed by an encrypted store and persisting the resulting session.
    pub async fn login(&self, homeserver: &str, username: &str, password: &str) -> CoreResult<()> {
        let client = self.build_client(homeserver).await?;
        self.auth.login(&client, username, password).await?;
        self.auth.persist(&client, self.store.as_ref()).await?;
        // The persisted session carries only auth tokens and device
        // identity, never the homeserver — a later `restore` needs this to
        // rebuild an identical client without asking the user again.
        self.store.set(KEY_HOMESERVER_URL, homeserver)?;
        *self.client.write().await = Some(client);
        Ok(())
    }

    /// Attempts to restore a previously persisted session, rebuilding the
    /// client against the same homeserver and encrypted store used at login.
    ///
    /// Returns `Ok(false)` when there is nothing to restore — the normal
    /// first-run path, not an error.
    pub async fn restore(&self) -> CoreResult<bool> {
        let Some(homeserver) = self.store.get(KEY_HOMESERVER_URL)? else {
            return Ok(false);
        };

        let client = self.build_client(&homeserver).await?;

        if !self.auth.restore(&client, self.store.as_ref()).await? {
            return Ok(false);
        }

        *self.client.write().await = Some(client);
        Ok(true)
    }

    /// Logs out and drops the active client, if any. Clears local state even
    /// if the server-side call fails, so the user is never stuck "logged in"
    /// with no way back out.
    ///
    /// Also wipes the encrypted store directory (per the M0 plan: `logout`
    /// clears "session, secrets and stores"). `PasswordAuth::logout` deletes
    /// the store passphrase, so leaving the old SQLCipher-encrypted store on
    /// disk would make it unopenable by any later `login` (which generates a
    /// fresh passphrase) — and leaving message history, room state and
    /// crypto keys decryptable-if-you-can-reach-the-keyring on disk after
    /// logout is not acceptable for a chat client regardless.
    pub async fn logout(&self) -> CoreResult<()> {
        // Clone the handle (cheap — `Client` is internally reference
        // counted) and drop the read lock before the network call below, so
        // a concurrent `client()`/`require_client()` is never blocked on it.
        let active = self.client().await;
        if let Some(active) = &active {
            self.auth.logout(active, self.store.as_ref()).await?;
        }
        self.store.delete(KEY_HOMESERVER_URL)?;
        *self.client.write().await = None;
        // Drop our own strong reference before touching the store directory
        // on disk, so nothing here still has the SQLite files open.
        drop(active);
        self.remove_store()?;
        Ok(())
    }

    /// Clones the active client handle. `Client` is internally reference
    /// counted, so this is cheap and callers must not store it long-term.
    pub async fn client(&self) -> Option<Client> {
        self.client.read().await.clone()
    }

    /// Like [`Self::client`], but fails with [`CoreError::NotReady`] when
    /// logged out, so the UI can distinguish "not logged in yet" from a real
    /// failure.
    pub async fn require_client(&self) -> CoreResult<Client> {
        self.client().await.ok_or(CoreError::NotReady)
    }

    /// Builds a `Client` against `homeserver`, backed by the encrypted
    /// SQLCipher store at [`Self::store_path`]. Used identically by
    /// [`Self::login`] and [`Self::restore`] so the two can never diverge on
    /// where the store lives.
    async fn build_client(&self, homeserver: &str) -> CoreResult<Client> {
        // Before any TLS is constructed — see core::tls.
        tls::install_ring_provider();
        let passphrase = load_or_create_passphrase(self.store.as_ref())?;
        Client::builder()
            .homeserver_url(homeserver)
            .sqlite_store(self.store_path(), Some(&passphrase))
            .build()
            .await
            .map_err(|e| CoreError::Network(e.to_string()))
    }

    /// Where the encrypted SQLCipher store lives on disk.
    fn store_path(&self) -> PathBuf {
        self.data_dir.join("store")
    }

    /// Removes the encrypted store directory from disk. Tolerant of it not
    /// existing, so logging out when nothing was ever written stays a safe
    /// no-op.
    fn remove_store(&self) -> CoreResult<()> {
        match std::fs::remove_dir_all(self.store_path()) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }
}

/// Returns the passphrase for the SDK's encrypted stores, generating and
/// persisting one on first use.
///
/// Must return the *same* passphrase across calls: generating a fresh one on
/// each launch would orphan the existing encrypted store and silently lose
/// all local state.
fn load_or_create_passphrase(store: &dyn SecretStore) -> CoreResult<String> {
    if let Some(existing) = store.get(KEY_STORE_PASSPHRASE)? {
        return Ok(existing);
    }

    let passphrase = generate_passphrase();
    store.set(KEY_STORE_PASSPHRASE, &passphrase)?;
    Ok(passphrase)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::secrets::MemoryStore;

    #[tokio::test]
    async fn require_client_reports_not_ready_before_login() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test"),
            Box::new(MemoryStore::default()),
        );
        let err = session.require_client().await.unwrap_err();
        assert_eq!(err.kind(), "notReady");
    }

    #[tokio::test]
    async fn the_store_passphrase_is_generated_once_and_reused() {
        let store = MemoryStore::default();
        let first = super::load_or_create_passphrase(&store).unwrap();
        let second = super::load_or_create_passphrase(&store).unwrap();
        assert_eq!(
            first, second,
            "a new passphrase would orphan the existing encrypted store"
        );
    }

    #[tokio::test]
    async fn restore_reports_false_when_nothing_was_ever_logged_in() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test-never-logged-in"),
            Box::new(MemoryStore::default()),
        );
        assert!(!session.restore().await.unwrap());
    }

    #[tokio::test]
    async fn logout_without_a_prior_login_is_a_safe_no_op() {
        let session = Session::new(
            std::env::temp_dir().join("sm-test-logout-noop"),
            Box::new(MemoryStore::default()),
        );
        session.logout().await.unwrap();
        assert!(session.client().await.is_none());
    }

    #[tokio::test]
    async fn login_then_restore_reuse_the_same_encrypted_store() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        tls::install_ring_provider();
        let server = MockServer::start().await;

        // The client negotiates the API version before it can pick a login
        // path (r0 vs v3); without this mock the request never gets sent.
        Mock::given(method("GET"))
            .and(path("/_matrix/client/versions"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "versions": ["r0.6.0"],
            })))
            .mount(&server)
            .await;

        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "abc123",
                "device_id": "GHTYAJCE",
                "user_id": "@alice:localhost",
            })))
            .mount(&server)
            .await;

        let data_dir =
            std::env::temp_dir().join(format!("sm-session-test-{}", rand::random::<u64>()));
        let session = Session::new(data_dir.clone(), Box::new(MemoryStore::default()));

        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap();

        // A genuine app-relaunch would build a brand new `Session` reading
        // the same persisted secrets; `restore` always builds a brand new
        // `Client` regardless of instance, so calling it here on the same
        // `Session` exercises exactly the same code path.
        let restored = session.restore().await.unwrap();
        assert!(
            restored,
            "restore must succeed using the homeserver persisted at login"
        );
        assert_eq!(
            session
                .client()
                .await
                .unwrap()
                .user_id()
                .map(|id| id.to_string()),
            Some("@alice:localhost".to_string()),
        );

        // The load-bearing assertion: exactly one store directory exists.
        // If `login` and `restore` ever computed different store paths this
        // would find two, catching the regression the shared `build_client`
        // helper is meant to prevent.
        let entries: Vec<_> = std::fs::read_dir(&data_dir)
            .unwrap()
            .map(|e| e.unwrap().file_name())
            .collect();
        assert_eq!(
            entries,
            vec![std::ffi::OsString::from("store")],
            "login and restore must build the encrypted store at the same path"
        );

        // `logout`'s server-side call 404s against this mock (no endpoint
        // registered for it), which must not stop local state from clearing.
        session.logout().await.unwrap();
        assert!(
            session.client().await.is_none(),
            "logout must drop the active client even when the server call fails"
        );

        let _ = std::fs::remove_dir_all(&data_dir);
    }

    #[tokio::test]
    async fn login_after_logout_succeeds_at_the_same_data_dir() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        tls::install_ring_provider();
        let server = MockServer::start().await;

        Mock::given(method("GET"))
            .and(path("/_matrix/client/versions"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "versions": ["r0.6.0"],
            })))
            .mount(&server)
            .await;

        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "abc123",
                "device_id": "GHTYAJCE",
                "user_id": "@alice:localhost",
            })))
            .mount(&server)
            .await;

        let data_dir =
            std::env::temp_dir().join(format!("sm-session-relogin-test-{}", rand::random::<u64>()));
        let session = Session::new(data_dir.clone(), Box::new(MemoryStore::default()));

        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap();

        session.logout().await.unwrap();

        // `PasswordAuth::logout` deletes `KEY_STORE_PASSPHRASE`, so a second
        // `login` generates a *fresh* passphrase. If `logout` left the old
        // encrypted store directory in place, opening it with the new
        // passphrase fails with a SQLCipher `aead::Error` — `logout` must
        // wipe the store directory too, so the second login starts clean.
        session
            .login(&server.uri(), "alice", "hunter2")
            .await
            .unwrap_or_else(|e| panic!("second login after logout must succeed, got: {e:?}"));

        let _ = std::fs::remove_dir_all(&data_dir);
    }
}
