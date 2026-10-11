package com.spacedrift.fieldproof.data.remote

import android.content.ContentResolver
import android.net.Uri
import javax.inject.Inject

interface EvidenceFileReader {
    suspend fun readBytes(
        localUri: String
    ): ByteArray?
}

class ContentResolverEvidenceFileReader @Inject constructor(
    private val contentResolver: ContentResolver
) : EvidenceFileReader {

    override suspend fun readBytes(
        localUri: String
    ): ByteArray? {
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