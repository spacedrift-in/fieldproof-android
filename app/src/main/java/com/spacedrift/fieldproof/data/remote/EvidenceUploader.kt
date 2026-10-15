package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict

sealed interface EvidenceUploadResult {
    data object Success : EvidenceUploadResult

    data class Conflict(
        val serverConflict: ServerConflict
    ) : EvidenceUploadResult

    data class Failed(
        val retryable: Boolean,
        val message: String
    ) : EvidenceUploadResult
}

interface EvidenceUploader {
    suspend fun uploadEvidence(
        evidence: EvidenceEntity,
        idempotencyKey: String
    ): EvidenceUploadResult
}
