package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.FakeChecklistItemDao
import com.spacedrift.fieldproof.FakeChecklistOutcomeUploader
import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionDao
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.checklistItemEntity
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.ChecklistOutcomeUploadResult
import com.spacedrift.fieldproof.data.remote.ChecklistOutcomeUploader
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.inspectionEntity
import com.spacedrift.fieldproof.testConflict
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunnerTest {

    private val syncDao = FakeSyncOperationDao()
    private val inspectionDao = FakeInspectionDao()
    private val evidenceDao = FakeEvidenceDao()
    private val checklistItemDao = FakeChecklistItemDao().also { it.syncOperations = syncDao }
    private val syncRepository = SyncRepository(syncDao)

    private fun runner(
        uploader: FakeEvidenceUploader,
        inspectionUploader: FakeInspectionUploader = FakeInspectionUploader(),
        checklistUploader: ChecklistOutcomeUploader = FakeChecklistOutcomeUploader()
    ) = SyncRunner(
        syncRepository = syncRepository,
        inspectionDao = inspectionDao,
        evidenceDao = evidenceDao,
        checklistItemDao = checklistItemDao,
        inspectionUploader = inspectionUploader,
        uploader = uploader,
        checklistOutcomeUploader = checklistUploader
    )

    private suspend fun markInspectionSynced(inspectionId: String) {
        inspectionDao.insertInspection(inspectionEntity(id = inspectionId))
        syncRepository.queueInspection(inspectionId)
        syncRepository.markSynced(syncRepository.getInspectionOperation(inspectionId)!!)
    }

    private suspend fun queue(evidence: EvidenceEntity): SyncOperationEntity {
        evidenceDao.insertEvidence(evidence)
        markInspectionSynced(evidence.inspectionId)
        syncRepository.queueEvidence(evidence.id)
        return syncDao.findByEntityId(evidence.id)!!
    }

    private suspend fun queueChecklist(item: ChecklistItemEntity): SyncOperationEntity {
        checklistItemDao.insertChecklistItem(item)
        markInspectionSynced(item.inspectionId)
        syncRepository.queueChecklistOutcome(item.id)
        return syncDao.findByEntityId(item.id)!!
    }

    @Test
    fun `successful sync marks operation and evidence as synced`() = runBlocking {
        val uploader = FakeEvidenceUploader()
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        val result = runner(uploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("SYNCED", evidenceDao.get("e1")!!.syncStatus)
        assertEquals(1, syncDao.findByEntityId("e1")!!.attempts)
        // Idempotency key is the evidence id, making replays deterministic.
        assertEquals("e1", uploader.uploaded.single().second)
    }

    @Test
    fun `retryable failure keeps operation queued then retry succeeds`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(
                EvidenceUploadResult.Failed(retryable = true, message = "boom"),
                EvidenceUploadResult.Success
            )
        )
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        val first = runner(uploader).run()
        assertEquals(SyncRunResult.Retry, first)
        assertEquals("QUEUED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("boom", syncDao.findByEntityId("e1")!!.lastError)
        assertEquals(1, syncDao.findByEntityId("e1")!!.attempts)
        assertEquals("QUEUED", evidenceDao.get("e1")!!.syncStatus)

        val second = runner(uploader).run()
        assertEquals(SyncRunResult.AllDone, second)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("SYNCED", evidenceDao.get("e1")!!.syncStatus)
        assertEquals(2, syncDao.findByEntityId("e1")!!.attempts)

        // Both attempts used the same deterministic idempotency key.
        assertEquals(listOf("e1", "e1"), uploader.uploaded.map { it.second })
    }

    @Test
    fun `operation fails permanently after max attempts`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(
                EvidenceUploadResult.Failed(retryable = true, message = "down"),
                EvidenceUploadResult.Failed(retryable = true, message = "down"),
                EvidenceUploadResult.Failed(retryable = true, message = "down"),
                EvidenceUploadResult.Failed(retryable = true, message = "still down")
            )
        )
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        val runner = runner(uploader)
        val first = runner.run()
        val second = runner.run()
        val third = runner.run()
        val fourth = runner.run()

        assertEquals(SyncRunResult.Retry, first)
        assertEquals(SyncRunResult.Retry, second)
        assertEquals(SyncRunResult.Retry, third)
        assertEquals(SyncRunResult.AllDone, fourth)
        assertEquals("FAILED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("FAILED", evidenceDao.get("e1")!!.syncStatus)
        assertEquals(4, syncDao.findByEntityId("e1")!!.attempts)
    }

    @Test
    fun `stale uploading operation is recovered and synced`() = runBlocking {
        val uploader = FakeEvidenceUploader()
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        // Simulate a worker that claimed the operation then died mid-upload.
        val stale = syncDao.findByEntityId("e1")!!
        val longAgo = System.currentTimeMillis() - 60 * 60 * 1000
        syncDao.updateStatus(
            operationId = stale.id,
            status = "UPLOADING",
            attempts = 1,
            lastError = null,
            updatedAt = longAgo
        )

        val result = runner(uploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("SYNCED", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `recent uploading operation is left for the active worker`() = runBlocking {
        val uploader = FakeEvidenceUploader()
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        syncRepository.markUploading(syncDao.findByEntityId("e1")!!)

        val result = runner(uploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertTrue(uploader.uploaded.isEmpty())
        assertEquals("UPLOADING", syncDao.findByEntityId("e1")!!.status)
    }

    @Test
    fun `manual retry requeues a failed operation so it can sync`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(
                EvidenceUploadResult.Failed(retryable = false, message = "rejected")
            )
        )
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        assertEquals(SyncRunResult.AllDone, runner(uploader).run())
        assertEquals("FAILED", syncDao.findByEntityId("e1")!!.status)
        assertEquals(1, syncDao.findByEntityId("e1")!!.attempts)

        syncRepository.retryFailedOperations()

        val requeued = syncDao.findByEntityId("e1")!!
        assertEquals("QUEUED", requeued.status)
        assertEquals(0, requeued.attempts)
        assertEquals(null, requeued.lastError)

        assertEquals(SyncRunResult.AllDone, runner(uploader).run())
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("SYNCED", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `conflict keeps operation on conflict status`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(
                EvidenceUploadResult.Conflict(
                    testConflict(message = "Idempotency-Key was already used with different evidence content.")
                )
            )
        )
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        val result = runner(uploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("CONFLICT", syncDao.findByEntityId("e1")!!.status)
        assertEquals("CONFLICT", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `non retryable failure marks failed without reschedule`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(
                EvidenceUploadResult.Failed(retryable = false, message = "rejected")
            )
        )
        val evidence = evidenceEntity(id = "e1", inspectionId = "i1")
        queue(evidence)

        val result = runner(uploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("FAILED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("FAILED", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `unsupported operation types are failed`() = runBlocking {
        val now = System.currentTimeMillis()
        syncDao.insert(
            SyncOperationEntity(
                id = UUID.randomUUID().toString(),
                entityId = "x",
                entityType = "TEMPLATE",
                operationType = "UPSERT",
                status = "QUEUED",
                attempts = 0,
                lastError = null,
                createdAt = now,
                updatedAt = now
            )
        )

        val result = runner(FakeEvidenceUploader()).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertTrue(syncDao.snapshot().all { it.status == "FAILED" })
    }

    @Test
    fun `queued operation with missing evidence is failed`() = runBlocking {
        syncRepository.queueEvidence("does-not-exist")

        val result = runner(FakeEvidenceUploader()).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("FAILED", syncDao.findByEntityId("does-not-exist")!!.status)
    }

    @Test
    fun `inspection create is uploaded before evidence`() = runBlocking {
        val inspectionUploader = FakeInspectionUploader()
        val uploader = FakeEvidenceUploader()
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueInspection("i1")
        evidenceDao.insertEvidence(evidenceEntity(id = "e1", inspectionId = "i1"))
        syncRepository.queueEvidence("e1")

        val result = runner(uploader, inspectionUploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals(listOf("i1"), inspectionUploader.uploaded.map { it.second })
        assertEquals("SYNCED", syncDao.findByEntityId("i1")!!.status)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("SYNCED", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `evidence waits until its inspection is synced`() = runBlocking {
        val inspectionUploader = FakeInspectionUploader(
            mutableListOf(
                InspectionUploadResult.Failed(retryable = true, message = "offline")
            )
        )
        val uploader = FakeEvidenceUploader()
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueInspection("i1")
        evidenceDao.insertEvidence(evidenceEntity(id = "e1", inspectionId = "i1"))
        syncRepository.queueEvidence("e1")

        val first = runner(uploader, inspectionUploader).run()

        assertEquals(SyncRunResult.Retry, first)
        assertTrue(uploader.uploaded.isEmpty())
        assertEquals("QUEUED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("QUEUED", evidenceDao.get("e1")!!.syncStatus)

        val second = runner(uploader, inspectionUploader).run()

        assertEquals(SyncRunResult.AllDone, second)
        assertEquals("SYNCED", syncDao.findByEntityId("i1")!!.status)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("e1", uploader.uploaded.single().second)
    }

    @Test
    fun `evidence fails when its inspection fails permanently`() = runBlocking {
        val inspectionUploader = FakeInspectionUploader(
            mutableListOf(
                InspectionUploadResult.Failed(retryable = false, message = "invalid")
            )
        )
        val uploader = FakeEvidenceUploader()
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueInspection("i1")
        evidenceDao.insertEvidence(evidenceEntity(id = "e1", inspectionId = "i1"))
        syncRepository.queueEvidence("e1")

        val result = runner(uploader, inspectionUploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertTrue(uploader.uploaded.isEmpty())
        assertEquals("FAILED", syncDao.findByEntityId("i1")!!.status)
        assertEquals("FAILED", syncDao.findByEntityId("e1")!!.status)
        assertEquals("FAILED", evidenceDao.get("e1")!!.syncStatus)
    }

    @Test
    fun `legacy evidence automatically queues an inspection operation`() = runBlocking {
        val inspectionUploader = FakeInspectionUploader()
        val uploader = FakeEvidenceUploader()
        // The inspection exists locally but was never queued for creation.
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        evidenceDao.insertEvidence(evidenceEntity(id = "e1", inspectionId = "i1"))
        syncRepository.queueEvidence("e1")

        val result = runner(uploader, inspectionUploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("SYNCED", syncDao.findByEntityId("i1")!!.status)
        assertEquals("SYNCED", syncDao.findByEntityId("e1")!!.status)
        assertEquals(1, inspectionUploader.uploaded.size)
    }

    @Test
    fun `run with no queued operations is a no-op`() {
        val result = runBlocking {
            runner(FakeEvidenceUploader()).run()
        }
        assertEquals(SyncRunResult.AllDone, result)
    }

    @Test
    fun `checklist outcome sync marks item and operation synced`() = runBlocking {
        val checklistUploader = FakeChecklistOutcomeUploader()
        val item = checklistItemEntity(
            id = "c1",
            inspectionId = "i1",
            outcome = ChecklistItemEntity.OUTCOME_PASS,
            completed = true,
            syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
        )
        queueChecklist(item)

        val result = runner(FakeEvidenceUploader(), checklistUploader = checklistUploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("SYNCED", syncDao.findByEntityId("c1")!!.status)
        assertEquals(ChecklistItemEntity.SYNC_STATE_SYNCED, checklistItemDao.get("c1")!!.syncState)
        assertEquals(2, checklistItemDao.get("c1")!!.syncedVersion)
        // Idempotency key is the checklist item id, making replays deterministic.
        assertEquals("c1", checklistUploader.uploaded.single().second)
    }

    @Test
    fun `checklist retryable failure keeps operation queued then retry succeeds`() = runBlocking {
        val checklistUploader = FakeChecklistOutcomeUploader(
            mutableListOf(
                ChecklistOutcomeUploadResult.Failed(retryable = true, message = "offline")
            )
        )
        val item = checklistItemEntity(
            id = "c1",
            inspectionId = "i1",
            outcome = ChecklistItemEntity.OUTCOME_FAIL,
            completed = true,
            syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
        )
        queueChecklist(item)

        val first = runner(FakeEvidenceUploader(), checklistUploader = checklistUploader).run()

        assertEquals(SyncRunResult.Retry, first)
        assertEquals("QUEUED", syncDao.findByEntityId("c1")!!.status)
        assertEquals(ChecklistItemEntity.SYNC_STATE_QUEUED, checklistItemDao.get("c1")!!.syncState)
        assertEquals(1, syncDao.findByEntityId("c1")!!.attempts)

        val second = runner(FakeEvidenceUploader(), checklistUploader = checklistUploader).run()

        assertEquals(SyncRunResult.AllDone, second)
        assertEquals("SYNCED", syncDao.findByEntityId("c1")!!.status)
        assertEquals(ChecklistItemEntity.SYNC_STATE_SYNCED, checklistItemDao.get("c1")!!.syncState)
    }

    @Test
    fun `checklist conflict marks item on conflict`() = runBlocking {
        val checklistUploader = FakeChecklistOutcomeUploader(
            mutableListOf(
                ChecklistOutcomeUploadResult.Conflict(
                    testConflict(message = "Expected version 1 but server is at version 2.")
                )
            )
        )
        val item = checklistItemEntity(
            id = "c1",
            inspectionId = "i1",
            outcome = ChecklistItemEntity.OUTCOME_PASS,
            completed = true,
            syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
        )
        queueChecklist(item)

        val result = runner(FakeEvidenceUploader(), checklistUploader = checklistUploader).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("CONFLICT", syncDao.findByEntityId("c1")!!.status)
        assertEquals(ChecklistItemEntity.SYNC_STATE_CONFLICT, checklistItemDao.get("c1")!!.syncState)
    }

    @Test
    fun `checklist outcome waits until its inspection is synced`() = runBlocking {
        val inspectionUploader = FakeInspectionUploader(
            mutableListOf(
                InspectionUploadResult.Failed(retryable = true, message = "offline")
            )
        )
        val checklistUploader = FakeChecklistOutcomeUploader()
        inspectionDao.insertInspection(inspectionEntity(id = "i1"))
        syncRepository.queueInspection("i1")
        checklistItemDao.insertChecklistItem(
            checklistItemEntity(
                id = "c1",
                inspectionId = "i1",
                outcome = ChecklistItemEntity.OUTCOME_PASS,
                completed = true,
                syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
            )
        )
        syncRepository.queueChecklistOutcome("c1")

        val first = runner(
            FakeEvidenceUploader(),
            inspectionUploader = inspectionUploader,
            checklistUploader = checklistUploader
        ).run()

        assertEquals(SyncRunResult.Retry, first)
        assertTrue(checklistUploader.uploaded.isEmpty())
        assertEquals("QUEUED", syncDao.findByEntityId("c1")!!.status)

        val second = runner(
            FakeEvidenceUploader(),
            inspectionUploader = inspectionUploader,
            checklistUploader = checklistUploader
        ).run()

        assertEquals(SyncRunResult.AllDone, second)
        assertEquals("SYNCED", syncDao.findByEntityId("i1")!!.status)
        assertEquals("SYNCED", syncDao.findByEntityId("c1")!!.status)
        assertEquals(1, checklistUploader.uploaded.size)
    }

    @Test
    fun `queued checklist operation with missing item is failed`() = runBlocking {
        syncRepository.queueChecklistOutcome("does-not-exist")

        val result = runner(FakeEvidenceUploader()).run()

        assertEquals(SyncRunResult.AllDone, result)
        assertEquals("FAILED", syncDao.findByEntityId("does-not-exist")!!.status)
    }

    @Test
    fun `edit during in-flight upload does not overwrite the newer edit`() = runBlocking {
        val item = checklistItemEntity(
            id = "c1",
            inspectionId = "i1",
            outcome = ChecklistItemEntity.OUTCOME_PASS,
            completed = true,
            syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
        )
        queueChecklist(item)

        // While the revision-0 upload is in flight, the user edits the item
        // again. This bumps the item to revision 1 and replaces the queued
        // operation (same deterministic id) with one carrying revision 1.
        val editingUploader = object : ChecklistOutcomeUploader {
            override suspend fun uploadChecklistOutcome(
                item: ChecklistItemEntity,
                idempotencyKey: String
            ): ChecklistOutcomeUploadResult {
                val newRevision = checklistItemDao.applyOutcome(
                    itemId = item.id,
                    outcome = ChecklistItemEntity.OUTCOME_FAIL,
                    note = "edited mid-flight",
                    completed = true,
                    syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
                )
                syncRepository.queueChecklistOutcome(item.id, newRevision)
                return ChecklistOutcomeUploadResult.Success(itemVersion = 5, inspectionVersion = 2)
            }
        }

        val first = runner(FakeEvidenceUploader(), checklistUploader = editingUploader).run()

        // The stale revision-0 upload must not be acknowledged: the newer edit
        // stays queued (and is not marked SYNCED) and the run retries.
        assertEquals(SyncRunResult.Retry, first)
        assertEquals(ChecklistItemEntity.SYNC_STATE_QUEUED, checklistItemDao.get("c1")!!.syncState)
        assertEquals(1L, checklistItemDao.get("c1")!!.localRevision)
        assertEquals("QUEUED", syncDao.findByEntityId("c1")!!.status)
        assertEquals(1L, syncDao.findByEntityId("c1")!!.revision)

        // The next run uploads the newer revision and converges.
        val second = runner(
            FakeEvidenceUploader(),
            checklistUploader = FakeChecklistOutcomeUploader()
        ).run()

        assertEquals(SyncRunResult.AllDone, second)
        assertEquals(ChecklistItemEntity.SYNC_STATE_SYNCED, checklistItemDao.get("c1")!!.syncState)
        assertEquals(ChecklistItemEntity.OUTCOME_FAIL, checklistItemDao.get("c1")!!.outcome)
        assertEquals("SYNCED", syncDao.findByEntityId("c1")!!.status)
    }
}