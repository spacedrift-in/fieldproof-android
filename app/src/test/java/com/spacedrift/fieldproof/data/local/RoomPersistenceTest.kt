package com.spacedrift.fieldproof.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the real Room database (in-memory and on-disk) rather than DAO
 * mocks, covering persistence, reload, aggregate queries and the sync-operation
 * recovery queries used by the offline-to-sync path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: FieldProofDatabase

    private fun inspection(
        id: String = "i1",
        title: String = "Facility A",
        updatedAt: Long = 1_000L
    ) = InspectionEntity(
        id = id,
        title = title,
        location = "Building 1",
        status = "DRAFT",
        createdAt = 1_000L,
        updatedAt = updatedAt
    )

    private fun checklistItem(
        id: String,
        inspectionId: String = "i1",
        required: Boolean = true,
        completed: Boolean = false
    ) = ChecklistItemEntity(
        id = id,
        inspectionId = inspectionId,
        title = "Item $id",
        required = required,
        completed = completed
    )

    private fun evidence(
        id: String,
        inspectionId: String = "i1",
        syncStatus: String = "QUEUED",
        capturedAt: Long = 2_000L
    ) = EvidenceEntity(
        id = id,
        inspectionId = inspectionId,
        checklistItemId = null,
        type = "PHOTO",
        localUri = "file:///photo-$id.jpg",
        note = null,
        capturedAt = capturedAt,
        syncStatus = syncStatus
    )

    private fun syncOperation(
        id: String,
        entityId: String,
        entityType: String,
        status: String = "QUEUED",
        attempts: Int = 0,
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L
    ) = SyncOperationEntity(
        id = id,
        entityId = entityId,
        entityType = entityType,
        operationType = "CREATE",
        status = status,
        attempts = attempts,
        lastError = null,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FieldProofDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------------
    // Persistence / reload
    // ---------------------------------------------------------------------

    @Test
    fun `inspection is persisted and read back`() = runBlocking {
        db.inspectionDao().insertInspection(inspection())

        val reloaded = db.inspectionDao().getInspection("i1")

        assertNotNull(reloaded)
        assertEquals("Facility A", reloaded!!.title)
        assertEquals("Building 1", reloaded.location)
        assertEquals("DRAFT", reloaded.status)
    }

    @Test
    fun `inspection survives closing and reopening the database file`() = runBlocking {
        val name = "fieldproof-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(name)

        val first = Room.databaseBuilder(context, FieldProofDatabase::class.java, name).build()
        first.inspectionDao().insertInspection(inspection(title = "Persisted Facility"))
        first.close()

        val second = Room.databaseBuilder(context, FieldProofDatabase::class.java, name).build()
        val reloaded = second.inspectionDao().getInspection("i1")
        second.close()
        context.deleteDatabase(name)

        assertNotNull(reloaded)
        assertEquals("Persisted Facility", reloaded!!.title)
    }

    @Test
    fun `reinserting the same inspection id replaces the row`() = runBlocking {
        db.inspectionDao().insertInspection(inspection(title = "First"))
        db.inspectionDao().insertInspection(inspection(title = "Updated"))

        val rows = db.inspectionDao().observeInspections().first()

        assertEquals(1, rows.size)
        assertEquals("Updated", rows.single().title)
    }

    @Test
    fun `observeInspections emits inserted rows ordered by updatedAt`() = runBlocking {
        db.inspectionDao().insertInspection(inspection(id = "old", updatedAt = 100L))
        db.inspectionDao().insertInspection(inspection(id = "new", updatedAt = 500L))

        val ids = db.inspectionDao().observeInspections().first().map { it.id }

        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun `summary query aggregates checklist and evidence counts`() = runBlocking {
        db.inspectionDao().insertInspection(inspection())
        db.checklistItemDao().insertChecklistItems(
            listOf(
                checklistItem("c1", completed = true),
                checklistItem("c2", completed = false),
                checklistItem("c3", completed = false)
            )
        )
        db.evidenceDao().insertEvidence(evidence("e1"))
        db.evidenceDao().insertEvidence(evidence("e2"))

        val summary = db.inspectionDao().observeInspectionSummaries().first().single()

        assertEquals("i1", summary.id)
        assertEquals(3, summary.totalItems)
        assertEquals(1, summary.completedItems)
        assertEquals(2, summary.evidenceCount)
    }

    // ---------------------------------------------------------------------
    // Checklist + evidence persistence
    // ---------------------------------------------------------------------

    @Test
    fun `checklist completion is persisted`() = runBlocking {
        db.inspectionDao().insertInspection(inspection())
        db.checklistItemDao().insertChecklistItem(checklistItem("c1"))

        db.checklistItemDao().setCompleted("c1", true)

        val item = db.checklistItemDao().observeChecklistItems("i1").first().single()
        assertEquals(true, item.completed)
    }

    @Test
    fun `evidence sync status update is persisted`() = runBlocking {
        db.evidenceDao().insertEvidence(evidence("e1", syncStatus = "QUEUED"))

        db.evidenceDao().updateSyncStatus("e1", "SYNCED")

        assertEquals("SYNCED", db.evidenceDao().getEvidence("e1")!!.syncStatus)
    }

    @Test
    fun `deleteForInspection removes only that inspection's children`() = runBlocking {
        db.inspectionDao().insertInspection(inspection(id = "i1"))
        db.inspectionDao().insertInspection(inspection(id = "i2"))
        db.checklistItemDao().insertChecklistItems(
            listOf(checklistItem("c1", inspectionId = "i1"), checklistItem("c2", inspectionId = "i2"))
        )
        db.evidenceDao().insertEvidence(evidence("e1", inspectionId = "i1"))
        db.evidenceDao().insertEvidence(evidence("e2", inspectionId = "i2"))

        db.checklistItemDao().deleteForInspection("i1")
        db.evidenceDao().deleteForInspection("i1")

        assertEquals(0, db.checklistItemDao().observeChecklistItems("i1").first().size)
        assertEquals(1, db.checklistItemDao().observeChecklistItems("i2").first().size)
        assertNull(db.evidenceDao().getEvidence("e1"))
        assertNotNull(db.evidenceDao().getEvidence("e2"))
    }

    // ---------------------------------------------------------------------
    // Sync-operation queries (offline-to-sync recovery)
    // ---------------------------------------------------------------------

    @Test
    fun `queued operations are returned in creation order`() = runBlocking {
        db.syncOperationDao().insert(syncOperation("op2", "e2", "EVIDENCE", createdAt = 200L))
        db.syncOperationDao().insert(syncOperation("op1", "e1", "EVIDENCE", createdAt = 100L))
        db.syncOperationDao().insert(syncOperation("op3", "e3", "EVIDENCE", status = "SYNCED", createdAt = 50L))

        val queued = db.syncOperationDao().getQueuedOperations()

        assertEquals(listOf("op1", "op2"), queued.map { it.id })
    }

    @Test
    fun `getInspectionOperation returns the latest inspection operation`() = runBlocking {
        db.syncOperationDao().insert(
            syncOperation("op-old", "i1", "INSPECTION", createdAt = 100L)
        )
        db.syncOperationDao().insert(
            syncOperation("op-new", "i1", "INSPECTION", createdAt = 300L)
        )
        db.syncOperationDao().insert(
            syncOperation("op-evidence", "e1", "EVIDENCE", createdAt = 500L)
        )

        val operation = db.syncOperationDao().getInspectionOperation("i1")

        assertEquals("op-new", operation!!.id)
    }

    @Test
    fun `recoverStaleUploads requeues only old uploading operations`() = runBlocking {
        db.syncOperationDao().insert(
            syncOperation("stale", "e1", "EVIDENCE", status = "UPLOADING", updatedAt = 1_000L)
        )
        db.syncOperationDao().insert(
            syncOperation("active", "e2", "EVIDENCE", status = "UPLOADING", updatedAt = 9_000L)
        )

        db.syncOperationDao().recoverStaleUploads(updatedAt = 10_000L, staleBefore = 5_000L)

        val all = db.syncOperationDao().observeOperations().first().associateBy { it.id }
        assertEquals("QUEUED", all.getValue("stale").status)
        assertEquals("UPLOADING", all.getValue("active").status)
    }

    @Test
    fun `requeueFailed resets attempts and error`() = runBlocking {
        db.syncOperationDao().insert(
            syncOperation("failed", "e1", "EVIDENCE", status = "FAILED", attempts = 4)
        )

        db.syncOperationDao().requeueFailed(updatedAt = 5_000L)

        val requeued = db.syncOperationDao().getQueuedOperations().single()
        assertEquals("failed", requeued.id)
        assertEquals(0, requeued.attempts)
        assertNull(requeued.lastError)
    }
}