package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.remote.CreateInspectionRequest
import com.spacedrift.fieldproof.data.remote.EvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidencePartsResult
import com.spacedrift.fieldproof.data.remote.EvidenceRequestFactory
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import com.spacedrift.fieldproof.data.remote.FieldProofApi
import com.spacedrift.fieldproof.data.remote.HealthResponse
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploader
import com.spacedrift.fieldproof.data.remote.InspectionWorkflowClient
import com.spacedrift.fieldproof.data.remote.RemoteInspection
import com.spacedrift.fieldproof.data.remote.RemoteReport
import com.spacedrift.fieldproof.data.remote.RemoteReviewResponse
import com.spacedrift.fieldproof.data.remote.RemoteSubmitResponse
import com.spacedrift.fieldproof.data.remote.ReviewInspectionRequest
import com.spacedrift.fieldproof.data.remote.SubmitInspectionRequest
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.domain.conflict.ServerConflictParser
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class RemoteRepository @Inject constructor(
    private val api: FieldProofApi,
    private val fileReader: EvidenceFileReader
) : EvidenceUploader, InspectionUploader, InspectionWorkflowClient {

    suspend fun health(): HealthResponse {
        return api.health()
    }

    suspend fun createInspection(
        id: String,
        title: String,
        location: String,
        createdAt: Long,
        idempotencyKey: String
    ) = api.createInspection(
        request = CreateInspectionRequest(
            id = id,
            title = title,
            location = location,
            createdAt = createdAt
        ),
        idempotencyKey = idempotencyKey
    )

    override suspend fun uploadInspection(
        inspection: InspectionEntity,
        idempotencyKey: String
    ): InspectionUploadResult {
        return try {
            createInspection(
                id = inspection.id,
                title = inspection.title,
                location = inspection.location,
                createdAt = inspection.createdAt,
                idempotencyKey = idempotencyKey
            )
            InspectionUploadResult.Success
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: HttpException) {
            when {
                exception.code() == 409 -> InspectionUploadResult.Conflict(
                    serverConflict = parseConflict(exception)
                )

                exception.code() >= 500 -> InspectionUploadResult.Failed(
                    retryable = true,
                    message = "Server error (${exception.code()}): ${exception.message()}"
                )

                else -> InspectionUploadResult.Failed(
                    retryable = false,
                    message = "Create rejected (${exception.code()}): ${exception.message()}"
                )
            }
        } catch (exception: IOException) {
            InspectionUploadResult.Failed(
                retryable = true,
                message = "Network error: ${exception.message ?: "unreachable"}"
            )
        } catch (exception: Exception) {
            InspectionUploadResult.Failed(
                retryable = false,
                message = exception.message ?: "Unknown create error"
            )
        }
    }

    override suspend fun uploadEvidence(
        evidence: EvidenceEntity,
        idempotencyKey: String
    ): EvidenceUploadResult {
        val photoBytes = evidence.localUri?.let { uri ->
            fileReader.readBytes(uri)
        }

        return when (
            val result = EvidenceRequestFactory.build(
                evidence = evidence,
                photoBytes = photoBytes
            )
        ) {
            is EvidencePartsResult.MissingFile -> {
                EvidenceUploadResult.Failed(
                    retryable = false,
                    message = "Evidence photo is missing or unreadable " +
                        "(uri=${evidence.localUri ?: "none"})."
                )
            }

            is EvidencePartsResult.Ok -> {
                upload(
                    evidence = evidence,
                    parts = result.parts,
                    idempotencyKey = idempotencyKey
                )
            }
        }
    }

    override suspend fun getInspection(
        inspectionId: String
    ): WorkflowResult<RemoteInspection> {
        return runWorkflow {
            api.getInspection(inspectionId)
        }
    }

    override suspend fun submitInspection(
        inspectionId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteSubmitResponse> {
        return runWorkflow {
            api.submitInspection(
                inspectionId = inspectionId,
                request = SubmitInspectionRequest(expected_version = expectedVersion)
            )
        }
    }

    override suspend fun reviewInspection(
        inspectionId: String,
        decision: String,
        comment: String?,
        reviewerId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteReviewResponse> {
        return runWorkflow {
            api.reviewInspection(
                inspectionId = inspectionId,
                request = ReviewInspectionRequest(
                    decision = decision,
                    comment = comment,
                    reviewer_id = reviewerId,
                    expected_version = expectedVersion
                )
            )
        }
    }

    override suspend fun getReport(
        inspectionId: String
    ): WorkflowResult<RemoteReport> {
        return runWorkflow {
            api.getReport(inspectionId)
        }
    }

    override suspend fun downloadReportPdf(
        inspectionId: String
    ): WorkflowResult<ByteArray> {
        return runWorkflow {
            api.downloadReportPdf(inspectionId).use { body -> body.bytes() }
        }
    }

    private suspend fun <T> runWorkflow(
        block: suspend () -> T
    ): WorkflowResult<T> {
        return try {
            WorkflowResult.Success(block())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: HttpException) {
            when {
                exception.code() == 409 -> WorkflowResult.Conflict(
                    serverConflict = parseConflict(exception)
                )

                exception.code() >= 500 -> WorkflowResult.Failed(
                    message = httpMessage(exception),
                    retryable = true
                )

                else -> WorkflowResult.Failed(
                    message = httpMessage(exception),
                    retryable = false
                )
            }
        } catch (exception: IOException) {
            WorkflowResult.Failed(
                message = "Network error: ${exception.message ?: "unreachable"}",
                retryable = true
            )
        } catch (exception: Exception) {
            WorkflowResult.Failed(
                message = exception.message ?: "Unknown workflow error",
                retryable = false
            )
        }
    }

    private fun httpMessage(exception: HttpException): String {
        return "Request failed (${exception.code()}): ${exception.message()}"
    }

    /**
     * Reads and maps the body of a 409 response. The error body can only be
     * consumed once, so it is read here immediately and handed to the parser.
     */
    private fun parseConflict(exception: HttpException): ServerConflict {
        val body = runCatching {
            exception.response()?.errorBody()?.string()
        }.getOrNull()
        return ServerConflictParser.parse(body)
    }

    private suspend fun upload(
        evidence: EvidenceEntity,
        parts: List<okhttp3.MultipartBody.Part>,
        idempotencyKey: String
    ): EvidenceUploadResult {
        return try {
            api.uploadEvidence(
                inspectionId = evidence.inspectionId,
                parts = parts,
                idempotencyKey = idempotencyKey
            )
            EvidenceUploadResult.Success
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: HttpException) {
            when {
                exception.code() == 409 -> EvidenceUploadResult.Conflict(
                    serverConflict = parseConflict(exception)
                )

                exception.code() >= 500 -> EvidenceUploadResult.Failed(
                    retryable = true,
                    message = "Server error (${exception.code()}): ${exception.message()}"
                )

                else -> EvidenceUploadResult.Failed(
                    retryable = false,
                    message = "Upload rejected (${exception.code()}): ${exception.message()}"
                )
            }
        } catch (exception: IOException) {
            EvidenceUploadResult.Failed(
                retryable = true,
                message = "Network error: ${exception.message ?: "unreachable"}"
            )
        } catch (exception: Exception) {
            EvidenceUploadResult.Failed(
                retryable = false,
                message = exception.message ?: "Unknown upload error"
            )
        }
    }
}