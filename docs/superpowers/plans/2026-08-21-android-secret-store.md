# Android Secret Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the host supply a secret store over the FFI, implement one on Android against the Keystore, and make Android sign-in work for the first time.

**Architecture:** A `#[uniffi::export(callback_interface)]` trait in `supermessage-ffi` plus an adapter onto the core's existing `SecretStore`, a second `Core` constructor that takes one, and a Kotlin implementation encrypting with an AndroidKeyStore AES/GCM key into `SharedPreferences`. `supermessage-core` does not change.

**Tech Stack:** Rust, UniFFI, Kotlin, AndroidKeyStore, `javax.crypto`.

**Spec:** `docs/superpowers/specs/2026-08-21-android-secret-store-design.md`

## Global Constraints

- **`supermessage-core` does not change.** `Session::new(data_dir, store)` already takes `Box<dyn SecretStore>`; the seam exists. `KeyringStore` keeps serving macOS, Linux, Windows and iOS untouched.
- **Do not change `Core::new`'s signature.** Add `Core::with_secret_store`. `apple/SupermessageKit/CoreClient.swift:44` must keep compiling — iOS is verified separately on another machine.
- **The host trait is synchronous.** The core calls `SecretStore::get` inline. No `suspend`, no `runBlocking`, no DataStore.
- **Error variants survive the crossing.** `FfiError::Store → CoreError::Store`, and likewise `Auth`, `Network`, `Protocol`. Flattening to a string erases a distinction `Session` branches on.
- **The `detail` field name is load-bearing.** `FfiError`'s string fields are `detail`, not `message`, because `message` collides with `kotlin.Exception`. Do not rename.
- **Rebuilding the bindings takes ~15 minutes** for four ABIs and is required whenever the FFI surface changes:
  ```bash
  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
  ./scripts/build-android-libs.sh
  ```
  Gradle never invokes cargo, so skipping this leaves the old bindings in place and surfaces as a link error, not a compile error.
- **A test that has never failed is not yet a regression test.** Mutate every test until it fails before keeping it.
- **The app parses nothing and decides nothing.** Unchanged from the roster work.

---

### Task 1: The FFI seam — foreign trait, adapter, constructor

**Files:**
- Create: `crates/supermessage-ffi/src/secrets.rs`
- Modify: `crates/supermessage-ffi/src/lib.rs` (module declaration, re-export, second constructor)
- Test: `crates/supermessage-ffi/src/secrets.rs` (`#[cfg(test)] mod tests`)

**Interfaces:**
- Consumes: `supermessage_core::secrets::SecretStore`, `supermessage_core::error::{CoreError, CoreResult}`, `crate::error::FfiError`.
- Produces: `pub trait HostSecretStore` (callback interface), `pub(crate) struct ForeignStore`, and `Core::with_secret_store(data_dir: String, store: Box<dyn HostSecretStore>) -> Arc<Core>`.

- [ ] **Step 1: Write the failing tests**

In `crates/supermessage-ffi/src/secrets.rs`:

```rust
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
        assert_eq!(store.get("passphrase").unwrap(), Some("hunter2".to_string()));
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
            (FfiError::Store { detail: "locked".into() }, "store"),
            (FfiError::Auth { detail: "refused".into() }, "auth"),
            (FfiError::Network { detail: "offline".into() }, "network"),
            (FfiError::Protocol { detail: "garbled".into() }, "protocol"),
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
        *host.fail_with.lock().unwrap() =
            Some(FfiError::Store { detail: "keystore key invalidated".into() });
        let store = ForeignStore(Box::new(host));
        assert!(store.get("k").unwrap_err().to_string().contains("keystore key invalidated"));
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
```

- [ ] **Step 2: Run, confirm failure**

Run: `cargo test -p supermessage-ffi secrets`
Expected: FAIL — `secrets` module does not exist.

- [ ] **Step 3: Write the trait and adapter**

At the top of `crates/supermessage-ffi/src/secrets.rs`:

```rust
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
        self.0.set(key.to_string(), value.to_string()).map_err(into_core_error)
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
```

- [ ] **Step 4: Declare the module and re-export**

In `crates/supermessage-ffi/src/lib.rs`, beside the existing `pub mod` lines (28-30):

```rust
pub mod secrets;
```

and beside the existing `pub use` lines (40-42):

```rust
pub use secrets::HostSecretStore;
```

- [ ] **Step 5: Run the tests**

Run: `cargo test -p supermessage-ffi secrets`
Expected: PASS, 5 tests.

- [ ] **Step 6: Add the second constructor**

In `crates/supermessage-ffi/src/lib.rs`, replace the body of `Core::new` so both constructors share one private builder. `new` keeps its exact signature.

```rust
    /// Build a core rooted at `data_dir`, using the OS secret store.
    ///
    /// `data_dir` is the host's to choose — an app-support directory on macOS,
    /// the app container on iOS. The core puts its stores under it and does
    /// not look outside it.
    #[uniffi::constructor]
    pub fn new(data_dir: String) -> Arc<Self> {
        Self::build(data_dir, Box::new(KeyringStore))
    }

    /// Build a core whose secrets live in a store the host supplies.
    ///
    /// For platforms where the core has no usable store of its own. Android is
    /// the only one today: `KeyringStore` has no implementation there and
    /// fails every call, so a host that used [`Core::new`] could never sign in.
    #[uniffi::constructor]
    pub fn with_secret_store(data_dir: String, store: Box<dyn HostSecretStore>) -> Arc<Self> {
        Self::build(data_dir, Box::new(crate::secrets::ForeignStore(store)))
    }

    fn build(
        data_dir: String,
        store: Box<dyn supermessage_core::secrets::SecretStore>,
    ) -> Arc<Self> {
        install_tracing();

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("a multi-thread runtime must be constructible");

        Arc::new(Self {
            session: Arc::new(Session::new(PathBuf::from(data_dir), store)),
            runtime,
        })
    }
```

- [ ] **Step 7: Run the whole Rust suite**

Run: `cargo test --workspace`
Expected: PASS. The baseline is 622 core + 31 + 5 + the 5 new tests. No existing test may go red — if one does, the constructor refactor changed behaviour and that is a finding.

- [ ] **Step 8: Mutate**

Three mutations, each run for real, failure output kept, then restored:

1. In `into_core_error`, map every variant to `CoreError::Store`. Confirm `each_error_variant_keeps_its_identity` fails.
2. In `ForeignStore::get`, return `Ok(None)` unconditionally. Confirm `a_value_set_through_the_adapter_reads_back` fails.
3. In `into_core_error`, replace `detail` with a fixed string. Confirm `the_detail_text_survives` fails.

- [ ] **Step 9: Regenerate the bindings**

```bash
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/29.0.14206865"
./scripts/build-android-libs.sh
```

Takes ~15 minutes. Then confirm the surface actually crossed:

```bash
grep -n "HostSecretStore\|withSecretStore" android/core/src/main/kotlin/uniffi/supermessage_ffi/supermessage_ffi.kt | head
```
Expected: an `interface HostSecretStore` and a `withSecretStore` companion constructor. If either is missing, the export did not take and the later tasks cannot compile.

- [ ] **Step 10: Confirm Kotlin still compiles**

Run: `cd android && ./gradlew :core:compileDebugKotlin :kit:testDebugUnitTest`
Expected: PASS — the regenerated bindings must not break `:kit`'s 198 tests.

- [ ] **Step 11: Commit**

```bash
git add crates/supermessage-ffi/ android/core/src/main/kotlin/
git commit -m "FFI: let a host supply the secret store"
```

---

### Task 2: `AndroidSecretStore`

**Files:**
- Create: `android/app/src/main/kotlin/dev/supermessage/AndroidSecretStore.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/AndroidSecretStoreTest.kt`

**Interfaces:**
- Consumes: `uniffi.supermessage_ffi.HostSecretStore`, `uniffi.supermessage_ffi.FfiException`.
- Produces: `class AndroidSecretStore(context: Context) : HostSecretStore`.

**These tests are instrumented, not JVM.** `AndroidKeyStore` exists only on a device. A JVM test with a `HashMap` fake would pass while proving nothing.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.supermessage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSecretStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: AndroidSecretStore

    @Before fun setUp() {
        store = AndroidSecretStore(context)
        store.clearForTest()
    }

    @After fun tearDown() = store.clearForTest()

    @Test fun aValueSetIsReadBack() {
        store.set("passphrase", "hunter2")
        assertEquals("hunter2", store.get("passphrase"))
    }

    @Test fun anAbsentKeyIsNull() {
        assertNull(store.get("never-written"))
    }

    @Test fun aDeletedKeyIsNull() {
        store.set("passphrase", "hunter2")
        store.delete("passphrase")
        assertNull(store.get("passphrase"))
    }

    /**
     * Distinct keys do not collide — one key's value never reads back under
     * another's. A single shared IV or a single shared preference entry would
     * pass the round-trip test above and fail this one.
     */
    @Test fun distinctKeysDoNotCollide() {
        store.set("a", "first")
        store.set("b", "second")
        assertEquals("first", store.get("a"))
        assertEquals("second", store.get("b"))
    }

    /**
     * The ciphertext is genuinely different each time even for the same
     * plaintext — GCM with a fresh IV per write. Equal ciphertexts would mean
     * a reused IV, which is the classic GCM break.
     */
    @Test fun theSameValueEncryptsDifferentlyEachTime() {
        store.set("a", "same")
        val first = store.rawForTest("a")
        store.set("a", "same")
        val second = store.rawForTest("a")
        org.junit.Assert.assertNotEquals(first, second)
        assertEquals("same", store.get("a"))
    }

    /** Non-ASCII survives Base64 and GCM unchanged. */
    @Test fun nonAsciiRoundTrips() {
        store.set("k", "passphrase — with an em dash, 日本語, and 🔐")
        assertEquals("passphrase — with an em dash, 日本語, and 🔐", store.get("k"))
    }

    /**
     * A value survives a new store instance over the same preferences — the
     * difference between a store and a cache, and the whole reason a session
     * outlives the app. The nearest thing to a process restart a test can do.
     */
    @Test fun aValueSurvivesANewInstance() {
        store.set("passphrase", "hunter2")
        assertEquals("hunter2", AndroidSecretStore(context).get("passphrase"))
    }
}
```

- [ ] **Step 2: Run, confirm failure**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest --tests '*AndroidSecretStoreTest*'`
Expected: FAIL to compile — `AndroidSecretStore` does not exist.

- [ ] **Step 3: Write `AndroidSecretStore`**

```kotlin
package dev.supermessage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.supermessage_ffi.FfiException
import uniffi.supermessage_ffi.HostSecretStore

/**
 * Android's secret store, for a core that has none of its own here.
 *
 * `supermessage-core`'s `KeyringStore` reaches the OS secret store on every
 * other platform; on Android it fails every call by design rather than
 * writing plaintext to disk. This supplies the real one.
 *
 * An AES-256/GCM key lives in `AndroidKeyStore` and never leaves secure
 * hardware; values are encrypted under it and the ciphertext kept in
 * `SharedPreferences`.
 *
 * **Synchronous on purpose.** The core calls `SecretStore::get` inline, so
 * [HostSecretStore] is synchronous, so this cannot use DataStore — which is
 * async-only and would need `runBlocking` on a tokio worker thread.
 * `RosterPreferences` uses DataStore because Compose wants a `Flow`; this
 * wants a blocking read. Opposite requirements, opposite tools.
 */
class AndroidSecretStore(context: Context) : HostSecretStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // The key is gone for good, so this ciphertext can never be read
            // again. Not an error to report: `null` means "nothing stored",
            // which sends restore() down its first-run path and lands the
            // user on sign-in — the only honest outcome, since the session
            // really is unrecoverable. Drop the unreadable value and the dead
            // key so later writes can succeed.
            forget(key)
            null
        } catch (e: java.security.GeneralSecurityException) {
            throw FfiException.Store("couldn't decrypt a stored secret: ${e.message}")
        }
    }

    override fun set(key: String, value: String) {
        try {
            prefs.edit().putString(key, encrypt(value)).commit()
        } catch (e: java.security.GeneralSecurityException) {
            throw FfiException.Store("couldn't encrypt a secret: ${e.message}")
        }
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        // No IV supplied: AndroidKeyStore requires randomised encryption for
        // GCM and rejects a caller-provided one. Reusing an IV under a single
        // key is the classic GCM break, so letting the platform generate it
        // is what makes this safe rather than merely idiomatic.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // Deliberately NOT setUserAuthenticationRequired(true): it
                    // makes secrets unreadable while the device is locked,
                    // which breaks background sync and restore-on-boot. A chat
                    // client that cannot receive messages in a pocket has
                    // traded its function for a property the app sandbox
                    // already largely provides.
                    .build()
            )
        }.generateKey()
    }

    private fun forget(key: String) {
        prefs.edit().remove(key).commit()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    /** Test seam: the stored ciphertext, to prove two writes differ. */
    internal fun rawForTest(key: String): String? = prefs.getString(key, null)

    /** Test seam: drop every stored value and the key itself. */
    internal fun clearForTest() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val PREFS = "secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "dev.supermessage.secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest --tests '*AndroidSecretStoreTest*'`
Expected: PASS, 7 tests, on both connected devices.

- [ ] **Step 5: Mutate**

Each run for real, failure output kept, then restored:

1. In `encrypt`, hoist the IV to a fixed `ByteArray(12)` and pass it via `GCMParameterSpec` — if AndroidKeyStore rejects that outright, instead cache one `Cipher.iv` across calls. Confirm `theSameValueEncryptsDifferentlyEachTime` fails.
2. In `set`, write the plaintext instead of the ciphertext. Confirm nothing fails — then **fix the test suite**, because that is a gap: add an assertion that `rawForTest(key)` does not contain the plaintext. Re-run and confirm it now fails.
3. In `get`, ignore the `key` argument and read a fixed preference name. Confirm `distinctKeysDoNotCollide` fails.

Mutation 2 is deliberately one the current tests miss. Finding a gap is the point of mutating, not a sign the step went wrong.

- [ ] **Step 6: Commit**

```bash
git add android/app/
git commit -m "Android: a secret store backed by the Keystore"
```

---

### Task 3: Wire it in, and sign in

**Files:**
- Modify: `android/app/src/main/kotlin/dev/supermessage/SessionViewModel.kt`
- Test: `android/app/src/androidTest/kotlin/dev/supermessage/` (existing suite must stay green)

**Interfaces:**
- Consumes: `AndroidSecretStore` (Task 2), `Core.withSecretStore` (Task 1).

- [ ] **Step 1: Change the construction**

`SessionViewModel` currently builds `Core(dataDir = app.filesDir.path)`. It becomes:

```kotlin
    val session: Session = build(
        Core.withSecretStore(
            dataDir = app.filesDir.path,
            store = AndroidSecretStore(app),
        ),
        viewModelScope,
    )
```

Keep the existing `build(core: CoreInterface, scope: CoroutineScope)` companion seam exactly as it is — it is what lets tests inject a fake `Core`, and nothing about it changes.

Check the generated Kotlin for the real constructor spelling before writing this: UniFFI renders a secondary Rust constructor as a companion function, and the exact name is whatever Step 9 of Task 1 found. Use what the bindings say, not what this plan guessed.

- [ ] **Step 2: Build and run the whole instrumented suite**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest`
Expected: PASS — all 24 existing tests plus Task 2's 7. `RootScaffoldTest`'s four geometry tests must still pass unmodified.

- [ ] **Step 3: Sign in on a device, for real**

```bash
cd android && ./gradlew :app:installDebug
adb shell am start -n dev.supermessage/.MainActivity
```

Sign in against a real homeserver with real credentials. This has never worked on Android.

Confirm, in order:
1. The sign-in succeeds and `phase` becomes `SIGNED_IN`.
2. The roster shows your actual rooms, in the right sections, with the right previews and times.
3. Tapping a room opens the detail pane.
4. Rotating keeps the roster and the pane count follows the width.
5. **Kill the app and reopen it.** It must reach `SIGNED_IN` without asking again — the proof that `set` persisted rather than merely returned.

Point 5 is the one that can fail while 1-4 pass, and it is the whole difference between a store and a cache.

**This closes A1's acceptance criterion**, which the roster plan could not reach. Report exactly what was observed, including anything that did not work.

- [ ] **Step 4: Commit**

```bash
git add android/app/
git commit -m "Android: build the core with a real secret store"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §2 foreign trait and adapter | 1 steps 1-5 |
| §2 error variants survive | 1 step 1 test 3, step 8 mutation 1 |
| §3 second constructor, iOS untouched | 1 step 6 |
| §3 shared body, no duplication | 1 step 6 |
| §4 `AndroidSecretStore` in `:app` | 2 |
| §4.1 Keystore AES/GCM, platform-generated IV | 2 step 3, step 5 mutation 1 |
| §4.2 invalidated key returns null | 2 step 3 (`get`'s catch) |
| §4.3 no user auth | 2 step 3 (KeyGenParameterSpec comment) |
| §4.3 SharedPreferences, `commit()` | 2 step 3 |
| §5 round trip, absent, delete, collision, non-ASCII, persistence | 2 step 1 |
| §5 device sign-in and restart | 3 step 3 |
| §6 sequence | Tasks 1-3 collapse the spec's four steps into three: the spec's steps 1 and 2 share one 15-minute binding rebuild, so splitting them would cost a second one for nothing. |

**Placeholder scan:** clean. Every step carries real code. The one deliberate unknown is Task 3 step 1's constructor spelling, which is resolved by reading the generated bindings in Task 1 step 9 rather than guessed here — the plan says so explicitly instead of inventing a name.

**Type consistency:** `HostSecretStore`'s three methods keep the same signatures across the Rust trait (Task 1), the Kotlin implementation (Task 2), and the call site (Task 3). `ForeignStore` is `pub(crate)` and used only inside `supermessage-ffi`. `FfiError`'s field is `detail` throughout. `AndroidSecretStore(context)` matches its construction from `app` in Task 3.

**Known gap, stated rather than hidden:** `KeyPermanentlyInvalidatedException` is handled in Task 2 step 3 but has no test — provoking it needs lock-screen changes an instrumented test cannot make. Task 2's mutation step will not catch a defect there. It is written to the spec's §4.2 rule and reviewed by reading, which is weaker than the standard applied everywhere else in this plan, and worth saying out loud rather than letting the coverage look uniform.
