package com.spacedrift.fieldproof.remote

import com.spacedrift.fieldproof.evidenceEntity
import com.spacedrift.fieldproof.readPartValues
import com.spacedrift.fieldproof.data.remote.EvidencePartsResult
import com.spacedrift.fieldproof.data.remote.EvidenceRequestFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceRequestFactoryTest {

    private val photo = evidenceEntity(
        id = "e1",
        type = "PHOTO",
        localUri = "file:///data/user/0/app/files/fieldproof/FIELDPROOF_123.jpg"
    )

    private val note = evidenceEntity(
        id = "e2",
        type = "NOTE",
        localUri = null,
        note = "Found a loose handrail"
    )

    @Test
    fun `photo build includes id kind captured_at and file parts`() {
        val photoBytes = byteArrayOf(1, 2, 3, 4)

        val result = EvidenceRequestFactory.build(photo, photoBytes)

        assertTrue(result is EvidencePartsResult.Ok)

        val values = readPartValues((result as EvidencePartsResult.Ok).parts)
        assertEquals("e1", partValue(values, "id"))
        assertEquals("photo", partValue(values, "kind"))
        assertEquals("1000", partValue(values, "captured_at"))

        val filePart = (result.parts).find { part ->
            val name = part.headers?.get("Content-Disposition")
                ?.substringAfter("name=\"")
                ?.substringBefore("\"")
            name == "file"
        }
        assertTrue("file part expected", filePart != null)
        val buffer = okio.Buffer()
        filePart!!.body.writeTo(buffer)
        assertTrue(
            contentEquals(photoBytes, buffer.readByteArray())
        )
    }

    @Test
    fun `optional checklist and note fields are included`() {
        val photoWithMeta = photo.copy(
            checklistItemId = "ci-9",
            note = "Crack found"
        )

        val result =
            EvidenceRequestFactory.build(photoWithMeta, byteArrayOf(1)) as EvidencePartsResult.Ok

        val values = readPartValues(result.parts)
        assertEquals("ci-9", partValue(values, "checklist_item_id"))
        assertEquals("Crack found", partValue(values, "note"))
    }

    @Test
    fun `photo without bytes is reported as missing file`() {
        val result = EvidenceRequestFactory.build(photo, null)
        assertEquals(EvidencePartsResult.MissingFile, result)
    }

    @Test
    fun `note build has no file part and maps kind to note`() {
        val result = EvidenceRequestFactory.build(note, null)
        assertTrue(result is EvidencePartsResult.Ok)

        val values = readPartValues((result as EvidencePartsResult.Ok).parts)
        assertEquals("note", partValue(values, "kind"))
        assertEquals("Found a loose handrail", partValue(values, "note"))
        assertFalse(values.any { it.first == "file" })
    }

    @Test
    fun `kind mapping normalizes known and unknown types`() {
        assertEquals("photo", EvidenceRequestFactory.kindFor("PHOTO"))
        assertEquals("photo", EvidenceRequestFactory.kindFor("photo"))
        assertEquals("note", EvidenceRequestFactory.kindFor("note"))
        assertEquals("other", EvidenceRequestFactory.kindFor("other"))
    }

    private fun partValue(
        values: List<Pair<String, String>>,
        name: String
    ): String? {
        return values.firstOrNull { it.first == name }?.second
    }

    private fun contentEquals(a: ByteArray, b: ByteArray): Boolean {
        return a.contentEquals(b)
    }
}