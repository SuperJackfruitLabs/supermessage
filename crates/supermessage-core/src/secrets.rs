//! Where credentials live.
//!
//! Two things are stored here: the serialized Matrix session (access and
//! refresh tokens) and the passphrase for the SDK's encrypted SQLite
//! stores. Both go behind the OS secret store via [`SecretStore`].
//!
//! "Encrypted" is matrix-sdk-sqlite 0.18's own scheme, not SQLCipher: the
//! SQLite files themselves are plain, and their keys and values are
//! individually AEAD-encrypted with
//! `matrix_sdk_store_encryption::StoreCipher` derived from the passphrase
//! below. Losing the passphrase therefore loses the store's contents, which
//! is why `Session::logout` wipes the store directory in the same step that
//! deletes the passphrase.
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

// iOS talks to `keyring_core` directly; every other platform goes through
// `keyring`'s v1 shim.
//
// The shim is not merely unhelpful on iOS, it is a wall: `Entry::new` opens
// with `if SET_CREDENTIAL_STORE_RESULT.is_err() { return Err(NoDefaultStore) }`
// (`keyring-4.1.6/src/v1.rs`), and that one-time initializer refuses iOS
// outright — "must be macOS, Windows, or a non-iOS, non-Android *nix variant".
// So on iOS it fails *before* consulting any store, and registering the
// Data Protection store below is necessary but not sufficient. Going straight
// to `keyring_core` uses the store we registered; the two `Entry` types carry
// the same methods, so the code below is identical either way.
// Android's stub impl below uses none of this, and CI builds every target
// with `-D warnings`. The existing `not(ios)` gate is true on Android, so it
// was not enough on its own.
#[cfg(all(not(target_os = "ios"), not(target_os = "android")))]
use keyring::{Entry as KeyEntry, Error as KeyError};
#[cfg(target_os = "ios")]
use keyring_core::{Entry as KeyEntry, Error as KeyError};

/// Key under which the serialized Matrix session (access + refresh tokens)
/// is stored.
pub const KEY_SESSION: &str = "matrix_session";

/// Key under which the passphrase for the SDK's encrypted SQLite stores is
/// stored. See this module's doc comment for what that encryption is.
pub const KEY_STORE_PASSPHRASE: &str = "store_passphrase";

/// Key under which the homeserver URL used at login is stored.
///
/// The persisted [`matrix_sdk::authentication::matrix::MatrixSession`] under
/// [`KEY_SESSION`] carries only auth tokens and device identity, never the
/// homeserver — `Client::builder().build()` fails with
/// `ClientBuildError::MissingHomeserver` without one. `Session::restore`
/// needs this to rebuild an identical client without asking the user again.
pub const KEY_HOMESERVER_URL: &str = "homeserver_url";

/// Key under which the pusher this device registered is remembered —
/// `{"pushkey": …, "app_id": …}` — so signing out can remove it, even in a
/// later process than the one that registered it. Not a secret, but it lives
/// with the session it belongs to and is deleted with it.
pub const KEY_PUSHER: &str = "push_pusher";

/// Every key this module stores, for a migration that moves them all.
pub const ALL_KEYS: [&str; 4] = [
    KEY_SESSION,
    KEY_STORE_PASSPHRASE,
    KEY_HOMESERVER_URL,
    KEY_PUSHER,
];

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

#[cfg(not(target_os = "android"))]
const SERVICE_NAME: &str = "dev.supermessage.app";

/// Installs the credential store iOS needs, once.
///
/// Every other platform gets one for free: `keyring`'s `v1` shim picks the
/// Keychain, Credential Manager or Secret Service and registers it. That shim
/// explicitly excludes iOS and Android (`keyring-4.1.6/src/v1.rs`), returning
/// "must be macOS, Windows, or a non-iOS, non-Android *nix variant" — so on
/// iOS nothing is registered and the first credential call fails with "No
/// default store has been set, so cannot search or create entries". Which is
/// exactly what sign-in did on the simulator.
///
/// iOS keeps credentials in the Data Protection keychain rather than the
/// macOS one, so the store is `protected` rather than `keychain`. Items in it
/// can be unreadable while the device is locked, which is a state this app
/// should expect rather than treat as corruption.
#[cfg(target_os = "ios")]
static IOS_STORE: std::sync::LazyLock<Result<(), String>> = std::sync::LazyLock::new(|| {
    let store = apple_native_keyring_store::protected::Store::new().map_err(|e| e.to_string())?;
    keyring_core::set_default_store(store);
    Ok(())
});

/// Called before every credential access. A no-op everywhere but iOS.
#[cfg(target_os = "ios")]
fn ensure_store() -> CoreResult<()> {
    IOS_STORE
        .as_ref()
        .map(|_| ())
        .map_err(|e| CoreError::Store(e.clone()))
}

#[cfg(all(not(target_os = "ios"), not(target_os = "android")))]
fn ensure_store() -> CoreResult<()> {
    Ok(())
}

#[cfg(not(target_os = "android"))]
impl SecretStore for KeyringStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        ensure_store()?;
        let entry =
            KeyEntry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        match entry.get_password() {
            Ok(password) => Ok(Some(password)),
            Err(KeyError::NoEntry) => Ok(None),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        ensure_store()?;
        let entry =
            KeyEntry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        entry
            .set_password(value)
            .map_err(|e| CoreError::Store(e.to_string()))
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        ensure_store()?;
        let entry =
            KeyEntry::new(SERVICE_NAME, key).map_err(|e| CoreError::Store(e.to_string()))?;
        match entry.delete_credential() {
            Ok(()) => Ok(()),
            Err(KeyError::NoEntry) => Ok(()),
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

/// The keychain in one named access group, with items readable after the
/// device's first unlock — iOS only.
///
/// The Notification Service Extension is a second process with its own
/// default access group, so an item the app wrote there is one the extension
/// cannot read. Both are given `keychain-access-groups` naming the same group
/// and pass it here. **After first unlock**, not the default *when unlocked*:
/// a push arrives in a pocket, on a locked phone, and the extension must read
/// the session and the store passphrase then or show nothing. *This device
/// only*, so neither travels in a backup to another phone.
///
/// Also how the old items are read and removed: [`migrate_secrets`] is handed
/// one of these naming the app's own default group, so the delete cannot
/// reach the copy just written to the shared one — a query with no group
/// matches every group the app can see.
#[cfg(target_os = "ios")]
pub struct AccessGroupStore {
    store: std::sync::Arc<apple_native_keyring_store::protected::Store>,
}

#[cfg(target_os = "ios")]
impl AccessGroupStore {
    pub fn new(access_group: &str) -> CoreResult<Self> {
        let mut config = std::collections::HashMap::new();
        config.insert("access-group", access_group);
        let store = apple_native_keyring_store::protected::Store::new_with_configuration(&config)
            .map_err(|e| CoreError::Store(e.to_string()))?;
        Ok(Self { store })
    }

    fn entry(&self, key: &str) -> CoreResult<KeyEntry> {
        use keyring_core::api::CredentialStoreApi;
        let mut modifiers = std::collections::HashMap::new();
        modifiers.insert("access-policy", "after-first-unlock-this-device-only");
        self.store
            .build(SERVICE_NAME, key, Some(&modifiers))
            .map_err(|e| CoreError::Store(e.to_string()))
    }
}

#[cfg(target_os = "ios")]
impl SecretStore for AccessGroupStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        match self.entry(key)?.get_password() {
            Ok(password) => Ok(Some(password)),
            Err(KeyError::NoEntry) => Ok(None),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        self.entry(key)?
            .set_password(value)
            .map_err(|e| CoreError::Store(e.to_string()))
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        match self.entry(key)?.delete_credential() {
            Ok(()) | Err(KeyError::NoEntry) => Ok(()),
            Err(e) => Err(CoreError::Store(e.to_string())),
        }
    }
}

/// The OS secret store, in `access_group` when one is given.
///
/// The group is an iOS notion (see [`AccessGroupStore`]) and ignored
/// everywhere else, where [`KeyringStore`] is the only store there is.
pub fn os_store(access_group: Option<&str>) -> CoreResult<Box<dyn SecretStore>> {
    #[cfg(target_os = "ios")]
    if let Some(group) = access_group.filter(|g| !g.is_empty()) {
        return Ok(Box::new(AccessGroupStore::new(group)?));
    }
    #[cfg(not(target_os = "ios"))]
    let _ = access_group;
    Ok(Box::new(KeyringStore))
}

/// Move `keys` from `from` to `to`: read the old item, write the new one,
/// read it back, and only then delete the old.
///
/// **Idempotent, and safe to interrupt anywhere.** Each key is settled on its
/// own, so a process killed half way leaves some keys moved and the rest
/// where they were, and the next run finishes the job:
///
/// - only in `from` → copied, confirmed, removed from `from`;
/// - in `to` already → `to` wins and the stale `from` copy is removed (the
///   write landed last time and the delete did not);
/// - in neither → nothing.
///
/// Nothing is ever removed from `from` before the same value is readable from
/// `to`. A failure — a locked device, most likely, since items can be
/// unreadable before first unlock — stops the migration with that key still
/// in `from`, and is returned so the caller can try again next launch.
///
/// Returns how many keys were copied.
pub fn migrate_secrets(
    from: &dyn SecretStore,
    to: &dyn SecretStore,
    keys: &[&str],
) -> CoreResult<usize> {
    let mut copied = 0;
    for key in keys {
        if to.get(key)?.is_some() {
            from.delete(key)?;
            continue;
        }
        let Some(value) = from.get(key)? else {
            continue;
        };
        to.set(key, &value)?;
        if to.get(key)?.as_deref() != Some(value.as_str()) {
            return Err(CoreError::Store(format!(
                "{key} did not read back after moving it"
            )));
        }
        from.delete(key)?;
        copied += 1;
    }
    Ok(copied)
}

/// A store that reads through to where secrets used to live, moving each one
/// the first time it is asked for.
///
/// [`migrate_secrets`] at start-up moves everything when it can. It cannot
/// before the device's first unlock — the old items were written with the
/// default *when unlocked* protection — and a process iOS launches in the
/// background can be exactly that early. Without this, such a process would
/// find no session and treat the person as signed out; with it, the item is
/// moved by whichever later read first finds it readable.
///
/// Writes go only to the new store; deletes go to both, so a sign-out leaves
/// nothing in either.
pub struct MigratingStore {
    current: Box<dyn SecretStore>,
    legacy: Box<dyn SecretStore>,
}

impl MigratingStore {
    pub fn new(current: Box<dyn SecretStore>, legacy: Box<dyn SecretStore>) -> Self {
        let _ = migrate_secrets(legacy.as_ref(), current.as_ref(), &ALL_KEYS);
        Self { current, legacy }
    }
}

impl SecretStore for MigratingStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        if let Some(value) = self.current.get(key)? {
            return Ok(Some(value));
        }
        migrate_secrets(self.legacy.as_ref(), self.current.as_ref(), &[key])?;
        self.current.get(key)
    }

    fn set(&self, key: &str, value: &str) -> CoreResult<()> {
        self.current.set(key, value)
    }

    fn delete(&self, key: &str) -> CoreResult<()> {
        self.current.delete(key)?;
        self.legacy.delete(key)
    }
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

    fn filled(pairs: &[(&str, &str)]) -> MemoryStore {
        let store = MemoryStore::default();
        for (k, v) in pairs {
            store.set(k, v).unwrap();
        }
        store
    }

    #[test]
    fn a_migration_moves_every_key_and_leaves_nothing_behind() {
        let old = filled(&[
            (KEY_SESSION, "s"),
            (KEY_STORE_PASSPHRASE, "p"),
            (KEY_HOMESERVER_URL, "h"),
        ]);
        let new = MemoryStore::default();
        assert_eq!(migrate_secrets(&old, &new, &ALL_KEYS).unwrap(), 3);
        assert_eq!(new.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(new.get(KEY_STORE_PASSPHRASE).unwrap().as_deref(), Some("p"));
        assert_eq!(new.get(KEY_HOMESERVER_URL).unwrap().as_deref(), Some("h"));
        for key in ALL_KEYS {
            assert_eq!(old.get(key).unwrap(), None, "{key} left in the old store");
        }
    }

    #[test]
    fn a_second_migration_changes_nothing() {
        let old = filled(&[(KEY_SESSION, "s"), (KEY_STORE_PASSPHRASE, "p")]);
        let new = MemoryStore::default();
        migrate_secrets(&old, &new, &ALL_KEYS).unwrap();
        assert_eq!(migrate_secrets(&old, &new, &ALL_KEYS).unwrap(), 0);
        assert_eq!(new.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(new.get(KEY_STORE_PASSPHRASE).unwrap().as_deref(), Some("p"));
    }

    #[test]
    fn an_interrupted_migration_is_finished_and_the_new_copy_wins() {
        // Killed after writing the session to the new store and before
        // deleting it from the old; the passphrase never started.
        let old = filled(&[(KEY_SESSION, "stale"), (KEY_STORE_PASSPHRASE, "p")]);
        let new = filled(&[(KEY_SESSION, "s")]);
        assert_eq!(migrate_secrets(&old, &new, &ALL_KEYS).unwrap(), 1);
        assert_eq!(new.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(new.get(KEY_STORE_PASSPHRASE).unwrap().as_deref(), Some("p"));
        assert_eq!(old.get(KEY_SESSION).unwrap(), None);
        assert_eq!(old.get(KEY_STORE_PASSPHRASE).unwrap(), None);
    }

    /// A store whose writes fail — a locked device.
    struct Refusing;
    impl SecretStore for Refusing {
        fn get(&self, _key: &str) -> CoreResult<Option<String>> {
            Ok(None)
        }
        fn set(&self, _key: &str, _value: &str) -> CoreResult<()> {
            Err(CoreError::Store("locked".into()))
        }
        fn delete(&self, _key: &str) -> CoreResult<()> {
            Ok(())
        }
    }

    #[test]
    fn a_failed_write_leaves_the_old_item_where_it_was() {
        let old = filled(&[(KEY_SESSION, "s")]);
        assert!(migrate_secrets(&old, &Refusing, &ALL_KEYS).is_err());
        assert_eq!(old.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
    }

    /// A store that accepts a write and then reads back something else.
    #[derive(Default)]
    struct Forgetful(MemoryStore);
    impl SecretStore for Forgetful {
        fn get(&self, key: &str) -> CoreResult<Option<String>> {
            self.0.get(key)
        }
        fn set(&self, key: &str, _value: &str) -> CoreResult<()> {
            self.0.set(key, "garbled")
        }
        fn delete(&self, key: &str) -> CoreResult<()> {
            self.0.delete(key)
        }
    }

    #[test]
    fn nothing_is_deleted_until_the_new_copy_reads_back() {
        let old = filled(&[(KEY_SESSION, "s")]);
        assert!(migrate_secrets(&old, &Forgetful::default(), &ALL_KEYS).is_err());
        assert_eq!(old.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
    }

    /// A store shared between a test and the code under test.
    #[derive(Clone, Default)]
    struct Shared(std::sync::Arc<MemoryStore>);
    impl SecretStore for Shared {
        fn get(&self, key: &str) -> CoreResult<Option<String>> {
            self.0.get(key)
        }
        fn set(&self, key: &str, value: &str) -> CoreResult<()> {
            self.0.set(key, value)
        }
        fn delete(&self, key: &str) -> CoreResult<()> {
            self.0.delete(key)
        }
    }

    #[test]
    fn a_migrating_store_moves_everything_it_can_at_once() {
        let (current, legacy) = (Shared::default(), Shared::default());
        legacy.set(KEY_SESSION, "s").unwrap();
        let _store = MigratingStore::new(Box::new(current.clone()), Box::new(legacy.clone()));
        assert_eq!(current.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(legacy.get(KEY_SESSION).unwrap(), None);
    }

    /// Unreadable at start-up — before first unlock — and readable later.
    #[derive(Clone, Default)]
    struct Locked {
        inner: Shared,
        locked: std::sync::Arc<std::sync::atomic::AtomicBool>,
    }
    impl SecretStore for Locked {
        fn get(&self, key: &str) -> CoreResult<Option<String>> {
            if self.locked.load(std::sync::atomic::Ordering::SeqCst) {
                return Err(CoreError::Store("locked".into()));
            }
            self.inner.get(key)
        }
        fn set(&self, key: &str, value: &str) -> CoreResult<()> {
            self.inner.set(key, value)
        }
        fn delete(&self, key: &str) -> CoreResult<()> {
            self.inner.delete(key)
        }
    }

    #[test]
    fn an_item_unreadable_at_start_up_is_moved_when_first_read() {
        let current = Shared::default();
        let legacy = Locked::default();
        legacy.inner.set(KEY_SESSION, "s").unwrap();
        legacy
            .locked
            .store(true, std::sync::atomic::Ordering::SeqCst);

        let store = MigratingStore::new(Box::new(current.clone()), Box::new(legacy.clone()));
        assert_eq!(
            current.get(KEY_SESSION).unwrap(),
            None,
            "nothing moved while locked"
        );

        legacy
            .locked
            .store(false, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(store.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(current.get(KEY_SESSION).unwrap().as_deref(), Some("s"));
        assert_eq!(legacy.inner.get(KEY_SESSION).unwrap(), None);
    }

    #[test]
    fn a_migrating_store_writes_new_and_deletes_everywhere() {
        let (current, legacy) = (Shared::default(), Shared::default());
        let store = MigratingStore::new(Box::new(current.clone()), Box::new(legacy.clone()));
        store.set(KEY_PUSHER, "p").unwrap();
        assert_eq!(current.get(KEY_PUSHER).unwrap().as_deref(), Some("p"));
        assert_eq!(legacy.get(KEY_PUSHER).unwrap(), None);
        legacy.set(KEY_SESSION, "stray").unwrap();
        store.delete(KEY_SESSION).unwrap();
        assert_eq!(legacy.get(KEY_SESSION).unwrap(), None);
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
