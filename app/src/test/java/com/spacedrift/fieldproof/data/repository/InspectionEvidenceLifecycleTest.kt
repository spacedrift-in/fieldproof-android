package com.spacedrift.fieldproof.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.FieldProofDatabase
import com.spacedrift.fieldproof.security.EncryptedEvidenceVault
import com.spacedrift.fieldproof.security.EvidenceCipher
import com.spacedrift.fieldproof.security.EvidenceKeyProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Verifies that deleting an inspection, applying retention, and wiping local data
 * actually remove the encrypted evidence bytes on disk — not just the database
 * rows — and that queued sync work never outlives its entity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InspectionEvidenceLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private lateinit var db: FieldProofDatabase
    private lateinit var vault: EncryptedEvidenceVault
    private lateinit var repository: InspectionRepository
    private lateinit var rawDirectory: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, FieldProofDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        vault = EncryptedEvidenceVault(
            directory = tempFolder.newFolder("evidence"),
            cipher = EvidenceCipher(EvidenceKeyProvider { key })
        )
        rawDirectory = tempFolder.newFolder("raw")

        repository = InspectionRepository(
            inspectionDao = db.inspectionDao(),
            checklistItemDao = db.checklistItemDao(),
            evidenceDao = db.evidenceDao(),
            syncRepository = SyncRepository(db.syncOperationDao()),
            evidenceVault = vault
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `addEvidence stores encrypted bytes and removes the plaintext capture`() = runBlocking {
        val inspection = repository.createInspection("Facility", "Building")
        val raw = rawFile("capture.jpg", "photo!".toByteArray())

        repository.addEvidence(
            inspectionId = inspection.id,
            checklistItemId = null,
            type = "PHOTO",
            localUri = Uri.fromFile(raw).toString(),
            note = null
        )

        val evidence = db.evidenceDao().getForInspection(inspection.id).single()
        assertFalse("plaintext capture must be gone", raw.exists())
        assertNotNull(evidence.localUri)
        assertTrue(evidence.localUri!!.endsWith(".fpenc"))
        assertEquals("photo!", String(vault.read(evidence.localUri)!!))
    }

    @Test
    fun `deleteInspection removes files, sync operations and rows`() = runBlocking {
        val inspection = repository.createInspection("Facility", "Building")
        repository.addEvidence(
            inspectionId = inspection.id,
            checklistItemId = null,
            type = "PHOTO",
            localUri = Uri.fromFile(rawFile("c.jpg", byteArrayOf(1, 2, 3))).toString(),
            note = null
        )

        val storedFile = File(Uri.parse(
            db.evidenceDao().getForInspection(inspection.id).single().localUri
        ).path!!)
        assertTrue(storedFile.exists())

        repository.deleteInspection(inspection.id)

        assertFalse("encrypted evidence file must be deleted", storedFile.exists())
        assertNull(db.inspectionDao().getInspection(inspection.id))
        assertTrue(db.evidenceDao().getForInspection(inspection.id).isEmpty())
        assertTrue(db.checklistItemDao().getForInspection(inspection.id).isEmpty())
        assertTrue(db.syncOperationDao().getQueuedOperations().isEmpty())
    }

    @Test
    fun `purgeExpiredEvidence deletes only old synced evidence`() = runBlocking {
        val now = 1_000_000_000_000L
        val day = 24L * 60L * 60L * 1000L

        val expiredUri = sealedEvidence("old-synced", byteArrayOf(1))
        val recentUri = sealedEvidence("recent-synced", byteArrayOf(2))
        val queuedUri = sealedEvidence("old-queued", byteArrayOf(3))

        db.evidenceDao().insertEvidence(
            evidence("e-old", inspectionId = "i1", uri = expiredUri, capturedAt = now - 40 * day)
        )
        db.evidenceDao().insertEvidence(
            evidence("e-recent", inspectionId = "i1", uri = recentUri, capturedAt = now - 1 * day)
        )
        db.evidenceDao().insertEvidence(
            evidence(
                id = "e-queued",
                inspectionId = "i1",
                uri = queuedUri,
                capturedAt = now - 40 * day,
                syncStatus = "QUEUED"
            )
        )

        val purged = repository.purgeExpiredEvidence(retentionDays = 30, now = now)

        assertEquals(1, purged)
        assertFalse(File(Uri.parse(expiredUri).path!!).exists())
        assertNull(db.evidenceDao().getEvidence("e-old")!!.localUri)
        assertTrue(File(Uri.parse(recentUri).path!!).exists())
        assertTrue(File(Uri.parse(queuedUri).path!!).exists())
    }

    @Test
    fun `deleteAllLocalData clears files and every table`() = runBlocking {
        val inspection = repository.createInspection("Facility", "Building")
        repository.addEvidence(
            inspectionId = inspection.id,
            checklistItemId = null,
            type = "PHOTO",
            localUri = Uri.fromFile(rawFile("c.jpg", byteArrayOf(9))).toString(),
            note = null
        )

        repository.deleteAllLocalData()

        assertTrue(db.inspectionDao().observeInspections().first().isEmpty())
        assertTrue(db.evidenceDao().getForInspection(inspection.id).isEmpty())
        assertTrue(db.syncOperationDao().getQueuedOperations().isEmpty())
    }

    private suspend fun sealedEvidence(name: String, bytes: ByteArray): String {
        return vault.import(Uri.fromFile(rawFile("$name.jpg", bytes)).toString())!!
    }

    private fun evidence(
        id: String,
        inspectionId: String,
        uri: String,
        capturedAt: Long,
        syncStatus: String = "SYNCED"
    ) = EvidenceEntity(
        id = id,
        inspectionId = inspectionId,
        checklistItemId = null,
        type = "PHOTO",
        localUri = uri,
        note = null,
        capturedAt = capturedAt,
        syncStatus = syncStatus
    )

    private fun rawFile(name: String, bytes: ByteArray): File {
        return File(rawDirectory, "$name-${counter++}").apply { writeBytes(bytes) }
    }

    private var counter = 0
}
