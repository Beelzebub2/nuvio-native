package com.nuvio.app.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Profile-bound AES-GCM values; the non-exportable key lives in Android Keystore. */
internal object AndroidCredentialProtection {
    private const val PREFIX = "keystore:v1:"
    private const val KEY_ALIAS = "starlit.profile.secrets.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isProtected(stored: String) = stored.startsWith(PREFIX)

    fun protect(scopedKey: String, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(scopedKey.encodeToByteArray())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP)
        return "$PREFIX$iv.$ciphertext"
    }

    fun reveal(scopedKey: String, stored: String): String {
        // One-time migration of legacy plaintext preferences; callers must immediately
        // replace the old entry after a successful read. Unknown formats fail closed.
        if (!stored.startsWith("keystore:")) return stored
        require(stored.startsWith(PREFIX)) { "Unknown encrypted credential format" }
        val parts = stored.removePrefix(PREFIX).split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted credential" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        require(iv.size == 12) { "Invalid encrypted credential nonce" }
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(scopedKey.encodeToByteArray())
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).decodeToString()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
