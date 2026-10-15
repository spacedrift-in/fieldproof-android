package com.spacedrift.fieldproof.domain.conflict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the mapping from the backend's HTTP 409 wire format to the
 * stable [ServerConflict] domain model.
 *
 * The JSON bodies below are the exact shapes produced by the FastAPI backend
 * (`fieldproof-starter/backend/app/service.py` and `routers/inspections.py`).
 * If the backend changes these shapes, these tests should fail — that is the
 * point of the contract.
 */
class ServerConflictParserTest {

    @Test
    fun `structured version conflict maps to Version and preserves the payload`() {
        val body = """
            {"detail":{"message":"Version conflict: the inspection changed on the server.","current_version":1,"current_status":"DRAFT"}}
        """.trimIndent()

        val conflict = ServerConflictParser.parse(body)

        assertTrue(conflict is ServerConflict.Version)
        conflict as ServerConflict.Version
        assertEquals("Version conflict: the inspection changed on the server.", conflict.message)
        assertEquals(1, conflict.currentVersion)
        assertEquals("DRAFT", conflict.currentStatus)
        assertEquals(body, conflict.rawDetail)
    }

    @Test
    fun `structured missing-evidence conflict maps to MissingRequiredEvidence`() {
        val body = """
            {"detail":{"message":"Missing required evidence for submission.","missing_checklist_item_ids":["11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222"]}}
        """.trimIndent()

        val conflict = ServerConflictParser.parse(body)

        assertTrue(conflict is ServerConflict.MissingRequiredEvidence)
        conflict as ServerConflict.MissingRequiredEvidence
        assertEquals(
            listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222"
            ),
            conflict.missingChecklistItemIds
        )
        assertEquals(body, conflict.rawDetail)
    }

    @Test
    fun `plain string conflicts are preserved verbatim`() {
        val messages = listOf(
            "Cannot attach evidence while the inspection is APPROVED.",
            "Evidence id already exists with different content",
            "Cannot submit an inspection in status SUBMITTED.",
            "Cannot review an inspection in status DRAFT; expected SUBMITTED.",
            "Report is only available after approval; current status is DRAFT.",
            "Idempotency-Key was already used with a different request body.",
            "An inspection with this id already exists with different attributes.",
            "Idempotency-Key was already used with different evidence content."
        )

        messages.forEach { message ->
            val body = """{"detail":"$message"}"""
            val conflict = ServerConflictParser.parse(body)

            assertTrue(conflict is ServerConflict.Rejected)
            conflict as ServerConflict.Rejected
            assertEquals(message, conflict.message)
            assertEquals(body, conflict.rawDetail)
        }
    }

    @Test
    fun `empty or null body yields an Unrecognized conflict with a fallback message`() {
        val fromNull = ServerConflictParser.parse(null)
        val fromEmpty = ServerConflictParser.parse("   ")

        assertEquals(ServerConflictParser.FALLBACK_MESSAGE, fromNull.message)
        assertTrue(fromNull is ServerConflict.Unrecognized)
        assertTrue(fromEmpty is ServerConflict.Unrecognized)
    }

    @Test
    fun `non json body is preserved as raw text`() {
        val conflict = ServerConflictParser.parse("upstream timed out")

        assertTrue(conflict is ServerConflict.Unrecognized)
        assertEquals("upstream timed out", conflict.message)
        assertEquals("upstream timed out", conflict.rawDetail)
    }

    @Test
    fun `malformed json is preserved rather than dropped`() {
        val body = """{"detail": """
        val conflict = ServerConflictParser.parse(body)

        assertTrue(conflict is ServerConflict.Unrecognized)
        assertEquals(body, conflict.rawDetail)
    }

    @Test
    fun `body without a detail key is Unrecognized but keeps the raw payload`() {
        val body = """{"error":"boom"}"""
        val conflict = ServerConflictParser.parse(body)

        assertTrue(conflict is ServerConflict.Unrecognized)
        assertEquals(body, conflict.rawDetail)
    }

    @Test
    fun `structured detail without a known shape becomes Rejected with its message`() {
        val body = """{"detail":{"message":"Something else went wrong"}}"""
        val conflict = ServerConflictParser.parse(body)

        assertTrue(conflict is ServerConflict.Rejected)
        assertEquals("Something else went wrong", conflict.message)
        assertEquals(body, conflict.rawDetail)
    }
}
