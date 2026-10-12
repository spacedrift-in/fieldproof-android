package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
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
    private val evidenceDao: EvidenceDao
) {

    suspend fun keepLocal(operation: SyncOperationEntity) {
        if (operation.entityType == ENTITY_EVIDENCE) {
            keepLocalEvidence(operation)
        } else {
            // Inspections (and anything else) have no duplicate-able identity, so
            // retry the existing operation in place; the local row is untouched.
            syncRepository.requeueConflict(operation)
        }
    }

    suspend fun useServer(operation: SyncOperationEntity) {
        if (operation.entityType == ENTITY_EVIDENCE) {
            // Keep the user's captured evidence in the local database, but stop
            // trying to upload it under the conflicting identity.
            if (evidenceDao.getEvidence(operation.entityId) != null) {
                evidenceDao.updateSyncStatus(
                    evidenceId = operation.entityId,
                    status = STATUS_SUPERSEDED
                )
            }
        }
        syncRepository.resolveConflict(operation)
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
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
    }
}
