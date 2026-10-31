package com.spacedrift.fieldproof.ui.components

import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.inspectionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictDescriptionTest {

    private fun conflict(
        entityId: String,
        entityType: String,
        lastError: String? = "Server rejected the change.",
        conflictDetail: String? = null
    ) = SyncOperationEntity(
        id = "op-$entityId",
        entityId = entityId,
        entityType = entityType,
        operationType = "CREATE",
        status = "CONFLICT",
        attempts = 1,
        lastError = lastError,
        conflictDetail = conflictDetail,
        createdAt = 1000L,
        updatedAt = 2000L
    )

    @Test
    fun `version conflict payload is parsed into a server detail line`() {
        val body = """{"detail":{"message":"Version conflict","current_version":4,"current_status":"SUBMITTED"}}"""
        val presentation = describeConflict(
            operation = conflict("i1", "INSPECTION", lastError = "Version conflict", conflictDetail = body),
            evidence = emptyList(),
            inspection = inspectionEntity(id = "i1")
        )

        assertEquals("Server is at version 4 (SUBMITTED).", presentation.serverDetail)
        assertTrue(presentation.serverConflict is ServerConflict.Version)
    }

    @Test
    fun `missing required evidence payload is summarised`() {
        val body = """{"detail":{"message":"Missing required evidence","missing_checklist_item_ids":["a","b","c"]}}"""
        val presentation = describeConflict(
            operation = conflict("e1", "EVIDENCE", conflictDetail = body),
            evidence = emptyList(),
            inspection = null
        )

        assertEquals("Missing required evidence for 3 item(s).", presentation.serverDetail)
    }

    @Test
    fun `evidence conflict surfaces the local note`() {
        val presentation = describeConflict(
            operation = conflict("e1", "EVIDENCE"),
            evidence = listOf(
                evidenceEntity(
                    id = "e1",
                    type = "NOTE",
                    localUri = null,
                    note = "Retest <valve>"
                )
            ),
            inspection = null
        )

        assertEquals("Note evidence", presentation.title)
        assertEquals("EVIDENCE", presentation.entityType)
        assertEquals("Local note: Retest <valve>", presentation.localSummary)
        assertEquals(2000L, presentation.updatedAt)
    }

    @Test
    fun `inspection conflict names the inspection and keeps its local copy`() {
        val presentation = describeConflict(
            operation = conflict("i1", "INSPECTION"),
            evidence = emptyList(),
            inspection = inspectionEntity(id = "i1", title = "Facility A", location = "Plant 7")
        )

        assertTrue(presentation.title.contains("Facility A"))
        assertTrue(presentation.detail.contains("server"))
        assertEquals("Local: Facility A · Plant 7", presentation.localSummary)
    }

    @Test
    fun `evidence conflict without a local row still yields a usable description`() {
        val presentation = describeConflict(
            operation = conflict("missing", "EVIDENCE"),
            evidence = emptyList(),
            inspection = null
        )

        assertEquals("Evidence", presentation.title)
        assertEquals(null, presentation.localSummary)
    }
}
