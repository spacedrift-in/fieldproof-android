package com.spacedrift.fieldproof.sync

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.spacedrift.fieldproof.FakeChecklistItemDao
import com.spacedrift.fieldproof.FakeChecklistOutcomeUploader
import com.spacedrift.fieldproof.FakeEvidenceDao
import com.spacedrift.fieldproof.FakeEvidenceUploader
import com.spacedrift.fieldproof.FakeInspectionDao
import com.spacedrift.fieldproof.FakeInspectionUploader
import com.spacedrift.fieldproof.FakeSyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.evidenceEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Proves the connected-network scheduling contract: a queued evidence upload is
 * handed to a *unique* piece of WorkManager work that does not run while the
 * device is offline, and executes as soon as the network constraint is met.
 *
 * The worker is built with the real [SyncRunner] over in-memory fakes, so the sync
 * pipeline (inspection gate -> evidence upload -> SYNCED) actually runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SyncSchedulerWorkManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var operations: FakeSyncOperationDao
    private lateinit var evidence: FakeEvidenceDao
    private lateinit var evidenceUploader: FakeEvidenceUploader
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        operations = FakeSyncOperationDao()
        evidence = FakeEvidenceDao()
        evidenceUploader = FakeEvidenceUploader()

        val runner = SyncRunner(
            syncRepository = SyncRepository(operations),
            inspectionDao = FakeInspectionDao(),
            evidenceDao = evidence,
            checklistItemDao = FakeChecklistItemDao(),
            inspectionUploader = FakeInspectionUploader(),
            uploader = evidenceUploader,
            checklistOutcomeUploader = FakeChecklistOutcomeUploader()
        )

        val configuration = Configuration.Builder()
            .setWorkerFactory(FakeSyncWorkerFactory(runner))
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `queued evidence upload executes after connectivity returns`() = runBlocking {
        val inspectionId = UUID.randomUUID().toString()
        val evidenceId = UUID.randomUUID().toString()

        evidence.insertEvidence(
            evidenceEntity(id = evidenceId, inspectionId = inspectionId)
        )
        // The inspection already synced, so only the evidence upload is pending.
        operations.insert(inspectionOperation(inspectionId, status = "SYNCED"))
        operations.insert(evidenceOperation(evidenceId))

        WorkManagerSyncScheduler(context).enqueueSync()

        val workId = uniqueWorkId()

        // Offline: the CONNECTED constraint keeps the worker enqueued and idle.
        assertEquals(WorkInfo.State.ENQUEUED, workInfo(workId).state)
        assertTrue(evidenceUploader.uploaded.isEmpty())

        // Connectivity returns -> WorkManager dispatches the queued work.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(workId)

        awaitState(workId, WorkInfo.State.SUCCEEDED)

        // The evidence upload ran, reusing the durable entity id as its key.
        assertEquals(listOf(evidenceId), evidenceUploader.uploaded.map { it.second })
        assertEquals("SYNCED", evidence.get(evidenceId)!!.syncStatus)
        assertEquals(
            "SYNCED",
            operations.findByEntityId(evidenceId)!!.status
        )
    }

    @Test
    fun `sync work is unique and requires a connected network`() = runBlocking {
        WorkManagerSyncScheduler(context).enqueueSync()
        WorkManagerSyncScheduler(context).enqueueSync()

        val infos = workManager
            .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME)
            .get()

        assertTrue("expected pending sync work", infos.isNotEmpty())
        assertTrue(
            "every spec must require a connected network",
            infos.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED }
        )
    }

    private fun uniqueWorkId(): UUID {
        return workManager
            .getWorkInfosForUniqueWork(WorkManagerSyncScheduler.WORK_NAME)
            .get()
            .single()
            .id
    }

    private fun workInfo(id: UUID): WorkInfo {
        shadowOf(Looper.getMainLooper()).idle()
        return workManager.getWorkInfoById(id).get()!!
    }

    private fun awaitState(
        id: UUID,
        expected: WorkInfo.State,
        timeoutMs: Long = 5_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (workManager.getWorkInfoById(id).get()?.state == expected) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, workManager.getWorkInfoById(id).get()?.state)
    }

    private fun inspectionOperation(
        inspectionId: String,
        status: String
    ) = SyncOperationEntity(
        id = UUID.randomUUID().toString(),
        entityId = inspectionId,
        entityType = "INSPECTION",
        operationType = "CREATE",
        status = status,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun evidenceOperation(evidenceId: String) = SyncOperationEntity(
        id = UUID.randomUUID().toString(),
        entityId = evidenceId,
        entityType = "EVIDENCE",
        operationType = "CREATE",
        status = "QUEUED",
        createdAt = 2L,
        updatedAt = 2L
    )

    private class FakeSyncWorkerFactory(
        private val runner: SyncRunner
    ) : WorkerFactory() {

        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker? {
            return if (workerClassName == SyncWorker::class.java.name) {
                SyncWorker(appContext, workerParameters, runner)
            } else {
                null
            }
        }
    }
}
