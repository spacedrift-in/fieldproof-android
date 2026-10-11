package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import javax.inject.Inject

sealed interface SyncRunResult {
    data object AllDone : SyncRunResult

    data object Retry : SyncRunResult
}

class SyncRunner @Inject constructor(
    private val syncRepository: SyncRepository,
    private val evidenceDao: EvidenceDao,
    private val uploader: EvidenceUploader
) {

    private companion object {
        const val MAX_ATTEMPTS = 4
    }

    suspend fun run(): SyncRunResult {
        val operations = syncRepository.getQueuedOperations()

        if (operations.isEmpty()) {
            return SyncRunResult.AllDone
        }

        var shouldRetry = false

        for (operation in operations) {
            when (val outcome = dispatch(operation)) {
                Outcome.ShouldRetry -> shouldRetry = true
                Outcome.Finished -> Unit
            }
        }

        return if (shouldRetry) {
            SyncRunResult.Retry
        } else {
            SyncRunResult.AllDone
        }
    }

    private suspend fun dispatch(
        operation: SyncOperationEntity
    ): Outcome {
        if (operation.entityType != "EVIDENCE" ||
            operation.operationType != "CREATE") {
            syncRepository.markFailed(
                operation = operation,
                error = "Unsupported sync operation " +
                    "${operation.entityType}/${operation.operationType}."
            )
            return Outcome.Finished
        }

        if (operation.attempts >= MAX_ATTEMPTS) {
            syncRepository.markFailed(
                operation = operation,
                error = "Too many attempts; manual retry required."
            )
            evidenceDao.updateSyncStatus(
                evidenceId = operation.entityId,
                status = "FAILED"
            )
            return Outcome.Finished
        }

        val evidence = evidenceDao.getEvidence(operation.entityId)
        if (evidence == null) {
            syncRepository.markFailed(
                operation = operation,
                error = "Local evidence record no longer exists."
            )
            return Outcome.Finished
        }

        val attempted = operation.copy(attempts = operation.attempts + 1)

        syncRepository.markUploading(operation)
        evidenceDao.updateSyncStatus(
            evidenceId = evidence.id,
            status = "UPLOADING"
        )

        val result = uploader.uploadEvidence(
            evidence = evidence,
            idempotencyKey = evidence.id
        )

        return when (result) {
            is EvidenceUploadResult.Success -> {
                syncRepository.markSynced(attempted)
                evidenceDao.updateSyncStatus(
                    evidenceId = evidence.id,
                    status = "SYNCED"
                )
                Outcome.Finished
            }

            is EvidenceUploadResult.Conflict -> {
                syncRepository.markConflict(
                    operation = attempted,
                    error = "Idempotency key was reused with different content."
                )
                evidenceDao.updateSyncStatus(
                    evidenceId = evidence.id,
                    status = "CONFLICT"
                )
                Outcome.Finished
            }

            is EvidenceUploadResult.Failed -> {
                handleFailure(
                    evidence = evidence,
                    operation = attempted,
                    failure = result
                )
            }
        }
    }

    private suspend fun handleFailure(
        evidence: EvidenceEntity,
        operation: SyncOperationEntity,
        failure: EvidenceUploadResult.Failed
    ): Outcome {
        if (failure.retryable && operation.attempts < MAX_ATTEMPTS) {
            syncRepository.keepQueued(
                operation = operation,
                error = failure.message
            )
            evidenceDao.updateSyncStatus(
                evidenceId = evidence.id,
                status = "QUEUED"
            )
            return Outcome.ShouldRetry
        }

        syncRepository.markFailed(
            operation = operation,
            error = failure.message
        )
        evidenceDao.updateSyncStatus(
            evidenceId = evidence.id,
            status = "FAILED"
        )
        return Outcome.Finished
    }

    private enum class Outcome {
        ShouldRetry,
        Finished
    }
}