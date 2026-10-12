package com.spacedrift.fieldproof.data.remote

import com.spacedrift.fieldproof.domain.conflict.ServerConflict

// ---------------------------------------------------------------------------
// Server DTOs (Gson maps by exact field name; the API uses snake_case).
// ---------------------------------------------------------------------------

data class RemoteEvidence(
    val id: String,
    val checklist_item_id: String? = null,
    val kind: String,
    val note: String? = null,
    val sha256: String,
    val content_type: String? = null,
    val size_bytes: Int,
    val captured_at: String,
    val uploaded_at: String
)

data class RemoteChecklistItem(
    val id: String,
    val label: String,
    val required: Boolean,
    val evidence_requirement: String,
    val status: String,
    val note: String? = null,
    val sort_order: Int,
    val version: Int
)

data class RemoteReview(
    val id: String,
    val reviewer_id: String,
    val decision: String,
    val comment: String? = null,
    val inspection_version: Int,
    val reviewed_at: String
)

data class RemoteInspection(
    val id: String,
    val title: String,
    val location: String,
    val status: String,
    val version: Int,
    val created_at: String,
    val updated_at: String,
    val checklist_items: List<RemoteChecklistItem> = emptyList(),
    val evidence: List<RemoteEvidence> = emptyList(),
    val reviews: List<RemoteReview> = emptyList()
)

data class SubmitInspectionRequest(
    val expected_version: Int? = null
)

data class RemoteSubmitResponse(
    val id: String,
    val status: String,
    val version: Int
)

data class ReviewInspectionRequest(
    val decision: String,
    val comment: String? = null,
    val reviewer_id: String = "demo-reviewer",
    val expected_version: Int? = null
)

data class RemoteReviewResponse(
    val id: String,
    val status: String,
    val version: Int,
    val review: RemoteReview
)

data class RemoteReportSummary(
    val total_items: Int,
    val passed: Int,
    val failed: Int,
    val not_applicable: Int,
    val not_started: Int,
    val evidence_count: Int
)

data class RemoteReportChecklistItem(
    val id: String,
    val label: String,
    val required: Boolean,
    val evidence_requirement: String,
    val status: String,
    val note: String? = null,
    val evidence: List<RemoteEvidence> = emptyList()
)

data class RemoteReport(
    val inspection_id: String,
    val title: String,
    val location: String,
    val status: String,
    val version: Int,
    val generated_at: String,
    val summary: RemoteReportSummary,
    val checklist: List<RemoteReportChecklistItem>,
    val review: RemoteReview? = null
)

// ---------------------------------------------------------------------------
// Client-facing result + interface
// ---------------------------------------------------------------------------

sealed interface WorkflowResult<out T> {
    data class Success<T>(val value: T) : WorkflowResult<T>

    data class Conflict(
        val serverConflict: ServerConflict
    ) : WorkflowResult<Nothing> {
        val message: String get() = serverConflict.message
    }

    data class Failed(
        val message: String,
        val retryable: Boolean
    ) : WorkflowResult<Nothing>
}

interface InspectionWorkflowClient {
    suspend fun getInspection(
        inspectionId: String
    ): WorkflowResult<RemoteInspection>

    suspend fun submitInspection(
        inspectionId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteSubmitResponse>

    suspend fun reviewInspection(
        inspectionId: String,
        decision: String,
        comment: String?,
        reviewerId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteReviewResponse>

    suspend fun getReport(
        inspectionId: String
    ): WorkflowResult<RemoteReport>

    suspend fun downloadReportPdf(
        inspectionId: String
    ): WorkflowResult<ByteArray>
}