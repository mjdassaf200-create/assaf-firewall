package com.mrassaf.firewall

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Per-installation, non-exportable P-256 identity.
 * The private key stays in Android Keystore; only its public key and signatures leave the device.
 */
object DeviceIdentity {
    private const val KEY_ALIAS = "assaf.steel.device.identity.v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    @Synchronized
    private fun keyPair(): java.security.KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            return java.security.KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }

    fun publicKeyBase64(): String =
        Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)

    fun signChallenge(challenge: String): String {
        require(challenge.isNotBlank()) { "Security challenge is empty" }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair().private)
        signer.update(challenge.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    fun keyFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(keyPair().public.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
