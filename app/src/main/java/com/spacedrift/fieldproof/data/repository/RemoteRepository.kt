package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.remote.CreateInspectionRequest
import com.spacedrift.fieldproof.data.remote.EvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidencePartsResult
import com.spacedrift.fieldproof.data.remote.EvidenceRequestFactory
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import com.spacedrift.fieldproof.data.remote.FieldProofApi
import com.spacedrift.fieldproof.data.remote.HealthResponse
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class RemoteRepository @Inject constructor(
    private val api: FieldProofApi,
    private val fileReader: EvidenceFileReader
) : EvidenceUploader {

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
        } catch (exception: HttpException) {
            when {
                exception.code() == 409 -> EvidenceUploadResult.Conflict

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