package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploader
import com.spacedrift.fieldproof.data.repository.SyncRepository
import javax.inject.Inject

sealed interface SyncRunResult {
    data object AllDone : SyncRunResult

    data object Retry : SyncRunResult
}

class SyncRunner @Inject constructor(
    private val syncRepository: SyncRepository,
    private val inspectionDao: InspectionDao,
    private val evidenceDao: EvidenceDao,
    private val inspectionUploader: InspectionUploader,
    private val uploader: EvidenceUploader
) {

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CONFLICT = "CONFLICT"
        const val STATUS_RESOLVED = "RESOLVED"
    }

    suspend fun run(): SyncRunResult {
        syncRepository.recoverStaleOperations()
        ensureInspectionOperations()

        // Inspections must exist server-side before their evidence can upload,
        // so process INSPECTION/CREATE operations before everything else.
        val operations = syncRepository.getQueuedOperations()
            .sortedBy { if (it.entityType == "INSPECTION") 0 else 1 }

        if (operations.isEmpty()) {
            return SyncRunResult.AllDone
        }

        var shouldRetry = false

        for (operation in operations) {
            when (dispatch(operation)) {
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

    private suspend fun ensureInspectionOperations() {
        for (operation in syncRepository.getQueuedOperations()) {
            if (operation.entityType != "EVIDENCE") {
                continue
            }
            val evidence = evidenceDao.getEvidence(operation.entityId) ?: continue
            if (syncRepository.getInspectionOperation(evidence.inspectionId) == null) {
                syncRepository.queueInspection(evidence.inspectionId)
            }
        }
    }

    private suspend fun dispatch(
        operation: SyncOperationEntity
    ): Outcome {
        return when (operation.entityType to operation.operationType) {
            "INSPECTION" to "CREATE" -> dispatchInspection(operation)
            "EVIDENCE" to "CREATE" -> dispatchEvidence(operation)
            else -> {
                syncRepository.markFailed(
                    operation = operation,
                    error = "Unsupported sync operation " +
                        "${operation.entityType}/${operation.operationType}."
                )
                Outcome.Finished
            }
        }
    }

    private suspend fun dispatchInspection(
        operation: SyncOperationEntity
    ): Outcome {
        if (operation.attempts >= MAX_ATTEMPTS) {
            syncRepository.markFailed(
                operation = operation,
                error = "Too many attempts; manual retry required."
            )
            return Outcome.Finished
        }

        val inspection = inspectionDao.getInspection(operation.entityId)
        if (inspection == null) {
            syncRepository.markFailed(
                operation = operation,
                error = "Local inspection record no longer exists."
            )
            return Outcome.Finished
        }

        val attempted = operation.copy(attempts = operation.attempts + 1)

        syncRepository.markUploading(operation)

        return when (val result = inspectionUploader.uploadInspection(
            inspection = inspection,
            idempotencyKey = inspection.id
        )) {
            is InspectionUploadResult.Success -> {
                syncRepository.markSynced(attempted)
                Outcome.Finished
            }

            is InspectionUploadResult.Conflict -> {
                syncRepository.markConflict(
                    operation = attempted,
                    conflict = result.serverConflict
                )
                Outcome.Finished
            }

            is InspectionUploadResult.Failed -> {
                handleInspectionFailure(
                    operation = attempted,
                    failure = result
                )
            }
        }
    }

    private suspend fun handleInspectionFailure(
        operation: SyncOperationEntity,
        failure: InspectionUploadResult.Failed
    ): Outcome {
        if (failure.retryable && operation.attempts < MAX_ATTEMPTS) {
            syncRepository.keepQueued(
                operation = operation,
                error = failure.message
            )
            return Outcome.ShouldRetry
        }

        syncRepository.markFailed(
            operation = operation,
            error = failure.message
        )
        return Outcome.Finished
    }

    private suspend fun dispatchEvidence(
        operation: SyncOperationEntity
    ): Outcome {
        if (operation.attempts >= MAX_ATTEMPTS) {
            syncRepository.markFailed(
                operation = operation,
                error = "Too many attempts; manual retry required."
            )
            evidenceDao.updateSyncStatus(
                evidenceId = operation.entityId,
                status = STATUS_FAILED
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

        when (val gate = inspectionGate(evidence)) {
            is Gate.Blocked -> {
                syncRepository.keepQueued(
                    operation = operation,
                    error = gate.message
                )
                evidenceDao.updateSyncStatus(
                    evidenceId = evidence.id,
                    status = STATUS_QUEUED
                )
                return Outcome.ShouldRetry
            }

            is Gate.Failed -> {
                syncRepository.markFailed(
                    operation = operation,
                    error = gate.message
                )
                evidenceDao.updateSyncStatus(
                    evidenceId = evidence.id,
                    status = STATUS_FAILED
                )
                return Outcome.Finished
            }

            Gate.Ready -> Unit
        }

        val attempted = operation.copy(attempts = operation.attempts + 1)

        syncRepository.markUploading(operation)
        evidenceDao.updateSyncStatus(
            evidenceId = evidence.id,
            status = STATUS_UPLOADING
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
                    status = STATUS_SYNCED
                )
                Outcome.Finished
            }

            is EvidenceUploadResult.Conflict -> {
                syncRepository.markConflict(
                    operation = attempted,
                    conflict = result.serverConflict
                )
                evidenceDao.updateSyncStatus(
                    evidenceId = evidence.id,
                    status = STATUS_CONFLICT
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

    private suspend fun inspectionGate(evidence: EvidenceEntity): Gate {
        val inspectionOperation = syncRepository.getInspectionOperation(
            inspectionId = evidence.inspectionId
        ) ?: run {
            syncRepository.queueInspection(evidence.inspectionId)
            return Gate.Blocked("Waiting for the inspection to sync.")
        }

        return when (inspectionOperation.status) {
            // RESOLVED means the user explicitly accepted/kept the server's
            // inspection after a conflict, so evidence may proceed.
            STATUS_SYNCED, STATUS_RESOLVED -> Gate.Ready

            STATUS_FAILED, STATUS_CONFLICT -> Gate.Failed(
                "The inspection has not synced; retry the inspection before its evidence."
            )

            else -> Gate.Blocked("Waiting for the inspection to sync.")
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
                status = STATUS_QUEUED
            )
            return Outcome.ShouldRetry
        }

        syncRepository.markFailed(
            operation = operation,
            error = failure.message
        )
        evidenceDao.updateSyncStatus(
            evidenceId = evidence.id,
            status = STATUS_FAILED
        )
        return Outcome.Finished
    }

    private sealed interface Gate {
        data object Ready : Gate

        data class Blocked(val message: String) : Gate

        data class Failed(val message: String) : Gate
    }

    private enum class Outcome {
        ShouldRetry,
        Finished
    }
}
