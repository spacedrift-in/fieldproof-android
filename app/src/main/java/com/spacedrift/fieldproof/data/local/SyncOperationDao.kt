package com.spacedrift.fieldproof.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(
        operation: SyncOperationEntity
    )

    @Query(
        """
        SELECT * FROM sync_operations
        WHERE status = 'QUEUED'
        ORDER BY createdAt ASC
        """
    )
    suspend fun getQueuedOperations(): List<SyncOperationEntity>

    @Query(
        """
        SELECT * FROM sync_operations
        ORDER BY createdAt DESC
        """
    )
    fun observeOperations(): Flow<List<SyncOperationEntity>>

    @Query(
        """
        SELECT * FROM sync_operations
        WHERE entityType = 'INSPECTION'
            AND entityId = :inspectionId
        ORDER BY createdAt DESC
        LIMIT 1
        """
    )
    suspend fun getInspectionOperation(
        inspectionId: String
    ): SyncOperationEntity?

    @Query(
        """
        UPDATE sync_operations
        SET status = :status,
            attempts = :attempts,
            lastError = :lastError,
            updatedAt = :updatedAt
        WHERE id = :operationId
        """
    )
    suspend fun updateStatus(
        operationId: String,
        status: String,
        attempts: Int,
        lastError: String?,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE sync_operations
        SET status = 'QUEUED',
            updatedAt = :updatedAt
        WHERE status = 'UPLOADING'
            AND updatedAt <= :staleBefore
        """
    )
    suspend fun recoverStaleUploads(
        updatedAt: Long,
        staleBefore: Long
    )

    @Query(
        """
        UPDATE sync_operations
        SET status = 'QUEUED',
            attempts = 0,
            lastError = NULL,
            updatedAt = :updatedAt
        WHERE status = 'FAILED'
        """
    )
    suspend fun requeueFailed(
        updatedAt: Long
    )

    @Query(
        """
        DELETE FROM sync_operations
        WHERE id = :operationId
        """
    )
    suspend fun delete(
        operationId: String
    )
}