package com.spacedrift.fieldproof.ui.components

import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM coverage for the sync-state presentation helpers. These are what the
 * device tests render, so they lock the exact strings/tone for every state the
 * acceptance criteria call out: queued, uploading, synced, failed, conflict.
 */
class SyncStatusDisplayTest {

    @Test
    fun `each sync status maps to a distinct label`() {
        assertEquals("Queued", syncStatusLabel("QUEUED"))
        assertEquals("Uploading", syncStatusLabel("UPLOADING"))
        assertEquals("Synced", syncStatusLabel("SYNCED"))
        assertEquals("Failed", syncStatusLabel("FAILED"))
        assertEquals("Conflict", syncStatusLabel("CONFLICT"))
    }

    @Test
    fun `queued and uploading are labelled differently`() {
        assertEquals("Queued", syncStatusLabel("QUEUED"))
        assertEquals("Uploading", syncStatusLabel("UPLOADING"))
        org.junit.Assert.assertNotEquals(
            syncStatusLabel("QUEUED"),
            syncStatusLabel("UPLOADING")
        )
    }

    @Test
    fun `status tones group states for the UI`() {
        assertEquals(StatusTone.WARNING, statusTone("QUEUED"))
        assertEquals(StatusTone.WARNING, statusTone("UPLOADING"))
        assertEquals(StatusTone.SUCCESS, statusTone("SYNCED"))
        assertEquals(StatusTone.ERROR, statusTone("FAILED"))
        assertEquals(StatusTone.ERROR, statusTone("CONFLICT"))
    }

    private fun operation(status: String) = SyncOperationEntity(
        id = "op-$status",
        entityId = "e1",
        entityType = "EVIDENCE",
        operationType = "CREATE",
        status = status,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    @Test
    fun `queued and uploading operations count as pending`() {
        val summary = deriveSyncSummary(
            operations = listOf(operation("QUEUED"), operation("UPLOADING")),
            online = true
        )
        assertEquals(SyncState.SYNCING, summary.state)
        assertEquals(2, summary.pending)
    }

    @Test
    fun `a failed operation raises attention while a conflict is listed separately`() {
        val failed = deriveSyncSummary(listOf(operation("FAILED")), online = true)
        assertEquals(SyncState.ATTENTION, failed.state)
        assertEquals(1, failed.failed)
        assertEquals(0, failed.conflict)

        val conflict = deriveSyncSummary(listOf(operation("CONFLICT")), online = true)
        assertEquals(SyncState.ATTENTION, conflict.state)
        assertEquals(1, conflict.conflict)
        assertEquals(0, conflict.failed)
    }

    @Test
    fun `only synced operations report a clean sync`() {
        val summary = deriveSyncSummary(listOf(operation("SYNCED")), online = true)
        assertEquals(SyncState.SYNCED, summary.state)
    }
}
