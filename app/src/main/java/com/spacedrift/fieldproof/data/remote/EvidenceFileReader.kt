package com.spacedrift.fieldproof.data.remote

import android.content.ContentResolver
import android.net.Uri
import com.spacedrift.fieldproof.security.EvidenceVault
import javax.inject.Inject

interface EvidenceFileReader {
    suspend fun readBytes(
        localUri: String
    ): ByteArray?
}

/**
 * Reads evidence bytes for upload. Files written by the encrypted vault are
 * decrypted through [EvidenceVault]; content URIs (and any other scheme) fall
 * back to the [ContentResolver].
 */
class ContentResolverEvidenceFileReader @Inject constructor(
    private val contentResolver: ContentResolver,
    private val evidenceVault: EvidenceVault
) : EvidenceFileReader {

    override suspend fun readBytes(
        localUri: String
    ): ByteArray? {
        evidenceVault.read(localUri)?.let { return it }

        return try {
            val uri = Uri.parse(localUri)
            contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            }
        } catch (exception: Exception) {
            null
        }
    }
}
