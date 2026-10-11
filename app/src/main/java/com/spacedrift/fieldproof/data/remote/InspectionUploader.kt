package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.InspectionEntity

sealed interface InspectionUploadResult {
    data object Success : InspectionUploadResult

    data object Conflict : InspectionUploadResult

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
