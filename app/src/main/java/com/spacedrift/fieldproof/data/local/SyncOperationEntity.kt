package com.spacedrift.fieldproof.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_operations")
data class SyncOperationEntity(
    @PrimaryKey
    val id: String,
    val entityId: String,
    val entityType: String,
    val operationType: String,
    val status: String = "QUEUED",
    val attempts: Int = 0,
    val lastError: String? = null,
    // Raw JSON payload of the server's 409 response, kept so the conflict can be
    // re-presented (with structured detail) after process death. Null for
    // non-conflict operations.
    val conflictDetail: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    // The checklist-item local revision this operation uploads. Status changes
    // that ack the upload are guarded by this value so a stale upload cannot
    // acknowledge a newer queued operation for the same item.
    val revision: Long = 0L
)