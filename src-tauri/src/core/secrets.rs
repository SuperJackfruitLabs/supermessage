//! Where credentials live.
//!
//! Two things are stored here: the serialized Matrix session (access and
//! refresh tokens) and the passphrase for the SDK's SQLCipher-encrypted
//! SQLite stores. Both go behind the OS secret store via [`SecretStore`].
//!
//! The trait exists because the keyring cannot be exercised in unit tests
//! without writing to the developer's real secret store, so the contract is
//! tested against [`MemoryStore`] instead. [`KeyringStore`] is the real,
//! production-facing implementation.

#[cfg(test)]
use std::collections::HashMap;
#[cfg(test)]
use std::sync::Mutex;

use rand::rngs::OsRng;
use rand::TryRngCore;

use super::error::{CoreError, CoreResult};

/// Key under which the serialized Matrix session (access + refresh tokens)
/// is stored.
pub const KEY_SESSION: &str = "matrix_session";

/// Key under which the passphrase for the SDK's SQLCipher-encrypted SQLite
/// stores is stored.
pub const KEY_STORE_PASSPHRASE: &str = "store_passphrase";

/// Key under which the homeserver URL used at login is stored.
///
/// The persisted [`matrix_sdk::authentication::matrix::MatrixSession`] under
/// [`KEY_SESSION`] carries only auth tokens and device identity, never the
/// homeserver — `Client::builder().build()` fails with
/// `ClientBuildError::MissingHomeserver` without one. `Session::restore`
/// needs this to rebuild an identical client without asking the user again.
pub const KEY_HOMESERVER_URL: &str = "homeserver_url";

/// A place to put secrets. Implemented for real by [`KeyringStore`] (the OS
/// secret store) and for tests by [`MemoryStore`].
pub trait SecretStore: Send + Sync {
    fn get(&self, key: &str) -> CoreResult<Option<String>>;
    fn set(&self, key: &str, value: &str) -> CoreResult<()>;
    fn delete(&self, key: &str) -> CoreResult<()>;
}

/// The OS secret store (Secret Service on Linux, Keychain on macOS,
/// Credential Manager on Windows), reached through the `keyring` crate.
///
/// On Android there is no implementation yet — every method fails loudly
/// rather than silently falling back to writing plaintext to disk.
pub struct KeyringStore;

const SERVICE_NAME: &str = "dev.supermessage.app";

#[cfg(not(target_os = "android"))]
impl SecretStore for KeyringStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        let entry =
            keyring::Entry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        match entry.get_password() {
            Ok(password) => Ok(Some(password)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        let entry =
            keyring::Entry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        entry
            .set_password(value)
            .map_err(|e| CoreError::Store(e.to_string()))
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        let entry =
            keyring::Entry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        match entry.delete_credential() {
            Ok(()) => Ok(()),
            Err(keyring::Error::NoEntry) => Ok(()),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }
}

#[cfg(target_os = "android")]
impl SecretStore for KeyringStore {
    fn get(&self, _key: &str) -> CoreResult<Option<String>> {
        Err(android_unimplemented())
    }

    fn set(&self, _key: &str, _value: &str) -> CoreResult<()> {
        Err(android_unimplemented())
    }

    fn delete(&self, _key: &str) -> CoreResult<()> {
        Err(android_unimplemented())
    }
}

#[cfg(target_os = "android")]
fn android_unimplemented() -> CoreError {
    CoreError::Store("secret storage is not implemented on Android yet".into())
}

/// An in-memory secret store, for tests only. Never persists anything.
///
/// `#[cfg(test)]`, not just doc-comment convention: nothing in production
/// code should ever reach for this over the real `KeyringStore`, and gating
/// it keeps a non-test build from having to account for it as a dead-code
/// warning.
#[cfg(test)]
#[derive(Default)]
pub struct MemoryStore {
    entries: Mutex<HashMap<String, String>>,
}

#[cfg(test)]
impl SecretStore for MemoryStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        Ok(self.entries.lock().unwrap().get(key).cloned())
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        self.entries
            .lock()
            .unwrap()
            .insert(key.to_string(), value.to_string());
        Ok(())
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        self.entries.lock().unwrap().remove(key);
        Ok(())
    }
}

/// Generate a fresh 32-byte passphrase from OS randomness, hex encoded (64
/// characters).
pub fn generate_passphrase() -> String {
    let mut bytes = [0u8; 32];
    OsRng
        .try_fill_bytes(&mut bytes)
        .expect("OS randomness must be available");
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn contract(store: &dyn SecretStore) {
        assert_eq!(store.get("absent").unwrap(), None);
        store.set("k", "v").unwrap();
        assert_eq!(store.get("k").unwrap(), Some("v".to_string()));
        store.set("k", "v2").unwrap();
        assert_eq!(store.get("k").unwrap(), Some("v2".to_string()));
        store.delete("k").unwrap();
        assert_eq!(store.get("k").unwrap(), None);
    }

    #[test]
    fn memory_store_satisfies_the_contract() {
        contract(&MemoryStore::default());
    }

    #[test]
    fn deleting_an_absent_key_is_not_an_error() {
        MemoryStore::default().delete("never-existed").unwrap();
    }

    #[test]
    fn generated_passphrases_are_long_and_unique() {
        let a = generate_passphrase();
        let b = generate_passphrase();
        assert_eq!(a.len(), 64, "32 bytes hex encoded");
        assert_ne!(a, b);
    }

    #[test]
    #[ignore = "touches the developer's real OS keyring; run manually"]
    fn keyring_store_round_trips_on_this_machine() {
        let store = KeyringStore;
        store.set("smoke_test", "value").unwrap();
        assert_eq!(store.get("smoke_test").unwrap(), Some("value".to_string()));
        store.delete("smoke_test").unwrap();
    }
}
