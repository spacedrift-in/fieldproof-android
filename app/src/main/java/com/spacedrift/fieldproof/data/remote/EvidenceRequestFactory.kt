package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.EvidenceEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

sealed interface EvidencePartsResult {
    data class Ok(
        val parts: List<MultipartBody.Part>
    ) : EvidencePartsResult

    data object MissingFile : EvidencePartsResult
}

object EvidenceRequestFactory {

    private const val PHOTO_KIND = "photo"
    private const val NOTE_KIND = "note"
    private const val PHOTO_MEDIA_TYPE = "image/jpeg"

    fun build(
        evidence: EvidenceEntity,
        photoBytes: ByteArray?
    ): EvidencePartsResult {
        val kind = kindFor(evidence.type)

        if (kind == PHOTO_KIND && photoBytes == null) {
            return EvidencePartsResult.MissingFile
        }

        val parts = mutableListOf<MultipartBody.Part>()

        parts += MultipartBody.Part.createFormData("id", evidence.id)
        parts += MultipartBody.Part.createFormData("kind", kind)
        parts += MultipartBody.Part.createFormData(
            "captured_at",
            evidence.capturedAt.toString()
        )

        evidence.checklistItemId?.let { checklistItemId ->
            parts += MultipartBody.Part.createFormData(
                "checklist_item_id",
                checklistItemId
            )
        }

        evidence.note?.let { note ->
            parts += MultipartBody.Part.createFormData("note", note)
        }

        if (kind == PHOTO_KIND) {
            val body = photoBytes!!.toRequestBody(
                PHOTO_MEDIA_TYPE.toMediaType()
            )
            parts += MultipartBody.Part.createFormData(
                name = "file",
                filename = filenameFor(evidence),
                body = body
            )
        }

        return EvidencePartsResult.Ok(parts)
    }

    fun kindFor(type: String): String {
        return when (type.uppercase()) {
            "PHOTO" -> PHOTO_KIND
            "NOTE" -> NOTE_KIND
            else -> type.lowercase()
        }
    }

    private fun filenameFor(evidence: EvidenceEntity): String {
        evidence.localUri
            ?.let { uri ->
                URI.create(uri).path
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    // Evidence is stored encrypted as `<uuid>.fpenc`; never
                    // leak that extension to the server. Only keep a name that
                    // already looks like an image.
                    ?.takeIf { name ->
                        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                    }
                    ?.let { return it }
            }
        return "evidence_${evidence.id}.jpg"
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
}