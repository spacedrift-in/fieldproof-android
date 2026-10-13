package com.spacedrift.fieldproof.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Supplies the AES key used to encrypt evidence at rest.
 *
 * The production implementation is backed by the Android Keystore, so the key
 * material is generated on-device and cannot be exported. Tests substitute a
 * deterministic in-memory key so the cipher/vault logic can run on the JVM.
 */
fun interface EvidenceKeyProvider {
    fun secretKey(): SecretKey
}

/**
 * Android Keystore backed provider.
 *
 * The key is created lazily on first use inside the hardware-backed keystore
 * (when available) with a non-exportable, randomised AES-256-GCM key. Because
 * the key never leaves the device and is not included in Android backup, a
 * restored backup cannot be decrypted — this is the documented key-loss
 * behaviour (see docs/security.md).
 */
class AndroidKeystoreEvidenceKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
    private val keyStore: KeyStore = loadKeyStore()
) : EvidenceKeyProvider {

    @Volatile
    private var cached: SecretKey? = null

    override fun secretKey(): SecretKey {
        cached?.let { return it }

        synchronized(this) {
            cached?.let { return it }

            val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            val key = existing?.secretKey ?: generateKey()
            cached = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "fieldproof_evidence_key_v1"
        private const val KEY_SIZE_BITS = 256

        private fun loadKeyStore(): KeyStore {
            return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }
    }
}
