package com.spacedrift.fieldproof

import com.spacedrift.fieldproof.data.remote.CreateInspectionRequest
import com.spacedrift.fieldproof.data.remote.CreateInspectionResponse
import com.spacedrift.fieldproof.data.remote.HealthResponse
import com.spacedrift.fieldproof.data.remote.FieldProofApi
import com.spacedrift.fieldproof.data.remote.RemoteInspection
import com.spacedrift.fieldproof.data.remote.RemoteReport
import com.spacedrift.fieldproof.data.remote.RemoteReviewResponse
import com.spacedrift.fieldproof.data.remote.RemoteSubmitResponse
import com.spacedrift.fieldproof.data.remote.ReviewInspectionRequest
import com.spacedrift.fieldproof.data.remote.SubmitInspectionRequest
import com.spacedrift.fieldproof.data.remote.UploadEvidenceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

class FakeFieldProofApi : FieldProofApi {

    var uploadInspectionId: String? = null
    var uploadIdempotencyKey: String? = null
    lateinit var uploadParts: List<MultipartBody.Part>
    var attempts = 0
    var uploadException: Exception? = null

    var workflowException: Exception? = null
    var inspection: RemoteInspection? = null
    var submitResponse: RemoteSubmitResponse? = null
    var reviewResponse: RemoteReviewResponse? = null
    var report: RemoteReport? = null
    var submittedInspectionId: String? = null
    var reviewedInspectionId: String? = null
    var reviewRequest: ReviewInspectionRequest? = null

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

    override suspend fun getInspection(
        inspectionId: String
    ): RemoteInspection {
        workflowException?.let { throw it }
        return inspection
            ?: RemoteInspection(
                id = inspectionId,
                title = "untitled",
                location = "unknown",
                status = "DRAFT",
                version = 1,
                created_at = "2026-01-01T00:00:00Z",
                updated_at = "2026-01-01T00:00:00Z"
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

    override suspend fun submitInspection(
        inspectionId: String,
        request: SubmitInspectionRequest
    ): RemoteSubmitResponse {
        submittedInspectionId = inspectionId
        workflowException?.let { throw it }
        return submitResponse
            ?: RemoteSubmitResponse(id = inspectionId, status = "SUBMITTED", version = 2)
    }

    override suspend fun reviewInspection(
        inspectionId: String,
        request: ReviewInspectionRequest
    ): RemoteReviewResponse {
        reviewedInspectionId = inspectionId
        reviewRequest = request
        workflowException?.let { throw it }
        return reviewResponse
            ?: RemoteReviewResponse(
                id = inspectionId,
                status = "APPROVED",
                version = 3,
                review = com.spacedrift.fieldproof.data.remote.RemoteReview(
                    id = "r1",
                    reviewer_id = request.reviewer_id,
                    decision = request.decision,
                    comment = request.comment,
                    inspection_version = 3,
                    reviewed_at = "2026-01-01T00:00:00Z"
                )
            )
    }

    override suspend fun getReport(
        inspectionId: String
    ): RemoteReport {
        workflowException?.let { throw it }
        return report
            ?: RemoteReport(
                inspection_id = inspectionId,
                title = "untitled",
                location = "unknown",
                status = "APPROVED",
                version = 3,
                generated_at = "2026-01-01T00:00:00Z",
                summary = com.spacedrift.fieldproof.data.remote.RemoteReportSummary(
                    total_items = 3,
                    passed = 2,
                    failed = 0,
                    not_applicable = 0,
                    not_started = 1,
                    evidence_count = 2
                ),
                checklist = emptyList()
            )
    }

    var reportPdf: ByteArray = "%PDF-1.4 fake report".toByteArray()

    override suspend fun downloadReportPdf(
        inspectionId: String
    ): ResponseBody {
        workflowException?.let { throw it }
        return reportPdf.toResponseBody("application/pdf".toMediaType())
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