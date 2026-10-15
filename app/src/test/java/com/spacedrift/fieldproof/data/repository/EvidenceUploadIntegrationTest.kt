package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.FakeEvidenceFileReader
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.FieldProofApi
import com.spacedrift.fieldproof.evidenceEntity
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartReader
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Drives the real Retrofit/OkHttp stack (not the in-process [FieldProofApi] fake)
 * against a loopback HTTP server that replays the backend's idempotency contract:
 * a repeated ``Idempotency-Key`` with identical content returns the stored
 * response and never creates a second evidence record.
 *
 * This is the coverage for "replaying the same upload operation does not create
 * duplicate evidence" from the Android side of the wire.
 */
class EvidenceUploadIntegrationTest {

    private lateinit var server: FakeEvidenceServer
    private lateinit var api: FieldProofApi

    private val inspectionId = "11111111-1111-1111-1111-111111111111"
    private val checklistItemId = "33333333-3333-3333-3333-333333333333"

    private val photoUri = "file:///data/user/0/com.spacedrift.fieldproof/files/fieldproof/FIELDPROOF_1.jpg"
    private val photoBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setUp() {
        server = FakeEvidenceServer().also { it.start() }
        api = Retrofit.Builder()
            .baseUrl(server.baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FieldProofApi::class.java)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun repository(
        uri: String = photoUri,
        bytes: ByteArray = photoBytes
    ) = RemoteRepository(
        api = api,
        fileReader = FakeEvidenceFileReader(mapOf(uri to bytes))
    )

    private fun photoEvidence(id: String = "e1"): EvidenceEntity = evidenceEntity(
        id = id,
        inspectionId = inspectionId,
        checklistItemId = checklistItemId,
        type = "PHOTO",
        localUri = photoUri,
        note = null,
        capturedAt = 500L
    )

    @Test
    fun `photo upload sends file metadata and idempotency key over real http`() =
        runBlocking {
            val evidence = photoEvidence()

            val result = repository().uploadEvidence(evidence, evidence.id)

            assertEquals(EvidenceUploadResult.Success, result)
            assertEquals(1, server.receivedUploads)
            assertEquals(1, server.createdEvidence.size)

            val captured = server.createdEvidence.single()
            assertEquals(evidence.id, captured.id)
            assertEquals("photo", captured.kind)
            assertEquals("500", captured.capturedAt)
            assertEquals(checklistItemId, captured.checklistItemId)
            assertEquals("FIELDPROOF_1.jpg", captured.filename)
            assertEquals("image/jpeg", captured.contentType)
            assertArrayEquals(photoBytes, captured.fileBytes)

            // The idempotency key travels in the header, not the body.
            assertEquals(listOf(evidence.id), server.idempotencyKeys)
        }

    @Test
    fun `replaying the same evidence upload does not duplicate server evidence`() =
        runBlocking {
            val evidence = photoEvidence()
            val repository = repository()

            val first = repository.uploadEvidence(evidence, evidence.id)
            val replay = repository.uploadEvidence(evidence, evidence.id)

            assertEquals(EvidenceUploadResult.Success, first)
            assertEquals(EvidenceUploadResult.Success, replay)

            // Two requests hit the server, but only one evidence record exists.
            assertEquals(2, server.receivedUploads)
            assertEquals(1, server.createdEvidence.size)
            assertEquals(1, server.storedResponses)
            assertEquals(listOf(evidence.id, evidence.id), server.idempotencyKeys)
        }

    @Test
    fun `distinct idempotency keys create distinct server evidence`() = runBlocking {
        val repository = repository()

        repository.uploadEvidence(photoEvidence(id = "e1"), "e1")
        repository.uploadEvidence(photoEvidence(id = "e2"), "e2")

        assertEquals(2, server.createdEvidence.size)
        assertEquals(listOf("e1", "e2"), server.createdEvidence.map { it.id })
    }

    @Test
    fun `reused key with changed content is rejected as a conflict`() = runBlocking {
        val evidence = photoEvidence()
        repository().uploadEvidence(evidence, evidence.id)

        // Same key, different bytes -> server refuses rather than silently adding.
        val changed = photoBytes + 99
        val result = repository(bytes = changed)
            .uploadEvidence(evidence, evidence.id)

        assertTrue(result is EvidenceUploadResult.Conflict)
        assertEquals(2, server.receivedUploads)
        assertEquals(1, server.createdEvidence.size)
    }

    /**
     * In-memory stand-in for the FastAPI evidence endpoint. Parses the multipart
     * body with OkHttp's [MultipartReader] (mirroring the backend), fingerprints
     * content + metadata, and replays the stored response for a matching key.
     */
    private class FakeEvidenceServer {

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        private val idempotency = linkedMapOf<String, StoredRecord>()

        val createdEvidence = mutableListOf<CapturedEvidence>()
        val idempotencyKeys = mutableListOf<String>()
        var receivedUploads = 0

        val storedResponses: Int get() = idempotency.size

        val baseUrl: String
            get() = "http://127.0.0.1:${server.address.port}/"

        fun start() {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun stop() {
            server.stop(0)
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val isEvidence = exchange.requestMethod == "POST" &&
                path.matches(Regex("/api/v1/inspections/[^/]+/evidence"))

            if (!isEvidence) {
                respond(exchange, 404, """{"detail":"not found"}""")
                return
            }

            val key = exchange.requestHeaders.getFirst("Idempotency-Key")
            if (key == null) {
                respond(exchange, 400, """{"detail":"Idempotency-Key header is required"}""")
                return
            }

            receivedUploads += 1
            idempotencyKeys += key

            val captured = parseMultipart(exchange)
            val fingerprint = fingerprint(captured)

            val existing = idempotency[key]
            if (existing != null) {
                if (existing.fingerprint != fingerprint) {
                    respond(
                        exchange,
                        409,
                        """{"detail":"Idempotency-Key was already used with """ +
                            """different evidence content."}"""
                    )
                } else {
                    respond(exchange, existing.status, existing.body)
                }
                return
            }

            val evidenceId = captured.id ?: UUID.randomUUID().toString()
            createdEvidence += captured
            val body = """{"id":"$evidenceId","status":"SYNCED"}"""
            idempotency[key] = StoredRecord(fingerprint, 201, body)
            respond(exchange, 201, body)
        }

        private fun parseMultipart(exchange: HttpExchange): CapturedEvidence {
            val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
            val boundary = contentType
                .substringAfter("boundary=", "")
                .trim()
                .trim('"')

            var id: String? = null
            var kind = "photo"
            var capturedAt: String? = null
            var checklistItemId: String? = null
            var note: String? = null
            var filename: String? = null
            var partContentType: String? = null
            var fileBytes: ByteArray? = null

            MultipartReader(exchange.requestBody.source().buffer(), boundary)
                .use { reader ->
                    while (true) {
                        val part = reader.nextPart() ?: break
                        val disposition = part.headers["Content-Disposition"] ?: ""
                        val name = disposition
                            .substringAfter("name=\"", "")
                            .substringBefore("\"")
                        val partFilename = disposition
                            .substringAfter("filename=\"", "")
                            .substringBefore("\"")
                            .takeIf { it.isNotEmpty() }
                        val bytes = part.body.readByteArray()

                        when (name) {
                            "id" -> id = bytes.toString(Charsets.UTF_8)
                            "kind" -> kind = bytes.toString(Charsets.UTF_8)
                            "captured_at" -> capturedAt = bytes.toString(Charsets.UTF_8)
                            "checklist_item_id" ->
                                checklistItemId = bytes.toString(Charsets.UTF_8)
                            "note" -> note = bytes.toString(Charsets.UTF_8)
                            "file" -> {
                                filename = partFilename
                                partContentType = part.headers["Content-Type"]
                                fileBytes = bytes
                            }
                        }
                    }
                }

            return CapturedEvidence(
                id = id,
                kind = kind,
                capturedAt = capturedAt,
                checklistItemId = checklistItemId,
                note = note,
                filename = filename,
                contentType = partContentType,
                fileBytes = fileBytes
            )
        }

        private fun fingerprint(captured: CapturedEvidence): String {
            val metadata = listOf(
                captured.id,
                captured.kind,
                captured.checklistItemId,
                captured.note,
                captured.capturedAt,
                captured.filename,
                captured.contentType
            ).joinToString("|")

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(captured.fileBytes ?: ByteArray(0))
            digest.update(metadata.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun respond(exchange: HttpExchange, code: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private data class StoredRecord(
            val fingerprint: String,
            val status: Int,
            val body: String
        )
    }

    private data class CapturedEvidence(
        val id: String?,
        val kind: String,
        val capturedAt: String?,
        val checklistItemId: String?,
        val note: String?,
        val filename: String?,
        val contentType: String?,
        val fileBytes: ByteArray?
    )
}
