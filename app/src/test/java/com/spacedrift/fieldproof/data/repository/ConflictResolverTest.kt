package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionDao
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.inspectionEntity
import com.spacedrift.fieldproof.sync.SyncRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private val syncDao = FakeSyncOperationDao()
    private val inspectionDao = FakeInspectionDao()
    private val evidenceDao = FakeEvidenceDao()
    private val syncRepository = SyncRepository(syncDao)
    private val resolver = ConflictResolver(syncRepository, evidenceDao)

    private suspend fun conflictedEvidence(evidenceId: String = "e1"): SyncOperationEntity {
        val evidence = evidenceEntity(
            id = evidenceId,
            inspectionId = "i1",
            checklistItemId = "item-1",
            type = "NOTE",
            localUri = null,
            note = "Retest <valve> & re-torque"
        )
        evidenceDao.insertEvidence(evidence)
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueEvidence(evidenceId)
        val operation = syncDao.findByEntityId(evidenceId)!!
        syncRepository.markConflict(operation, "Idempotency key reused with different content.")
        return syncDao.findByEntityId(evidenceId)!!
    }

    @Test
    fun `keep local re-keys evidence, preserves content and queues a fresh upload`() = runBlocking {
        val conflict = conflictedEvidence()

        resolver.keepLocal(conflict)

        // The original identity is gone (re-keyed) but the content is preserved.
        assertNull(evidenceDao.get("e1"))
        val rekeyed = evidenceDao.snapshot().single()
        assertTrue(rekeyed.id != "e1")
        assertEquals("i1", rekeyed.inspectionId)
        assertEquals("item-1", rekeyed.checklistItemId)
        assertEquals("NOTE", rekeyed.type)
        assertEquals("Retest <valve> & re-torque", rekeyed.note)
        assertEquals("QUEUED", rekeyed.syncStatus)

        // The conflicting operation is retired and a fresh one is queued.
        assertEquals("RESOLVED", syncDao.get(conflict.id)!!.status)
        val queued = syncDao.snapshot()
            .first { it.entityType == "EVIDENCE" && it.status == "QUEUED" }
        assertEquals(rekeyed.id, queued.entityId)
        assertEquals(0, queued.attempts)
    }

    @Test
    fun `keep local requeues an inspection conflict without touching local data`() = runBlocking {
        inspectionDao.insertInspection(inspectionEntity(id = "i1", title = "Facility A"))
        syncRepository.queueInspection("i1")
        val op = syncDao.findByEntityId("i1")!!
        syncRepository.markConflict(op, "Inspection already exists with different attributes.")

        resolver.keepLocal(syncDao.findByEntityId("i1")!!)

        val requeued = syncDao.findByEntityId("i1")!!
        assertEquals("QUEUED", requeued.status)
        assertEquals(0, requeued.attempts)
        assertNull(requeued.lastError)
        assertNotNull(inspectionDao.get("i1"))
    }

    @Test
    fun `use server archives the local evidence and resolves the operation`() = runBlocking {
        val conflict = conflictedEvidence()

        resolver.useServer(conflict)

        // No data loss: the local row is retained, only marked SUPERSEDED.
        val archived = evidenceDao.get("e1")
        assertNotNull(archived)
        assertEquals("SUPERSEDED", archived!!.syncStatus)
        assertEquals("Retest <valve> & re-torque", archived.note)
        assertEquals("RESOLVED", syncDao.get(conflict.id)!!.status)
    }

    @Test
    fun `keep local evidence recovers and syncs through the runner`() = runBlocking {
        // Make the inspection already present server-side so evidence is not gated.
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueInspection("i1")
        syncRepository.markSynced(syncRepository.getInspectionOperation("i1")!!)

        val conflict = conflictedEvidence()
        resolver.keepLocal(conflict)

        val rekeyedId = evidenceDao.snapshot().single().id
        val runner = SyncRunner(
            syncRepository = syncRepository,
            inspectionDao = inspectionDao,
            evidenceDao = evidenceDao,
            inspectionUploader = FakeInspectionUploader(),
            uploader = FakeEvidenceUploader()
        )

        runner.run()

        assertEquals("SYNCED", evidenceDao.get(rekeyedId)!!.syncStatus)
        assertNull(evidenceDao.get("e1"))
    }
}
