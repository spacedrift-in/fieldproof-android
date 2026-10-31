package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict

sealed interface InspectionUploadResult {
    data object Success : InspectionUploadResult

    data class Conflict(
        val serverConflict: ServerConflict
    ) : InspectionUploadResult

    data class Failed(
        val retryable: Boolean,
        val message: String
    ) : InspectionUploadResult
}

interface InspectionUploader {
    suspend fun uploadInspection(
        inspection: InspectionEntity,
        idempotencyKey: String
    ): InspectionUploadResult
}
