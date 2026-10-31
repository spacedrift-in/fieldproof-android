package com.spacedrift.fieldproof.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInspection(inspection: InspectionEntity)

    @Query("SELECT * FROM inspections ORDER BY updatedAt DESC")
    fun observeInspections(): Flow<List<InspectionEntity>>

    @Query(
        "SELECT i.*, " +
            "(SELECT COUNT(*) FROM checklist_items c WHERE c.inspectionId = i.id) AS totalItems, " +
            "(SELECT COUNT(*) FROM checklist_items c WHERE c.inspectionId = i.id AND c.completed = 1) AS completedItems, " +
            "(SELECT COUNT(*) FROM evidence e WHERE e.inspectionId = i.id) AS evidenceCount " +
            "FROM inspections i ORDER BY i.updatedAt DESC"
    )
    fun observeInspectionSummaries(): Flow<List<InspectionSummaryEntity>>

    @Query("SELECT * FROM inspections WHERE id = :inspectionId LIMIT 1")
    suspend fun getInspection(
        inspectionId: String
    ): InspectionEntity?

    @Query("SELECT * FROM inspections WHERE id = :inspectionId LIMIT 1")
    fun observeInspection(
        inspectionId: String
    ): Flow<InspectionEntity?>

    @Query("DELETE FROM inspections WHERE id = :inspectionId")
    suspend fun deleteInspection(
        inspectionId: String
    )

    @Query("DELETE FROM inspections")
    suspend fun deleteAll()
}