package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.FakeEvidenceFileReader
import com.spacedrift.fieldproof.FakeFieldProofApi
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.httpException
import com.spacedrift.fieldproof.inspectionEntity
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests proving the Android client maps the backend's real HTTP 409
 * bodies onto the [ServerConflict] domain model without discarding anything.
 *
 * The bodies are copied from the backend implementation; see
 * `fieldproof-starter/backend/app/service.py`, `routers/inspections.py` and
 * `docs/api-contract.md`.
 */
class RemoteRepositoryConflictContractTest {

    private val api = FakeFieldProofApi()

    private fun repository() = RemoteRepository(
        api = api,
        fileReader = FakeEvidenceFileReader()
    )

    private val versionConflictBody =
        """{"detail":{"message":"Version conflict: the inspection changed on the server.","current_version":7,"current_status":"SUBMITTED"}}"""

    private val missingEvidenceBody =
        """{"detail":{"message":"Missing required evidence for submission.","missing_checklist_item_ids":["a","b"]}}"""

    private val idempotencyBody =
        """{"detail":"Idempotency-Key was already used with different evidence content."}"""

    private val inspectionIdCollisionBody =
        """{"detail":"An inspection with this id already exists with different attributes."}"""

    @Test
    fun `evidence 409 preserves the backend detail as a typed conflict`() = runBlocking {
        api.uploadException = httpException(409, idempotencyBody)
        val evidence = evidenceEntity(id = "e1", type = "NOTE", localUri = null, note = "keep me")

        val result = repository().uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Conflict)
        val conflict = (result as EvidenceUploadResult.Conflict).serverConflict
        assertTrue(conflict is ServerConflict.Rejected)
        assertEquals(
            "Idempotency-Key was already used with different evidence content.",
            conflict.message
        )
        assertEquals(idempotencyBody, conflict.rawDetail)
    }

    @Test
    fun `inspection 409 preserves the backend detail as a typed conflict`() = runBlocking {
        api.createException = httpException(409, inspectionIdCollisionBody)

        val result = repository().uploadInspection(
            inspection = inspectionEntity(id = "i1"),
            idempotencyKey = "i1"
        )

        assertTrue(result is InspectionUploadResult.Conflict)
        val conflict = (result as InspectionUploadResult.Conflict).serverConflict
        assertEquals(
            "An inspection with this id already exists with different attributes.",
            conflict.message
        )
        assertEquals(inspectionIdCollisionBody, conflict.rawDetail)
    }

    @Test
    fun `workflow version conflict maps structured fields and preserves the payload`() =
        runBlocking {
            api.workflowException = httpException(409, versionConflictBody)

            val result = repository().submitInspection("i1", expectedVersion = 3)

            assertTrue(result is WorkflowResult.Conflict)
            val conflict = (result as WorkflowResult.Conflict).serverConflict
            assertTrue(conflict is ServerConflict.Version)
            conflict as ServerConflict.Version
            assertEquals(7, conflict.currentVersion)
            assertEquals("SUBMITTED", conflict.currentStatus)
            assertEquals(versionConflictBody, conflict.rawDetail)
            // The convenience accessor still exposes the server message.
            assertEquals(result.message, conflict.message)
        }

    @Test
    fun `workflow missing-evidence conflict maps its ids and preserves the payload`() =
        runBlocking {
            api.workflowException = httpException(409, missingEvidenceBody)

            val result = repository().submitInspection("i1", expectedVersion = null)

            assertTrue(result is WorkflowResult.Conflict)
            val conflict = (result as WorkflowResult.Conflict).serverConflict
            assertTrue(conflict is ServerConflict.MissingRequiredEvidence)
            assertEquals(listOf("a", "b"), (conflict as ServerConflict.MissingRequiredEvidence).missingChecklistItemIds)
            assertEquals(missingEvidenceBody, conflict.rawDetail)
        }
}
