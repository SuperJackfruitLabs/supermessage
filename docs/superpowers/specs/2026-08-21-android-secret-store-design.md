# A secret store for Android

**Status:** design, 21 Aug 2026. Written against `feat/android-roster` at `99ff322`.
**Audience:** whoever implements it, and whoever later asks why Android has a constructor iOS does not.
**Companions:** `docs/superpowers/specs/2026-08-21-android-roster-design.md` is the work this unblocks. `docs/superpowers/plans/2026-08-21-android-app-roadmap.md` places it: everything in Phase A and B is unreachable without it.

## 1. What is broken, precisely

Android sign-in cannot succeed. Not "is untested" — cannot succeed, by construction.

`crates/supermessage-core/src/secrets.rs:149-167`:

```rust
#[cfg(target_os = "android")]
impl SecretStore for KeyringStore {
    fn get(&self, _key: &str) -> CoreResult<Option<String>> { Err(android_unimplemented()) }
    fn set(&self, _key: &str, _value: &str) -> CoreResult<()> { Err(android_unimplemented()) }
    fn delete(&self, _key: &str) -> CoreResult<()> { Err(android_unimplemented()) }
}

#[cfg(target_os = "android")]
fn android_unimplemented() -> CoreError {
    CoreError::Store("secret storage is not implemented on Android yet".into())
}
```

`KeyringStore`'s own doc comment states the intent: *"On Android there is no implementation yet — every method fails loudly rather than silently falling back to writing plaintext to disk."* Failing loudly was the right call. But `crates/supermessage-ffi/src/lib.rs:96-99` hands that store to every platform unconditionally:

```rust
session: Arc::new(Session::new(PathBuf::from(data_dir), Box::new(KeyringStore))),
```

So `Session::login` (`session.rs:128`) → `build_client` → `load_or_create_passphrase` (`session.rs:1157`) → `store.get(KEY_STORE_PASSPHRASE)` → `Err`. **The failure happens before the homeserver is contacted.** `restore()` fails identically at `store.get(KEY_HOMESERVER_URL)`, so the app can never reach `SIGNED_IN` by any route.

What a user sees is `"Couldn't read this device's local store."` — `ErrorPresenter.kt:29`'s mapping of `FfiException.Store`. Accurate, and useless as a diagnosis, because the store was never going to work.

**How this was found.** A1's Task 6 hit it on a device and reported it as a quirk of a fresh AVD with "no prior successful sign-in to open against". Pre-existing and not that task's defect — both true — but the diagnosis was wrong, and the truth disqualifies A1's acceptance criterion rather than merely deferring it. The roster spec §5 called the device check "not automatable here… it needs a homeserver and an account." It needs neither. It needs this.

## 2. The seam

`supermessage-core` does not change. `Session::new(data_dir: PathBuf, store: Box<dyn SecretStore>)` (`session.rs:84`) already accepts an injected store — the seam exists and has always existed; nothing was ever passed through it from a host.

The work is a foreign trait in `supermessage-ffi`, and an adapter.

```rust
/// A secret store the host implements, for platforms where the core has no
/// usable one of its own. Android is the only such platform today.
#[uniffi::export(callback_interface)]
pub trait HostSecretStore: Send + Sync {
    fn get(&self, key: String) -> Result<Option<String>, FfiError>;
    fn set(&self, key: String, value: String) -> Result<(), FfiError>;
    fn delete(&self, key: String) -> Result<(), FfiError>;
}

struct ForeignStore(Box<dyn HostSecretStore>);

impl supermessage_core::secrets::SecretStore for ForeignStore {
    fn get(&self, key: &str) -> CoreResult<Option<String>> {
        self.0.get(key.to_string()).map_err(into_core_error)
    }
    // set, delete: the same shape
}
```

**Two traits, not one exported core trait.** The core's `SecretStore` takes `&str` and returns `CoreResult`; UniFFI needs owned `String` and a `uniffi::Error`. The adapter is where those meet, and it is the only place that knows both vocabularies.

**Error mapping is not decoration.** `into_core_error` must preserve the *variant*, not flatten to a string: `FfiError::Store{detail} → CoreError::Store(detail)`, and likewise for `Auth`, `Network`, `Protocol`, with anything else becoming `CoreError::Store`. `Session` branches on these variants, and iOS's locked-keychain handling is exactly a `Store` that means "wait", not "fail". Flattening would erase that distinction on Android before anyone builds the behaviour that depends on it.

This precedent already exists: `EventSink` is `#[uniffi::export(callback_interface)]` (`events.rs:84`) and Kotlin implements it as `class EventPump : EventSink`. Nothing here is a new mechanism for this codebase.

## 3. The constructor

Add one; do not change the existing one.

```rust
/// Build a core whose secrets live in a store the host supplies.
#[uniffi::constructor]
pub fn with_secret_store(data_dir: String, store: Box<dyn HostSecretStore>) -> Arc<Self>
```

`Core::new(data_dir)` keeps `KeyringStore` and keeps working for macOS, Linux, Windows and iOS. Android calls `with_secret_store`.

**Why not one constructor taking a store.** It is arguably cleaner, and it breaks the iOS build at `apple/SupermessageKit/CoreClient.swift:44` until that file is updated. iOS is being verified separately on another machine; handing it a build that does not compile, to discover later and out of context, buys tidiness at someone else's expense. `Core::new` has exactly two callers — `CoreClient.swift:44` and `SessionViewModel.kt:41` — so the blast radius is known and small either way, and the version that leaves a shipping platform untouched wins.

The two constructors must share their body. `install_tracing()` and the tokio runtime build are not to be duplicated — one private `fn build(data_dir: String, store: Box<dyn SecretStore>) -> Arc<Self>` with both constructors delegating to it.

## 4. The Android implementation

`AndroidSecretStore` lives in **`:app`**, beside `RosterPreferences`. Not `:kit`: that module depends on `:core` and coroutines and nothing else, which is what keeps its 198 tests on the JVM, and this needs a `Context`.

### 4.1 The recipe

A single AES-256/GCM key in `AndroidKeyStore`, created on first use, never leaving secure hardware. Values are encrypted with it and the ciphertext kept in DataStore.

```kotlin
private const val ALIAS = "dev.supermessage.secrets"
private const val TRANSFORM = "AES/GCM/NoPadding"

private fun key(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
    }.generateKey()
}
```

**The IV is generated by `Cipher`, never supplied.** AndroidKeyStore requires randomised encryption for GCM and rejects a caller-provided IV outright. Encrypt, then read `cipher.iv` and store it with the ciphertext: `Base64(iv || ciphertext)`, IV being 12 bytes. Decrypt with `GCMParameterSpec(128, iv)`.

Reusing an IV under one key is the classic GCM catastrophe, so this is not merely API etiquette — letting the platform generate it is what makes the scheme safe.

### 4.2 The failure that is not a failure

`KeyPermanentlyInvalidatedException` on decrypt means the key is gone for good — the stored ciphertext can never be read again. This is reachable in normal use (certain lock-screen changes, some restore-to-new-device paths).

**`get` returns `null` for that case, after deleting the unreadable value.** Not an error. `null` means "nothing stored", which sends `restore()` down its documented first-run path (`session.rs:145` returns `Ok(false)` for a missing homeserver) and lands the user on the sign-in screen — the only honest outcome, since the session really is unrecoverable. Reporting an error instead would strand them on a failure they cannot act on.

An invalidated key must also be regenerated rather than reused, or every subsequent `set` fails too.

### 4.3 Two rulings

**No `setUserAuthenticationRequired`.** It reads as the safer setting and is the wrong one here: it makes secrets unreadable while the device is locked, which breaks background sync and restore-on-boot, and push is on the roadmap. iOS already meets this state and its own comment says the app should expect it rather than treat it as corruption. A chat client that cannot receive messages while in a pocket has traded its function for a security property the app sandbox already substantially provides.

**Ciphertext goes in `SharedPreferences`, not DataStore** — and this reverses the first instinct, so the reason matters.

`RosterPreferences` uses DataStore, and one storage idiom would beat two. But `supermessage_core::secrets::SecretStore::get` is **synchronous** (`secrets.rs:66`) — the core calls it inline, inside `load_or_create_passphrase`, on whatever thread it is already on. So the foreign trait must be synchronous, and DataStore is deliberately async-only. Bridging them means `runBlocking` inside a callback invoked from Rust's tokio runtime: blocking a runtime worker thread on disk I/O, which is the one thing an async runtime asks you not to do.

`SharedPreferences` is synchronous by design and is the right shape here. `RosterPreferences` needs a `Flow` for Compose; this needs a blocking read for FFI. Opposite requirements, so opposite tools — a file named `secrets`, separate from the roster's, since their lifetimes differ.

Use `commit()` rather than `apply()` on write: `apply()` returns before the write reaches disk, and `set` must not claim success for a passphrase that is still in memory when the process dies.

## 5. What this must prove

### By test

1. **The adapter maps every error variant** — `Store`, `Auth`, `Network`, `Protocol` each survive the crossing as themselves. Rust, with a fake `HostSecretStore`.
2. **Round trip**: `set` then `get` returns the value; `get` on an absent key returns `null`; `delete` then `get` returns `null`. Instrumented — `AndroidKeyStore` exists only on a device.
3. **A value survives a process restart.** This is the one that matters: it is the difference between a store and a cache, and the whole point is that a session outlives the app.
4. **Distinct keys do not collide**, and a value round-trips unchanged through Base64 and GCM including non-ASCII.

### On a device

**Sign in against a real homeserver and see the roster.** This closes A1's acceptance criterion, which has never once been exercised on Android. Then kill the app and reopen it: `restore()` must reach `SIGNED_IN` without asking again — the proof that `set` truly persisted rather than merely returned.

### The standard

**A test that has never failed is not yet a regression test.** Every test above is mutated until it fails before it is kept. On this project that discipline has found eight tests that could not fail for their stated reason, five of them in shipping iOS code.

For the round-trip test specifically: a fake that stores in a `HashMap` would pass while proving nothing about Keystore. These run on a device against the real `AndroidKeyStore`, or they do not count.

## 6. Sequence

1. **The foreign trait and the adapter**, in `supermessage-ffi`, with the error-mapping tests. Ends with `cargo test` green and the Kotlin bindings regenerated.
2. **`with_secret_store`**, sharing a body with `new`. Ends with both constructors present and iOS's call site untouched.
3. **`AndroidSecretStore`** in `:app`, with instrumented tests. Ends with the round trip and the restart test green on a device.
4. **Wire it into `SessionViewModel`** and sign in for real. Ends with a roster on screen.

Step 3 carries the risk: Keystore behaviour differs between emulators and hardware, and the invalidation path is awkward to provoke. Step 4 is where A1 finally gets its acceptance.

## 7. What this does not cover

- **Migration.** Nothing is stored on Android today, because nothing ever could be. There is no old format to read.
- **The other platforms.** `KeyringStore` keeps serving macOS, Linux, Windows and iOS unchanged.
- **StrongBox.** `setIsStrongBoxBacked(true)` needs a hardware feature check and a fallback path on devices without the secure element. Worth doing later; not worth blocking sign-in on.
- **Key rotation**, and re-encrypting under a new key.
- **Backup exclusion.** Keystore-encrypted values are useless off-device anyway, since the key never leaves it, but the DataStore file's backup rules deserve their own look.
