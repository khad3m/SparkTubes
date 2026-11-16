package com.sparktube.ytauth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android-Keystore-backed AES-256/GCM key-value store for sensitive
 * strings (Google credentials). Values are encrypted with a key that never
 * leaves the hardware-backed Keystore. Plain-prefs fallback exists only for
 * devices where the Keystore is unavailable, and is flagged in prefs.
 */
object SecureStore {

    private const val PREFS = "ytauth_secure"
    private const val ALIAS = "ytauth_master_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun put(context: Context, key: String, value: String) {
        val enc = runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrNull()
        prefs(context).edit()
            .putString(key, enc ?: value)
            .putBoolean("$key.enc", enc != null)
            .apply()
    }

    fun get(context: Context, key: String): String? {
        val raw = prefs(context).getString(key, null) ?: return null
        val encrypted = prefs(context).getBoolean("$key.enc", false)
        if (!encrypted) return raw
        return runCatching {
            val data = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).remove("$key.enc").apply()
    }

    fun has(context: Context, key: String): Boolean = !get(context, key).isNullOrBlank()
}
