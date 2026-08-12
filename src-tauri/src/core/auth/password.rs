//! Password login: the only [`AuthProvider`] implementation today.
//!
//! The target homeserver (`id.agentpod.dev`, Synapse 1.152.0) advertises
//! only `m.login.password` — no SSO, no native OIDC. Native OIDC remains the
//! long-term intent but requires deploying matrix-authentication-service
//! first, so this is what actually logs users in for now.

use async_trait::async_trait;
use matrix_sdk::store::RoomLoadSettings;
use matrix_sdk::Client;

use super::AuthProvider;
use crate::core::error::{CoreError, CoreResult};
use crate::core::secrets::{SecretStore, KEY_SESSION, KEY_STORE_PASSPHRASE};

/// Logs in and restores sessions using `m.login.password`.
pub struct PasswordAuth;

#[async_trait]
impl AuthProvider for PasswordAuth {
    async fn login(&self, client: &Client, username: &str, password: &str) -> CoreResult<()> {
        client
            .matrix_auth()
            .login_username(username, password)
            .initial_device_display_name("supermessage")
            .await
            .map(|_| ())
            .map_err(map_login_error)
    }

    async fn restore(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<bool> {
        let Some(json) = store.get(KEY_SESSION)? else {
            return Ok(false);
        };

        if client.matrix_auth().session().is_some() {
            // Already authenticated. `restore_session` panics if auth data
            // was already set on this client (matrix-sdk treats it as a
            // programmer error to call it twice), and there is nothing left
            // to do: the session this store holds is already live.
            return Ok(true);
        }

        let session = serde_json::from_str(&json)
            .map_err(|e| CoreError::Store(format!("corrupt stored session: {e}")))?;

        client
            .matrix_auth()
            .restore_session(session, RoomLoadSettings::default())
            .await
            .map_err(|e| CoreError::Auth(e.to_string()))?;

        Ok(true)
    }

    async fn persist(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<()> {
        let session = client
            .matrix_auth()
            .session()
            .ok_or_else(|| CoreError::Auth("no active session to persist".into()))?;

        let json = serde_json::to_string(&session)
            .map_err(|e| CoreError::Store(format!("failed to serialize session: {e}")))?;

        store.set(KEY_SESSION, &json)
    }

    async fn logout(&self, client: &Client, store: &dyn SecretStore) -> CoreResult<()> {
        // Best-effort: an unreachable server must not stop the local session
        // from being cleared, or the user is stuck "logged in" with no way
        // to log back out.
        let _ = client.matrix_auth().logout().await;

        store.delete(KEY_SESSION)?;
        store.delete(KEY_STORE_PASSPHRASE)?;
        Ok(())
    }
}

/// Maps a login failure to the `CoreError` variant that tells the UI what
/// actually happened: a wrong password (HTTP 403 / `M_FORBIDDEN`, surfaced
/// as [`CoreError::Auth`]) versus the server being unreachable, timing out,
/// or returning something else entirely ([`CoreError::Network`]).
fn map_login_error(err: matrix_sdk::Error) -> CoreError {
    let is_wrong_password = err.as_client_api_error().is_some_and(|api_err| {
        api_err.status_code.as_u16() == 403
            || matches!(
                api_err.error_kind(),
                Some(ruma::api::error::ErrorKind::Forbidden)
            )
    });

    if is_wrong_password {
        CoreError::Auth(err.to_string())
    } else {
        CoreError::Network(err.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::secrets::MemoryStore;
    use crate::core::tls;
    use matrix_sdk::test_utils::logged_in_client_with_server;

    #[tokio::test]
    async fn persist_then_restore_round_trips_the_session() {
        tls::install_ring_provider();
        let (client, _server) = logged_in_client_with_server().await;
        let store = MemoryStore::default();
        let auth = PasswordAuth;

        auth.persist(&client, &store).await.unwrap();
        assert!(store
            .get(crate::core::secrets::KEY_SESSION)
            .unwrap()
            .is_some());

        // A fresh client with the same store restores without a network login.
        let restored = auth.restore(&client, &store).await.unwrap();
        assert!(restored);
    }

    #[tokio::test]
    async fn restore_reports_false_when_nothing_is_stored() {
        tls::install_ring_provider();
        let (client, _server) = logged_in_client_with_server().await;
        let store = MemoryStore::default();
        assert!(!PasswordAuth.restore(&client, &store).await.unwrap());
    }

    // The brief's two tests above are used verbatim. Everything below exists
    // because the task explicitly calls out the 403-vs-transport mapping and
    // the "logout still clears secrets locally" behavior as real defects, not
    // cosmetic ones, and neither is exercised by the brief's tests.

    #[tokio::test]
    async fn login_maps_wrong_password_to_auth_error() {
        use matrix_sdk::test_utils::no_retry_test_client_with_server;
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, ResponseTemplate};

        tls::install_ring_provider();
        let (client, server) = no_retry_test_client_with_server().await;

        Mock::given(method("POST"))
            .and(path("/_matrix/client/r0/login"))
            .respond_with(ResponseTemplate::new(403).set_body_json(serde_json::json!({
                "errcode": "M_FORBIDDEN",
                "error": "Invalid password",
            })))
            .mount(&server)
            .await;

        let err = PasswordAuth
            .login(&client, "alice", "wrong-password")
            .await
            .unwrap_err();

        assert!(
            matches!(err, CoreError::Auth(_)),
            "wrong password (403/M_FORBIDDEN) must map to CoreError::Auth, got {err:?}"
        );
    }

    #[tokio::test]
    async fn login_maps_transport_failure_to_network_error() {
        use matrix_sdk::test_utils::test_client_builder;

        tls::install_ring_provider();
        // Nothing listens here: a connection attempt fails at the transport
        // layer, before any HTTP response (let alone a Matrix errcode) exists.
        let client = test_client_builder(Some("http://127.0.0.1:1".to_string()))
            .build()
            .await
            .unwrap();

        let err = PasswordAuth
            .login(&client, "alice", "whatever")
            .await
            .unwrap_err();

        assert!(
            matches!(err, CoreError::Network(_)),
            "an unreachable server must map to CoreError::Network, got {err:?}"
        );
    }

    #[tokio::test]
    async fn logout_clears_secrets_even_when_the_server_call_fails() {
        tls::install_ring_provider();
        // No mock is registered for the logout endpoint, so the server call
        // 404s / fails — logout must still clear local state.
        let (client, _server) = logged_in_client_with_server().await;
        let store = MemoryStore::default();
        store.set(KEY_SESSION, "some-session-json").unwrap();
        store.set(KEY_STORE_PASSPHRASE, "some-passphrase").unwrap();

        PasswordAuth.logout(&client, &store).await.unwrap();

        assert!(store.get(KEY_SESSION).unwrap().is_none());
        assert!(store.get(KEY_STORE_PASSPHRASE).unwrap().is_none());
    }
}
