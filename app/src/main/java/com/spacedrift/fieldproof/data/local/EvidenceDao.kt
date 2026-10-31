package com.spacedrift.fieldproof.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(
        evidence: EvidenceEntity
    )

    @Query(
        "SELECT * FROM evidence WHERE id = :id"
    )
    suspend fun getEvidence(
        id: String
    ): EvidenceEntity?

    @Query(
        "SELECT * FROM evidence " +
                "WHERE inspectionId = :inspectionId " +
                "ORDER BY capturedAt DESC"
    )
    fun observeEvidence(
        inspectionId: String
    ): Flow<List<EvidenceEntity>>

    @Query(
        "SELECT * FROM evidence " +
                "WHERE inspectionId = :inspectionId " +
                "ORDER BY capturedAt DESC"
    )
    suspend fun getForInspection(
        inspectionId: String
    ): List<EvidenceEntity>

    @Query(
        "SELECT * FROM evidence " +
                "WHERE syncStatus = 'SYNCED' " +
                "AND capturedAt < :capturedBefore " +
                "AND localUri IS NOT NULL"
    )
    suspend fun getSyncedCapturedBefore(
        capturedBefore: Long
    ): List<EvidenceEntity>

    @Query(
        "UPDATE evidence SET localUri = NULL WHERE id = :evidenceId"
    )
    suspend fun clearLocalUri(
        evidenceId: String
    )

    @Query(
        "UPDATE evidence " +
                "SET syncStatus = :status " +
                "WHERE id = :evidenceId"
    )
    suspend fun updateSyncStatus(
        evidenceId: String,
        status: String
    )

    @Query(
        "DELETE FROM evidence " +
                "WHERE inspectionId = :inspectionId"
    )
    suspend fun deleteForInspection(
        inspectionId: String
    )

    @Query(
        "DELETE FROM evidence " +
                "WHERE id = :evidenceId"
    )
    suspend fun deleteEvidence(
        evidenceId: String
    )

    @Query("DELETE FROM evidence")
    suspend fun deleteAll()
}