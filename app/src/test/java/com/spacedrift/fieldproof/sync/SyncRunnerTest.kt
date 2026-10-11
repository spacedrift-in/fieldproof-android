package com.spacedrift.fieldproof.sync

import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.evidenceEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunnerTest {

    private val syncDao = FakeSyncOperationDao()
    private val evidenceDao = FakeEvidenceDao()
    private val syncRepository = SyncRepository(syncDao)

    private fun runner(uploader: FakeEvidenceUploader) = SyncRunner(
        syncRepository = syncRepository,
        evidenceDao = evidenceDao,
        uploader = uploader
    )

    private suspend fun queue(evidence: EvidenceEntity): SyncOperationEntity {
        evidenceDao.insertEvidence(evidence)
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
    fun `conflict keeps operation on conflict status`() = runBlocking {
        val uploader = FakeEvidenceUploader(
            mutableListOf(EvidenceUploadResult.Conflict)
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
                entityType = "INSPECTION",
                operationType = "CREATE",
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
    fun `run with no queued operations is a no-op`() {
        val result = runBlocking {
            runner(FakeEvidenceUploader()).run()
        }
        assertEquals(SyncRunResult.AllDone, result)
    }
}