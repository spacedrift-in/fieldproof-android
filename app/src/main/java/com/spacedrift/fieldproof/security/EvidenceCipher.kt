package com.spacedrift.fieldproof.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for evidence files using AES-256-GCM.
 *
 * The on-disk envelope is self-describing so it can be versioned and detected:
 *
 * ```
 * | magic (5 bytes "FPENC") | version (1 byte) | iv (12 bytes) | ciphertext+tag |
 * ```
 *
 * The key is supplied by an [EvidenceKeyProvider]; in production that is the
 * Android Keystore, so the key is non-exportable and device-bound. Losing the
 * keystore entry (factory reset, app data wipe, or key invalidation) makes the
 * sealed files permanently unreadable — see docs/security.md for the documented
 * key-loss behaviour.
 */
class EvidenceCipher(
    private val keyProvider: EvidenceKeyProvider
) {

    fun seal(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keyProvider.secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArray(MAGIC.size + 1 + iv.size + ciphertext.size).also { out ->
            var offset = 0
            MAGIC.copyInto(out, offset)
            offset += MAGIC.size
            out[offset] = VERSION
            offset += 1
            iv.copyInto(out, offset)
            offset += iv.size
            ciphertext.copyInto(out, offset)
        }
    }

    fun open(blob: ByteArray): ByteArray {
        require(isSealed(blob)) { "Not a FieldProof encrypted envelope." }

        val version = blob[MAGIC.size]
        require(version == VERSION) { "Unsupported evidence envelope version: $version." }

        val ivStart = MAGIC.size + 1
        val iv = blob.copyOfRange(ivStart, ivStart + IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(ivStart + IV_LENGTH_BYTES, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider.secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    fun isSealed(blob: ByteArray): Boolean {
        if (blob.size < MAGIC.size + 1 + IV_LENGTH_BYTES) return false
        for (index in MAGIC.indices) {
            if (blob[index] != MAGIC[index]) return false
        }
        return true
    }

    companion object {
        private val MAGIC = byteArrayOf(
            'F'.code.toByte(),
            'P'.code.toByte(),
            'E'.code.toByte(),
            'N'.code.toByte(),
            'C'.code.toByte()
        )
        private const val VERSION: Byte = 1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
