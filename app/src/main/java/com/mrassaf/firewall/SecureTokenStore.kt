package com.mrassaf.firewall

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
 * Stores refresh credentials encrypted with an AES-GCM key held by Android Keystore.
 * Access tokens remain short-lived; refresh credentials are never stored as plaintext.
 */
object SecureTokenStore {
    private const val STORE = "assaf_secure_tokens"
    private const val KEY_ALIAS = "assaf.refresh.aesgcm.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(name, encoded).apply()
    }

    fun read(context: Context, name: String): String? {
        val encoded = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val ciphertext = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clear(context: Context, name: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(name).apply()
    }
}
