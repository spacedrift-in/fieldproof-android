package com.spacedrift.fieldproof.ui.inspection

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spacedrift.fieldproof.FakeInspectionWorkflowClient
import com.spacedrift.fieldproof.FakeSyncScheduler
import com.spacedrift.fieldproof.data.connectivity.ConnectivityObserver
import com.spacedrift.fieldproof.data.local.FieldProofDatabase
import com.spacedrift.fieldproof.data.remote.RemoteSubmitResponse
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.data.repository.ConflictResolver
import com.spacedrift.fieldproof.data.repository.InspectionRepository
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.missingEvidenceConflict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies that a server-side 409 "missing required evidence" response is
 * surfaced at field level (per checklist item id) so the Android finalization
 * flow is blocked by the server's own validation, not just local checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectionViewModelSubmitValidationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: FieldProofDatabase
    private lateinit var workflowClient: FakeInspectionWorkflowClient
    private lateinit var viewModel: InspectionViewModel

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FieldProofDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val syncRepository = SyncRepository(db.syncOperationDao())
        val inspectionRepository = InspectionRepository(
            inspectionDao = db.inspectionDao(),
            checklistItemDao = db.checklistItemDao(),
            evidenceDao = db.evidenceDao(),
            syncRepository = syncRepository
        )
        val conflictResolver = ConflictResolver(
            syncRepository = syncRepository,
            evidenceDao = db.evidenceDao(),
            checklistItemDao = db.checklistItemDao()
        )
        workflowClient = FakeInspectionWorkflowClient()

        viewModel = InspectionViewModel(
            repository = inspectionRepository,
            syncRepository = syncRepository,
            conflictResolver = conflictResolver,
            workflowClient = workflowClient,
            syncScheduler = FakeSyncScheduler(),
            connectivity = ConnectivityObserver(context)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `submit blocked by server surfaces missing evidence at field level`() {
        workflowClient.submitResult = WorkflowResult.Conflict(
            missingEvidenceConflict(missingIds = listOf("item-a", "item-b"))
        )

        viewModel.submitInspection("inspection-1")
        waitUntil { !viewModel.workflow.value.busy && viewModel.workflow.value.message != null }

        val state = viewModel.workflow.value
        assertEquals(setOf("item-a", "item-b"), state.missingEvidenceItemIds)
        assertFalse(state.busy)

        // The submit actually reached the server endpoint via the client.
        assertEquals(listOf("inspection-1" to 1), workflowClient.submits)
    }

    @Test
    fun `successful submit clears any field-level server flags`() {
        workflowClient.submitResult = WorkflowResult.Conflict(
            missingEvidenceConflict(missingIds = listOf("item-a"))
        )
        viewModel.submitInspection("inspection-1")
        waitUntil { viewModel.workflow.value.missingEvidenceItemIds.isNotEmpty() }
        assertEquals(setOf("item-a"), viewModel.workflow.value.missingEvidenceItemIds)

        workflowClient.submitResult =
            WorkflowResult.Success(RemoteSubmitResponse("inspection-1", "SUBMITTED", 2))
        viewModel.submitInspection("inspection-1")
        waitUntil { viewModel.workflow.value.missingEvidenceItemIds.isEmpty() }

        assertTrue(viewModel.workflow.value.missingEvidenceItemIds.isEmpty())
    }

    @Test
    fun `adding evidence clears the flag for that item`() {
        workflowClient.submitResult = WorkflowResult.Conflict(
            missingEvidenceConflict(missingIds = listOf("item-a", "item-b"))
        )
        viewModel.submitInspection("inspection-1")
        waitUntil { viewModel.workflow.value.missingEvidenceItemIds.size == 2 }

        viewModel.addEvidence(
            inspectionId = "inspection-1",
            checklistItemId = "item-a",
            type = "PHOTO",
            localUri = "file:///tmp/FIELDPROOF_1.jpg",
            note = null
        )
        waitUntil { viewModel.workflow.value.missingEvidenceItemIds == setOf("item-b") }

        assertEquals(setOf("item-b"), viewModel.workflow.value.missingEvidenceItemIds)
    }

    private fun waitUntil(
        timeoutMs: Long = 3_000,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
