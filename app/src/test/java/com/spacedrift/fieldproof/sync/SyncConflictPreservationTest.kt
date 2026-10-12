package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionDao
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.domain.conflict.ServerConflictParser
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.inspectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Contract test for the acceptance criterion: a server conflict is recorded
 * (payload preserved) and **no local data is overwritten**.
 */
class SyncConflictPreservationTest {

    private val syncDao = FakeSyncOperationDao()
    private val inspectionDao = FakeInspectionDao()
    private val evidenceDao = FakeEvidenceDao()
    private val syncRepository = SyncRepository(syncDao)

    private val conflictBody =
        """{"detail":"Idempotency-Key was already used with different evidence content."}"""

    private fun runner(uploader: FakeEvidenceUploader) = SyncRunner(
        syncRepository = syncRepository,
        inspectionDao = inspectionDao,
        evidenceDao = evidenceDao,
        inspectionUploader = FakeInspectionUploader(),
        uploader = uploader
    )

    @Test
    fun `evidence conflict preserves the payload and leaves local content untouched`() =
        runBlocking {
            // Inspection already exists server-side so evidence is not gated.
            inspectionDao.insertInspection(inspectionEntity(id = "i1"))
            syncRepository.queueInspection("i1")
            syncRepository.markSynced(syncRepository.getInspectionOperation("i1")!!)

            val evidence = evidenceEntity(
                id = "e1",
                inspectionId = "i1",
                checklistItemId = "item-1",
                type = "PHOTO",
                localUri = "file:///tmp/FIELDPROOF_keep.jpg",
                note = "Handrail loose"
            )
            evidenceDao.insertEvidence(evidence)
            syncRepository.queueEvidence("e1")

            val uploader = FakeEvidenceUploader(
                mutableListOf(
                    EvidenceUploadResult.Conflict(ServerConflictParser.parse(conflictBody))
                )
            )

            runner(uploader).run()

            // Local content is untouched — only sync metadata changed.
            val stored = evidenceDao.get("e1")
            assertNotNull(stored)
            stored!!
            assertEquals("item-1", stored.checklistItemId)
            assertEquals("PHOTO", stored.type)
            assertEquals("file:///tmp/FIELDPROOF_keep.jpg", stored.localUri)
            assertEquals("Handrail loose", stored.note)
            assertEquals("CONFLICT", stored.syncStatus)
            assertEquals(1, evidenceDao.snapshot().size)

            // The server payload is preserved on the operation for the UI.
            val operation = syncDao.findByEntityId("e1")!!
            assertEquals("CONFLICT", operation.status)
            assertEquals(
                "Idempotency-Key was already used with different evidence content.",
                operation.lastError
            )
            assertEquals(conflictBody, operation.conflictDetail)
        }
}
