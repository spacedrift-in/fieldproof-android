package com.spacedrift.fieldproof.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        "DELETE FROM checklist_items " +
                "WHERE inspectionId = :inspectionId"
    )
    suspend fun deleteForInspection(
        inspectionId: String
    )

    @Query("DELETE FROM checklist_items")
    suspend fun deleteAll()
}
