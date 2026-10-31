package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.SyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
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

    /**
     * Requeue a single failed operation so the worker retries it. Backs the
     * field-level "Retry" action; only sync metadata is reset, local content is
     * never touched.
     */
    suspend fun retryOperation(operationId: String) {
        syncOperationDao.requeueOperation(
            operationId = operationId,
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

    /**
     * Queue/refresh a checklist-outcome sync operation. The operation id is
     * deterministic per item, so repeated edits collapse into one pending
     * UPDATE rather than piling up duplicate operations. [revision] is the
     * item's local revision at the time of the edit; it is stored on the
     * operation so the upload can be acknowledged only while it is still the
     * latest (compare-and-set) revision.
     */
    suspend fun queueChecklistOutcome(
        checklistItemId: String,
        revision: Long = 0L
    ) {
        val now = System.currentTimeMillis()
        syncOperationDao.insert(
            SyncOperationEntity(
                id = "checklist:$checklistItemId",
                entityId = checklistItemId,
                entityType = "CHECKLIST",
                operationType = "UPDATE",
                status = "QUEUED",
                attempts = 0,
                lastError = null,
                createdAt = now,
                updatedAt = now,
                revision = revision
            )
        )
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

    /**
     * Removes sync operations for the given entity ids. Used when an inspection
     * (and everything under it) is deleted so queued/failed work does not outlive
     * the deleted records.
     */
    suspend fun deleteForEntities(entityIds: List<String>) {
        if (entityIds.isEmpty()) return
        syncOperationDao.deleteForEntities(entityIds)
    }

    suspend fun deleteAll() {
        syncOperationDao.deleteAll()
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

    /**
     * Marks an operation as conflicted, preserving the server's message and the
     * raw 409 payload for the UI. Only sync metadata is touched; the local
     * inspection/evidence records are never modified here.
     */
    suspend fun markConflict(
        operation: SyncOperationEntity,
        conflict: ServerConflict
    ) {
        syncOperationDao.markConflict(
            operationId = operation.id,
            error = conflict.message,
            conflictDetail = conflict.rawDetail,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Conflict variant for callers that only have a message (e.g. tests).
     * Keeps the operation on CONFLICT without a structured payload.
     */
    suspend fun markConflict(
        operation: SyncOperationEntity,
        error: String
    ) {
        syncOperationDao.markConflict(
            operationId = operation.id,
            error = error,
            conflictDetail = null,
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

    /**
     * Requeue a conflicted operation so the sync worker retries it. Used when the
     * user chooses to keep their local copy; the local entity is preserved
     * separately by [ConflictResolver].
     */
    suspend fun requeueConflict(
        operation: SyncOperationEntity
    ) {
        syncOperationDao.requeueOperation(
            operationId = operation.id,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Mark a conflicted operation as deliberately resolved. The operation is
     * retained (status ``RESOLVED``) for auditability rather than deleted.
     */
    suspend fun resolveConflict(
        operation: SyncOperationEntity
    ) {
        syncOperationDao.markResolved(
            operationId = operation.id,
            updatedAt = System.currentTimeMillis()
        )
    }
}