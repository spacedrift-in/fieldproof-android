package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.SyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class SyncRepository @Inject constructor(
    private val syncOperationDao: SyncOperationDao
) {

    fun observeOperations(): Flow<List<SyncOperationEntity>> {
        return syncOperationDao.observeOperations()
    }

    suspend fun queueEvidence(
        evidenceId: String
    ) {
        val now = System.currentTimeMillis()

        syncOperationDao.insert(
            SyncOperationEntity(
                id = UUID.randomUUID().toString(),
                entityId = evidenceId,
                entityType = "EVIDENCE",
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