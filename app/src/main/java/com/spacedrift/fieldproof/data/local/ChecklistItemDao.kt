package com.spacedrift.fieldproof.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChecklistItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItem(
        item: ChecklistItemEntity
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChecklistItems(
        items: List<ChecklistItemEntity>
    )

    @Query(
        "SELECT * FROM checklist_items " +
                "WHERE inspectionId = :inspectionId " +
                "ORDER BY id"
    )
    fun observeChecklistItems(
        inspectionId: String
    ): Flow<List<ChecklistItemEntity>>

    @Query(
        "SELECT * FROM checklist_items WHERE id = :itemId LIMIT 1"
    )
    suspend fun getChecklistItem(
        itemId: String
    ): ChecklistItemEntity?

    @Query(
        "SELECT * FROM checklist_items WHERE inspectionId = :inspectionId"
    )
    suspend fun getForInspection(
        inspectionId: String
    ): List<ChecklistItemEntity>

    @Query(
        "UPDATE checklist_items " +
                "SET completed = :completed " +
                "WHERE id = :itemId"
    )
    suspend fun setCompleted(
        itemId: String,
        completed: Boolean
    )

    @Query(
        "UPDATE checklist_items " +
                "SET outcome = :outcome, " +
                "note = :note, " +
                "completed = :completed, " +
                "syncState = :syncState " +
                "WHERE id = :itemId"
    )
    suspend fun setOutcome(
        itemId: String,
        outcome: String,
        note: String?,
        completed: Boolean,
        syncState: String
    )

    @Query(
        "UPDATE checklist_items " +
                "SET syncState = :syncState " +
                "WHERE id = :itemId"
    )
    suspend fun updateSyncState(
        itemId: String,
        syncState: String
    )

    @Query(
        "UPDATE checklist_items " +
                "SET syncState = :syncState, syncedVersion = :syncedVersion " +
                "WHERE id = :itemId"
    )
    suspend fun markSynced(
        itemId: String,
        syncState: String,
        syncedVersion: Int?
    )

    @Query(
        "SELECT localRevision FROM checklist_items WHERE id = :itemId LIMIT 1"
    )
    suspend fun getLocalRevision(
        itemId: String
    ): Long?

    @Query(
        "UPDATE checklist_items " +
                "SET localRevision = localRevision + 1 " +
                "WHERE id = :itemId"
    )
    suspend fun bumpLocalRevision(
        itemId: String
    ): Int

    /**
     * Records a new outcome and bumps the item's local revision in one
     * transaction, returning the revision that the subsequent sync operation
     * must carry. Keeping the edit and its revision atomic means a queued
     * operation can never reference a revision that was never persisted.
     */
    @Transaction
    suspend fun applyOutcome(
        itemId: String,
        outcome: String,
        note: String?,
        completed: Boolean,
        syncState: String
    ): Long {
        setOutcome(
            itemId = itemId,
            outcome = outcome,
            note = note,
            completed = completed,
            syncState = syncState
        )
        bumpLocalRevision(itemId)
        return getLocalRevision(itemId) ?: 0L
    }

    /**
     * Compare-and-set acknowledgement of a checklist-outcome upload. Both the
     * item and its sync operation are only marked SYNCED when their revision
     * still equals [revision] — i.e. no newer local edit superseded the upload.
     * Runs in a single Room transaction so the item and operation never diverge.
     * Returns true when the acknowledgement was applied.
     */
    @Transaction
    suspend fun acknowledgeSyncedOutcome(
        itemId: String,
        operationId: String,
        revision: Long,
        syncedVersion: Int?,
        attempts: Int,
        now: Long
    ): Boolean {
        val itemRows = markItemSyncedIfRevision(
            itemId = itemId,
            syncState = STATUS_SYNCED,
            syncedVersion = syncedVersion,
            revision = revision
        )
        val operationRows = markOperationSyncedIfRevision(
            operationId = operationId,
            revision = revision,
            attempts = attempts,
            updatedAt = now
        )
        return itemRows > 0 && operationRows > 0
    }

    /**
     * Compare-and-set claim of a queued operation before uploading. Guards
     * against an upload being marked UPLOADING after a newer edit already
     * replaced the operation, which would otherwise leave the new operation
     * stuck. Returns true when this caller owns the upload.
     */
    @Transaction
    suspend fun claimChecklistUpload(
        operationId: String,
        revision: Long,
        attempts: Int,
        now: Long
    ): Boolean {
        return markOperationUploadingIfRevision(
            operationId = operationId,
            revision = revision,
            attempts = attempts,
            updatedAt = now
        ) > 0
    }

    @Query(
        "UPDATE checklist_items " +
                "SET syncState = :syncState, syncedVersion = :syncedVersion " +
                "WHERE id = :itemId AND localRevision = :revision"
    )
    suspend fun markItemSyncedIfRevision(
        itemId: String,
        syncState: String,
        syncedVersion: Int?,
        revision: Long
    ): Int

    @Query(
        "UPDATE sync_operations " +
                "SET status = 'UPLOADING', attempts = :attempts, " +
                "lastError = NULL, updatedAt = :updatedAt " +
                "WHERE id = :operationId AND revision = :revision"
    )
    suspend fun markOperationUploadingIfRevision(
        operationId: String,
        revision: Long,
        attempts: Int,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE sync_operations " +
                "SET status = 'SYNCED', attempts = :attempts, " +
                "lastError = NULL, updatedAt = :updatedAt " +
                "WHERE id = :operationId AND revision = :revision"
    )
    suspend fun markOperationSyncedIfRevision(
        operationId: String,
        revision: Long,
        attempts: Int,
        updatedAt: Long
    ): Int

    @Query(
        "DELETE FROM checklist_items " +
                "WHERE inspectionId = :inspectionId"
    )
    suspend fun deleteForInspection(
        inspectionId: String
    )

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAll()

    private companion object {
        const val STATUS_SYNCED = "SYNCED"
    }
}
