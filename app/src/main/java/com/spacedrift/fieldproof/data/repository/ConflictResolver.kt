package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.ChecklistItemDao
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.domain.conflict.ServerConflictParser
import java.util.UUID
import javax.inject.Inject

/**
 * Resolves server conflicts (``409`` responses) on behalf of the user.
 *
 * Local work is never deleted until the user makes a deliberate choice, and even
 * then the "use server" path archives the local copy instead of dropping it. The
 * two supported choices mirror the documented conflict policy
 * (``fieldproof-starter/docs/sync-state-machine.md``):
 *
 * - [keepLocal] retries the local copy. For evidence this re-keys the row to a
 *   fresh id, so a new idempotency key is used and the exact same bytes/notes can
 *   be uploaded as a new record without overwriting the server.
 * - [useServer] accepts the server's copy and marks the operation resolved,
 *   archiving (not deleting) the conflicting local evidence as ``SUPERSEDED``.
 */
class ConflictResolver @Inject constructor(
    private val syncRepository: SyncRepository,
    private val evidenceDao: EvidenceDao,
    private val checklistItemDao: ChecklistItemDao
) {

    suspend fun keepLocal(operation: SyncOperationEntity) {
        when (operation.entityType) {
            ENTITY_EVIDENCE -> keepLocalEvidence(operation)
            ENTITY_CHECKLIST -> keepLocalChecklist(operation)
            // Inspections (and anything else) have no duplicate-able identity, so
            // retry the existing operation in place; the local row is untouched.
            else -> syncRepository.requeueConflict(operation)
        }
    }

    suspend fun useServer(operation: SyncOperationEntity) {
        when (operation.entityType) {
            ENTITY_EVIDENCE ->
                // Keep the user's captured evidence in the local database, but stop
                // trying to upload it under the conflicting identity.
                if (evidenceDao.getEvidence(operation.entityId) != null) {
                    evidenceDao.updateSyncStatus(
                        evidenceId = operation.entityId,
                        status = STATUS_SUPERSEDED
                    )
                }

            ENTITY_CHECKLIST -> useServerChecklist(operation)
        }
        syncRepository.resolveConflict(operation)
    }

    private suspend fun keepLocalChecklist(operation: SyncOperationEntity) {
        val item = checklistItemDao.getChecklistItem(operation.entityId)
        if (item == null) {
            // Nothing local left to preserve; retry in place and let the worker
            // fail it cleanly if the item is really gone.
            syncRepository.requeueConflict(operation)
            return
        }

        // Adopt the server's current version as the baseline so the retried
        // upload is accepted (expected_version matches) and the user's local
        // outcome overwrites the server's value.
        val serverVersion = (ServerConflictParser.parse(operation.conflictDetail)
                as? ServerConflict.Version)
            ?.currentVersion
            ?: item.syncedVersion

        checklistItemDao.markSynced(
            itemId = item.id,
            syncState = STATUS_QUEUED,
            syncedVersion = serverVersion
        )
        syncRepository.requeueConflict(operation)
    }

    private suspend fun useServerChecklist(operation: SyncOperationEntity) {
        val item = checklistItemDao.getChecklistItem(operation.entityId) ?: return
        val conflict = ServerConflictParser.parse(operation.conflictDetail)

        if (conflict is ServerConflict.Version) {
            val serverOutcome = conflict.currentStatus
            checklistItemDao.setOutcome(
                itemId = item.id,
                outcome = serverOutcome,
                // The conflict payload does not carry the server note; keep the
                // local note rather than discarding the user's text.
                note = item.note,
                completed = serverOutcome != ChecklistItemEntity.OUTCOME_NOT_STARTED,
                syncState = STATUS_SYNCED
            )
            checklistItemDao.markSynced(
                itemId = item.id,
                syncState = STATUS_SYNCED,
                syncedVersion = conflict.currentVersion
            )
        } else {
            checklistItemDao.updateSyncState(item.id, STATUS_SYNCED)
        }
    }

    private suspend fun keepLocalEvidence(operation: SyncOperationEntity) {
        val original = evidenceDao.getEvidence(operation.entityId)
        if (original == null) {
            // Nothing local left to preserve; just retry whatever the operation
            // points at (the worker will fail it cleanly if it is really gone).
            syncRepository.requeueConflict(operation)
            return
        }

        val rekeyed = original.copy(
            id = UUID.randomUUID().toString(),
            syncStatus = STATUS_QUEUED
        )

        // Re-key rather than duplicate: move the same local evidence (photo file
        // reference, note and checklist link all preserved) to a fresh identity so
        // a new idempotency key is used on the retry.
        evidenceDao.insertEvidence(rekeyed)
        evidenceDao.deleteEvidence(original.id)
        syncRepository.queueEvidence(rekeyed.id)
        syncRepository.resolveConflict(operation)
    }

    private companion object {
        const val ENTITY_EVIDENCE = "EVIDENCE"
        const val ENTITY_CHECKLIST = "CHECKLIST"
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
    }
}
