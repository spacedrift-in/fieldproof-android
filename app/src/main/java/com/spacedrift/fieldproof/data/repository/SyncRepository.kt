package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.SyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class SyncRepository @Inject constructor(
    private val syncOperationDao: SyncOperationDao
) {

    private companion object {
        // A worker that dies mid-upload can leave an operation stuck in
        // UPLOADING. Assume it is stale once it has not been touched for a
        // while, then let it be retried (uploads are idempotent).
        const val STALE_UPLOAD_TIMEOUT_MS = 15 * 60 * 1000L
    }

    fun observeOperations(): Flow<List<SyncOperationEntity>> {
        return syncOperationDao.observeOperations()
    }

    suspend fun recoverStaleOperations() {
        val now = System.currentTimeMillis()
        syncOperationDao.recoverStaleUploads(
            updatedAt = now,
            staleBefore = now - STALE_UPLOAD_TIMEOUT_MS
        )
    }

    suspend fun retryFailedOperations() {
        syncOperationDao.requeueFailed(
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun queueEvidence(
        evidenceId: String
    ) {
        queueCreate(entityId = evidenceId, entityType = "EVIDENCE")
    }

    suspend fun queueInspection(
        inspectionId: String
    ) {
        queueCreate(entityId = inspectionId, entityType = "INSPECTION")
    }

    suspend fun getInspectionOperation(
        inspectionId: String
    ): SyncOperationEntity? {
        return syncOperationDao.getInspectionOperation(inspectionId)
    }

    private suspend fun queueCreate(
        entityId: String,
        entityType: String
    ) {
        val now = System.currentTimeMillis()

        syncOperationDao.insert(
            SyncOperationEntity(
                id = UUID.randomUUID().toString(),
                entityId = entityId,
                entityType = entityType,
                operationType = "CREATE",
                status = "QUEUED",
                attempts = 0,
                lastError = null,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun getQueuedOperations(): List<SyncOperationEntity> {
        return syncOperationDao.getQueuedOperations()
    }

    suspend fun markUploading(
        operation: SyncOperationEntity
    ) {
        syncOperationDao.updateStatus(
            operationId = operation.id,
            status = "UPLOADING",
            attempts = operation.attempts + 1,
            lastError = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun markFailed(
        operation: SyncOperationEntity,
        error: String
    ) {
        syncOperationDao.updateStatus(
            operationId = operation.id,
            status = "FAILED",
            attempts = operation.attempts,
            lastError = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun keepQueued(
        operation: SyncOperationEntity,
        error: String
    ) {
        syncOperationDao.updateStatus(
            operationId = operation.id,
            status = "QUEUED",
            attempts = operation.attempts,
            lastError = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun markConflict(
        operation: SyncOperationEntity,
        error: String
    ) {
        syncOperationDao.updateStatus(
            operationId = operation.id,
            status = "CONFLICT",
            attempts = operation.attempts,
            lastError = error,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun markSynced(
        operation: SyncOperationEntity
    ) {
        syncOperationDao.updateStatus(
            operationId = operation.id,
            status = "SYNCED",
            attempts = operation.attempts,
            lastError = null,
            updatedAt = System.currentTimeMillis()
        )
    }
}