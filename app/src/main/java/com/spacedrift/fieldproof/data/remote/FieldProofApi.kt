package com.spacedrift.fieldproof.data.remote

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

interface FieldProofApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @POST("api/v1/inspections")
    suspend fun createInspection(
        @Body request: CreateInspectionRequest,
        @Header("Idempotency-Key") idempotencyKey: String
    ): CreateInspectionResponse

    @GET("api/v1/inspections/{inspectionId}")
    suspend fun getInspection(
        @Path("inspectionId") inspectionId: String
    ): RemoteInspection

    @Multipart
    @POST("api/v1/inspections/{inspectionId}/evidence")
    suspend fun uploadEvidence(
        @Path("inspectionId") inspectionId: String,
        @Part parts: List<MultipartBody.Part>,
        @Header("Idempotency-Key") idempotencyKey: String
    ): UploadEvidenceResponse

    @PUT("api/v1/inspections/{inspectionId}/checklist/{checklistItemId}")
    suspend fun updateChecklistOutcome(
        @Path("inspectionId") inspectionId: String,
        @Path("checklistItemId") checklistItemId: String,
        @Body request: ChecklistOutcomeRequest
    ): RemoteChecklistOutcomeResponse

    @POST("api/v1/inspections/{inspectionId}/submit")
    suspend fun submitInspection(
        @Path("inspectionId") inspectionId: String,
        @Body request: SubmitInspectionRequest
    ): RemoteSubmitResponse

    @POST("api/v1/inspections/{inspectionId}/review")
    suspend fun reviewInspection(
        @Path("inspectionId") inspectionId: String,
        @Body request: ReviewInspectionRequest
    ): RemoteReviewResponse

    @GET("api/v1/inspections/{inspectionId}/report")
    suspend fun getReport(
        @Path("inspectionId") inspectionId: String
    ): RemoteReport

    @Streaming
    @GET("api/v1/inspections/{inspectionId}/report.pdf")
    suspend fun downloadReportPdf(
        @Path("inspectionId") inspectionId: String
    ): ResponseBody
}