//! A secret store the *host* implements.
//!
//! `supermessage-core` ships `KeyringStore`, which reaches the OS secret
//! store on macOS, Linux and Windows, and the Data Protection keychain on
//! iOS. On Android it has no implementation and every method returns an
//! error — deliberately, rather than falling back to plaintext on disk. This
//! is how an Android host supplies its own instead.
//!
//! Two traits rather than exporting the core's: the core's `SecretStore`
//! speaks `&str` and `CoreResult`, UniFFI needs owned `String` and a
//! `uniffi::Error`. `ForeignStore` is where those vocabularies meet.

use supermessage_core::error::{CoreError, CoreResult};
use supermessage_core::secrets::SecretStore;

use crate::error::FfiError;

/// A place to put secrets, implemented by the host.
///
/// Synchronous, because the core calls it inline — see `SecretStore` in the
/// core, whose signature this must satisfy through [`ForeignStore`]. A host
/// backing this with an async store must bridge on its own side.
#[uniffi::export(callback_interface)]
pub trait HostSecretStore: Send + Sync {
    /// The value for `key`, or `None` when nothing is stored under it.
    ///
    /// `None` is not an error and must not be reported as one: `restore()`
    /// reads a missing key as "first run" and sends the user to sign-in,
    /// which is the correct outcome when a stored value is unrecoverable.
    fn get(&self, key: String) -> Result<Option<String>, FfiError>;
    fn set(&self, key: String, value: String) -> Result<(), FfiError>;
    fn delete(&self, key: String) -> Result<(), FfiError>;
}

/// Wraps a host store so the core can use it as its own.
pub(crate) struct ForeignStore(pub(crate) Box<dyn HostSecretStore>);

impl SecretStore for ForeignStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        self.0.get(key.to_string()).map_err(into_core_error)
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        self.0
            .set(key.to_string(), value.to_string())
            .map_err(into_core_error)
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        self.0.delete(key.to_string()).map_err(into_core_error)
    }
}

/// Preserves the *variant*, not just the text.
///
/// `Session` branches on these. `Store` in particular is what a locked device
/// looks like — a state to wait out rather than a failure to report — and
/// collapsing everything into one variant would erase that on Android before
/// the behaviour that depends on it is ever built.
fn into_core_error(e: FfiError) -> CoreError {
    match e {
        FfiError::Auth { detail } => CoreError::Auth(detail),
        FfiError::Network { detail } => CoreError::Network(detail),
        FfiError::Store { detail } => CoreError::Store(detail),
        FfiError::Protocol { detail } => CoreError::Protocol(detail),
        other => CoreError::Store(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Default)]
    struct FakeHost {
        entries: Mutex<std::collections::HashMap<String, String>>,
        fail_with: Mutex<Option<FfiError>>,
    }

    impl HostSecretStore for FakeHost {
        fn get(&self, key: String) -> Result<Option<String>, FfiError> {
            if let Some(e) = self.fail_with.lock().unwrap().take() {
                return Err(e);
            }
            Ok(self.entries.lock().unwrap().get(&key).cloned())
        }
        fn set(&self, key: String, value: String) -> Result<(), FfiError> {
            if let Some(e) = self.fail_with.lock().unwrap().take() {
                return Err(e);
            }
            self.entries.lock().unwrap().insert(key, value);
            Ok(())
        }
        fn delete(&self, key: String) -> Result<(), FfiError> {
            if let Some(e) = self.fail_with.lock().unwrap().take() {
                return Err(e);
            }
            self.entries.lock().unwrap().remove(&key);
            Ok(())
        }
    }

    /// The adapter really delegates: what the host stored is what the core reads.
    #[test]
    fn a_value_set_through_the_adapter_reads_back() {
        let store = ForeignStore(Box::new(FakeHost::default()));
        store.set("passphrase", "hunter2").unwrap();
        assert_eq!(
            store.get("passphrase").unwrap(),
            Some("hunter2".to_string())
        );
        store.delete("passphrase").unwrap();
        assert_eq!(store.get("passphrase").unwrap(), None);
    }

    /// An absent key is `None`, not an error — `restore()` depends on this to
    /// find its first-run path rather than reporting a failure.
    #[test]
    fn an_absent_key_is_none_rather_than_an_error() {
        let store = ForeignStore(Box::new(FakeHost::default()));
        assert_eq!(store.get("never-written").unwrap(), None);
    }

    /// Every variant survives the crossing AS ITSELF. `Session` branches on
    /// these, and iOS's locked-keychain handling is exactly a `Store` that
    /// means "wait" rather than "fail" — flattening to a string erases that.
    #[test]
    fn each_error_variant_keeps_its_identity() {
        for (ffi, expect_variant) in [
            (
                FfiError::Store {
                    detail: "locked".into(),
                },
                "store",
            ),
            (
                FfiError::Auth {
                    detail: "refused".into(),
                },
                "auth",
            ),
            (
                FfiError::Network {
                    detail: "offline".into(),
                },
                "network",
            ),
            (
                FfiError::Protocol {
                    detail: "garbled".into(),
                },
                "protocol",
            ),
        ] {
            let host = FakeHost::default();
            *host.fail_with.lock().unwrap() = Some(ffi);
            let store = ForeignStore(Box::new(host));
            let got = store.get("k").unwrap_err();
            let actual = match got {
                CoreError::Store(_) => "store",
                CoreError::Auth(_) => "auth",
                CoreError::Network(_) => "network",
                CoreError::Protocol(_) => "protocol",
                other => panic!("unexpected variant: {other:?}"),
            };
            assert_eq!(actual, expect_variant);
        }
    }

    /// The detail text is carried through, not replaced with a generic string.
    #[test]
    fn the_detail_text_survives() {
        let host = FakeHost::default();
        *host.fail_with.lock().unwrap() = Some(FfiError::Store {
            detail: "keystore key invalidated".into(),
        });
        let store = ForeignStore(Box::new(host));
        assert!(store
            .get("k")
            .unwrap_err()
            .to_string()
            .contains("keystore key invalidated"));
    }

    /// A variant with no core twin still becomes a Store error rather than panicking.
    #[test]
    fn an_unmapped_variant_degrades_to_store() {
        let host = FakeHost::default();
        *host.fail_with.lock().unwrap() = Some(FfiError::NotReady);
        let store = ForeignStore(Box::new(host));
        assert!(matches!(store.get("k").unwrap_err(), CoreError::Store(_)));
    }
}
