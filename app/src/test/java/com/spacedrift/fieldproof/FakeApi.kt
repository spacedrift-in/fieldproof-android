package com.spacedrift.fieldproof

import com.spacedrift.fieldproof.data.remote.CreateInspectionRequest
import com.spacedrift.fieldproof.data.remote.CreateInspectionResponse
import com.spacedrift.fieldproof.data.remote.HealthResponse
import com.spacedrift.fieldproof.data.remote.FieldProofApi
import com.spacedrift.fieldproof.data.remote.UploadEvidenceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class FakeFieldProofApi : FieldProofApi {

    var uploadInspectionId: String? = null
    var uploadIdempotencyKey: String? = null
    lateinit var uploadParts: List<MultipartBody.Part>
    var attempts = 0
    var uploadException: Exception? = null

    override suspend fun health(): HealthResponse {
        return HealthResponse(
            status = "ok",
            service = "fieldproof-api",
            stage = "starter"
        )
    }

    override suspend fun createInspection(
        request: CreateInspectionRequest,
        idempotencyKey: String
    ): CreateInspectionResponse {
        return CreateInspectionResponse(
            id = request.id,
            status = "DRAFT"
        )
    }

    override suspend fun uploadEvidence(
        inspectionId: String,
        parts: List<MultipartBody.Part>,
        idempotencyKey: String
    ): UploadEvidenceResponse {
        attempts += 1
        uploadInspectionId = inspectionId
        uploadParts = parts
        uploadIdempotencyKey = idempotencyKey

        uploadException?.let { throw it }

        return UploadEvidenceResponse(
            id = idempotencyKey,
            status = "SYNCED"
        )
    }
}

fun readPartValues(parts: List<MultipartBody.Part>): List<Pair<String, String>> {
    val body = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .apply { parts.forEach { addPart(it) } }
        .build()

    return body.parts.map { part ->
        val disposition = part.headers?.get("Content-Disposition") ?: ""
        val name = disposition
            .substringAfter("name=\"")
            .substringBefore("\"")
        val buffer = okio.Buffer()
        part.body.writeTo(buffer)
        name to buffer.readUtf8()
    }
}

fun httpException(code: Int): HttpException {
    val jsonBody = """{"detail":"error"}"""
    val body = jsonBody.toResponseBody("application/json".toMediaType())
    val response = Response.error<UploadEvidenceResponse>(code, body)
    return HttpException(response)
}