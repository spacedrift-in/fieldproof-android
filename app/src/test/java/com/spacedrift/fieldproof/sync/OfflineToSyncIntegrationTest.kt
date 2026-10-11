package com.spacedrift.fieldproof.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.data.local.FieldProofDatabase
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.repository.InspectionRepository
import com.spacedrift.fieldproof.data.repository.SyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end offline-to-sync test over a real Room database:
 * local persistence -> queued sync operations -> upload -> synced, including an
 * offline (retryable) phase and a permanent-failure/manual-retry recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineToSyncIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: FieldProofDatabase
    private lateinit var syncRepository: SyncRepository
    private lateinit var inspectionRepository: InspectionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FieldProofDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        syncRepository = SyncRepository(db.syncOperationDao())
        inspectionRepository = InspectionRepository(
            inspectionDao = db.inspectionDao(),
            checklistItemDao = db.checklistItemDao(),
            evidenceDao = db.evidenceDao(),
            syncRepository = syncRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runner(
        inspectionUploader: FakeInspectionUploader = FakeInspectionUploader(),
        evidenceUploader: FakeEvidenceUploader = FakeEvidenceUploader()
    ) = SyncRunner(
        syncRepository = syncRepository,
        inspectionDao = db.inspectionDao(),
        evidenceDao = db.evidenceDao(),
        inspectionUploader = inspectionUploader,
        uploader = evidenceUploader
    )

    @Test
    fun `capture offline then reconnect syncs inspection and evidence through room`() = runBlocking {
        // 1. Create locally: inspection + checklist + INSPECTION/CREATE op persist.
        val inspection = inspectionRepository.createInspection("Facility A", "Building 1")

        assertEquals(inspection.id, db.inspectionDao().getInspection(inspection.id)!!.id)
        assertEquals(3, db.checklistItemDao().observeChecklistItems(inspection.id).first().size)
        assertEquals("QUEUED", syncRepository.getInspectionOperation(inspection.id)!!.status)

        // 2. Capture evidence offline: evidence row + EVIDENCE/CREATE op persist.
        val requiredItem = db.checklistItemDao()
            .observeChecklistItems(inspection.id)
            .first()
            .first { it.required }
        inspectionRepository.addEvidence(
            inspectionId = inspection.id,
            checklistItemId = requiredItem.id,
            type = "PHOTO",
            localUri = "file:///photo.jpg",
            note = null
        )

        val evidence = db.evidenceDao().observeEvidence(inspection.id).first().single()
        assertEquals("QUEUED", evidence.syncStatus)
        assertTrue(syncRepository.getQueuedOperations().any { it.entityId == evidence.id })

        // 3. Still offline: a retryable inspection failure keeps everything queued.
        val offlineInspectionUploader = FakeInspectionUploader(
            mutableListOf(InspectionUploadResult.Failed(retryable = true, message = "offline"))
        )
        val offlineEvidenceUploader = FakeEvidenceUploader()

        val offline = runner(offlineInspectionUploader, offlineEvidenceUploader).run()

        assertEquals(SyncRunResult.Retry, offline)
        assertEquals("QUEUED", syncRepository.getInspectionOperation(inspection.id)!!.status)
        assertEquals("QUEUED", db.evidenceDao().getEvidence(evidence.id)!!.syncStatus)
        assertTrue(offlineEvidenceUploader.uploaded.isEmpty())

        // 4. Reconnect: inspection uploads first, then its evidence; both SYNCED in Room.
        val inspectionUploader = FakeInspectionUploader()
        val evidenceUploader = FakeEvidenceUploader()

        val online = runner(inspectionUploader, evidenceUploader).run()

        assertEquals(SyncRunResult.AllDone, online)
        assertEquals("SYNCED", syncRepository.getInspectionOperation(inspection.id)!!.status)
        assertEquals("SYNCED", db.evidenceDao().getEvidence(evidence.id)!!.syncStatus)
        assertEquals("SYNCED", db.evidenceDao().observeEvidence(inspection.id).first().single().syncStatus)

        // Deterministic idempotency keys: the local ids.
        assertEquals(listOf(inspection.id), inspectionUploader.uploaded.map { it.second })
        assertEquals(listOf(evidence.id), evidenceUploader.uploaded.map { it.second })
    }

    @Test
    fun `permanent failure recovers through room after a manual retry`() = runBlocking {
        val inspection = inspectionRepository.createInspection("Facility B", "Building 2")
        val requiredItem = db.checklistItemDao()
            .observeChecklistItems(inspection.id)
            .first()
            .first { it.required }
        inspectionRepository.addEvidence(
            inspectionId = inspection.id,
            checklistItemId = requiredItem.id,
            type = "PHOTO",
            localUri = "file:///photo.jpg",
            note = null
        )
        val evidence = db.evidenceDao().observeEvidence(inspection.id).first().single()

        // First run: inspection succeeds, evidence fails permanently.
        val failingEvidenceUploader = FakeEvidenceUploader(
            mutableListOf(EvidenceUploadResult.Failed(retryable = false, message = "rejected"))
        )
        val first = runner(FakeInspectionUploader(), failingEvidenceUploader).run()

        assertEquals(SyncRunResult.AllDone, first)
        assertEquals("SYNCED", syncRepository.getInspectionOperation(inspection.id)!!.status)
        assertEquals("FAILED", db.evidenceDao().getEvidence(evidence.id)!!.syncStatus)
        val evidenceOperation = db.syncOperationDao().observeOperations().first()
            .first { it.entityId == evidence.id }
        assertEquals("FAILED", evidenceOperation.status)

        // Manual retry requeues the failed op (attempts reset) and it now syncs.
        syncRepository.retryFailedOperations()

        val retryEvidenceUploader = FakeEvidenceUploader()
        val second = runner(FakeInspectionUploader(), retryEvidenceUploader).run()

        assertEquals(SyncRunResult.AllDone, second)
        assertEquals("SYNCED", db.evidenceDao().getEvidence(evidence.id)!!.syncStatus)
        assertEquals(listOf(evidence.id), retryEvidenceUploader.uploaded.map { it.second })
    }
}