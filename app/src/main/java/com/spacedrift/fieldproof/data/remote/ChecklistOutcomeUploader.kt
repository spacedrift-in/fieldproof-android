package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict

sealed interface ChecklistOutcomeUploadResult {
    data class Success(
        val itemVersion: Int,
        val inspectionVersion: Int
    ) : ChecklistOutcomeUploadResult

    data class Conflict(
        val serverConflict: ServerConflict
    ) : ChecklistOutcomeUploadResult

    data class Failed(
        val retryable: Boolean,
        val message: String
    ) : ChecklistOutcomeUploadResult
}

interface ChecklistOutcomeUploader {
    suspend fun uploadChecklistOutcome(
        item: ChecklistItemEntity,
        idempotencyKey: String
    ): ChecklistOutcomeUploadResult
}
