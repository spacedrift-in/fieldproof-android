package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.FakeEvidenceFileReader
import com.spacedrift.fieldproof.FakeFieldProofApi
import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.httpException
import com.spacedrift.fieldproof.readPartValues
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRepositoryTest {

    private val api = FakeFieldProofApi()

    @Test
    fun `photo upload sends parts and returns success`() = runBlocking {
        val photoBytes = byteArrayOf(10, 20, 30)
        val evidence = evidenceEntity(
            id = "e1",
            inspectionId = "i1",
            type = "PHOTO",
            localUri = "file:///tmp/photo.jpg",
            capturedAt = 500L
        )
        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(
                mapOf("file:///tmp/photo.jpg" to photoBytes)
            )
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertEquals(EvidenceUploadResult.Success, result)
        assertEquals("i1", api.uploadInspectionId)
        assertEquals("e1", api.uploadIdempotencyKey)

        val values = readPartValues(api.uploadParts)
        assertEquals("e1", partValue(values, "id"))
        assertEquals("photo", partValue(values, "kind"))
        assertEquals("500", partValue(values, "captured_at"))
        assertTrue(api.uploadParts.any { part ->
            part.headers?.get("Content-Disposition")
                ?.substringAfter("name=\"")
                ?.substringBefore("\"") == "file"
        })
    }

    @Test
    fun `photo with no local uri is a missing-file failure and never calls api`() =
        runBlocking {
            val evidence = evidenceEntity(
                id = "e1",
                inspectionId = "i1",
                type = "PHOTO",
                localUri = null
            )
            val repository = RemoteRepository(
                api = api,
                fileReader = FakeEvidenceFileReader()
            )

            val result = repository.uploadEvidence(evidence, evidence.id)

            assertTrue(result is EvidenceUploadResult.Failed)
            assertFalse((result as EvidenceUploadResult.Failed).retryable)
            assertTrue(result.message.contains("missing"))
            assertEquals(0, api.attempts)
        }

    @Test
    fun `photo with unreadable file is a missing-file failure`() = runBlocking {
        val evidence = evidenceEntity(
            id = "e1",
            inspectionId = "i1",
            type = "PHOTO",
            localUri = "file:///gone.jpg"
        )
        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(mapOf()) // read returns null
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Failed)
        assertEquals(0, api.attempts)
    }

    @Test
    fun `note upload sends no file part`() = runBlocking {
        val evidence = evidenceEntity(
            id = "e2",
            inspectionId = "i1",
            type = "NOTE",
            localUri = null,
            note = "Handrail loose"
        )
        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader()
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertEquals(EvidenceUploadResult.Success, result)
        assertEquals("e2", api.uploadIdempotencyKey)
        val values = readPartValues(api.uploadParts)
        assertEquals("note", partValue(values, "kind"))
        assertEquals("Handrail loose", partValue(values, "note"))
        assertFalse(values.any { it.first == "file" })
    }

    @Test
    fun `409 is reported as conflict`() = runBlocking {
        api.uploadException = httpException(409)

        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(mapOf("file:///x.jpg" to byteArrayOf(1)))
        )
        val evidence = evidenceEntity(
            id = "e1",
            type = "PHOTO",
            localUri = "file:///x.jpg"
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertEquals(EvidenceUploadResult.Conflict, result)
    }

    @Test
    fun `server errors are retryable`() = runBlocking {
        api.uploadException = httpException(500)

        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(mapOf("file:///x.jpg" to byteArrayOf(1)))
        )
        val evidence = evidenceEntity(
            id = "e1",
            type = "PHOTO",
            localUri = "file:///x.jpg"
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Failed)
        assertTrue((result as EvidenceUploadResult.Failed).retryable)
    }

    @Test
    fun `client rejections are not retryable`() = runBlocking {
        api.uploadException = httpException(400)

        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(mapOf("file:///x.jpg" to byteArrayOf(1)))
        )
        val evidence = evidenceEntity(
            id = "e1",
            type = "PHOTO",
            localUri = "file:///x.jpg"
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Failed)
        (result as EvidenceUploadResult.Failed).let {
            assertFalse(it.retryable)
        }
    }

    @Test
    fun `io exceptions are retryable`() = runBlocking {
        api.uploadException = IOException("unreachable")

        val repository = RemoteRepository(
            api = api,
            fileReader = FakeEvidenceFileReader(mapOf("file:///x.jpg" to byteArrayOf(1)))
        )
        val evidence = evidenceEntity(
            id = "e1",
            type = "PHOTO",
            localUri = "file:///x.jpg"
        )

        val result = repository.uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Failed)
        assertTrue((result as EvidenceUploadResult.Failed).retryable)
    }

    private fun partValue(
        values: List<Pair<String, String>>,
        name: String
    ): String? {
        return values.firstOrNull { it.first == name }?.second
    }
}