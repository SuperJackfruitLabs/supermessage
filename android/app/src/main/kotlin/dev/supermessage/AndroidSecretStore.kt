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
