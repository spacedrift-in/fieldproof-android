package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionDao
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
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
    private val syncRepository = SyncRepository(syncDao)

    private fun runner(
        uploader: FakeEvidenceUploader,
        inspectionUploader: FakeInspectionUploader = FakeInspectionUploader()
    ) = SyncRunner(
        syncRepository = syncRepository,
        inspectionDao = inspectionDao,
        evidenceDao = evidenceDao,
        inspectionUploader = inspectionUploader,
        uploader = uploader
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
                entityType = "CHECKLIST",
                operationType = "UPDATE",
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
}