package com.spacedrift.fieldproof.data.remote

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface FieldProofApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("api/v1/inspections")
    suspend fun createInspection(
        @Body request: CreateInspectionRequest,
        @Header("Idempotency-Key") idempotencyKey: String
    ): CreateInspectionResponse

    @Multipart
    @POST("api/v1/inspections/{inspectionId}/evidence")
    suspend fun uploadEvidence(
        @Path("inspectionId") inspectionId: String,
        @Part parts: List<MultipartBody.Part>,
        @Header("Idempotency-Key") idempotencyKey: String
    ): UploadEvidenceResponse
}