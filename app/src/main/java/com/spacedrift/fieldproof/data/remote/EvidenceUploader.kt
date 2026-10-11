package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.EvidenceEntity

sealed interface EvidenceUploadResult {
    data object Success : EvidenceUploadResult

    data object Conflict : EvidenceUploadResult

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