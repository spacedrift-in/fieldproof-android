package com.spacedrift.fieldproof.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "checklist_items")
data class ChecklistItemEntity(
    @PrimaryKey
    val id: String,

    val inspectionId: String,

    val title: String,

    val required: Boolean = true,

    // Retained for the aggregate progress queries; mirrors `outcome != NOT_STARTED`.
    val completed: Boolean = false,

    // Outcome recorded locally and synced to the server: NOT_STARTED/PASS/FAIL/NOT_APPLICABLE.
    val outcome: String = OUTCOME_NOT_STARTED,

    // Optional per-item note synced alongside the outcome.
    val note: String? = null,

    // The server checklist item `version` last acknowledged by a successful sync.
    // Null until the first sync so an unconditional (unversioned) update is sent.
    val syncedVersion: Int? = null,

    // Local sync lifecycle for this item's outcome: LOCAL/QUEUED/UPLOADING/SYNCED/FAILED/CONFLICT.
    val syncState: String = SYNC_STATE_LOCAL,

    // Monotonic counter bumped on every local outcome edit. A successful upload
    // may only acknowledge (mark SYNCED) the exact revision it uploaded, so a
    // slower in-flight upload can never clobber a newer local edit.
    val localRevision: Long = 0L
) {
    companion object {
        const val OUTCOME_NOT_STARTED = "NOT_STARTED"
        const val OUTCOME_PASS = "PASS"
        const val OUTCOME_FAIL = "FAIL"
        const val OUTCOME_NOT_APPLICABLE = "NOT_APPLICABLE"

        val OUTCOMES = setOf(
            OUTCOME_NOT_STARTED,
            OUTCOME_PASS,
            OUTCOME_FAIL,
            OUTCOME_NOT_APPLICABLE
        )

        const val SYNC_STATE_LOCAL = "LOCAL"
        const val SYNC_STATE_QUEUED = "QUEUED"
        const val SYNC_STATE_UPLOADING = "UPLOADING"
        const val SYNC_STATE_SYNCED = "SYNCED"
        const val SYNC_STATE_FAILED = "FAILED"
        const val SYNC_STATE_CONFLICT = "CONFLICT"
    }
}
