package com.spacedrift.fieldproof

import com.spacedrift.fieldproof.data.local.ChecklistItemDao
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionDao
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import com.spacedrift.fieldproof.data.local.SyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.ChecklistOutcomeUploadResult
import com.spacedrift.fieldproof.data.remote.ChecklistOutcomeUploader
import com.spacedrift.fieldproof.data.remote.EvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
import com.spacedrift.fieldproof.data.remote.InspectionUploadResult
import com.spacedrift.fieldproof.data.remote.InspectionUploader
import com.spacedrift.fieldproof.data.remote.InspectionWorkflowClient
import com.spacedrift.fieldproof.data.remote.RemoteInspection
import com.spacedrift.fieldproof.data.remote.RemoteReport
import com.spacedrift.fieldproof.data.remote.RemoteReviewResponse
import com.spacedrift.fieldproof.data.remote.RemoteSubmitResponse
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSyncOperationDao : SyncOperationDao {

    private val store = linkedMapOf<String, SyncOperationEntity>()
    private val flow = MutableStateFlow<List<SyncOperationEntity>>(emptyList())

    private fun update() {
        flow.value = store.values.sortedByDescending { it.createdAt }
    }

    override suspend fun insert(operation: SyncOperationEntity) {
        store[operation.id] = operation
        update()
    }

    override suspend fun getQueuedOperations(): List<SyncOperationEntity> {
        return store.values
            .filter { it.status == "QUEUED" }
            .sortedBy { it.createdAt }
    }

    override fun observeOperations(): Flow<List<SyncOperationEntity>> = flow

    override suspend fun updateStatus(
        operationId: String,
        status: String,
        attempts: Int,
        lastError: String?,
        updatedAt: Long
    ) {
        val current = store[operationId] ?: return
        store[operationId] = current.copy(
            status = status,
            attempts = attempts,
            lastError = lastError,
            updatedAt = updatedAt
        )
        update()
    }

    override suspend fun recoverStaleUploads(updatedAt: Long, staleBefore: Long) {
        store.values
            .filter { it.status == "UPLOADING" && it.updatedAt <= staleBefore }
            .forEach { operation ->
                store[operation.id] = operation.copy(
                    status = "QUEUED",
                    updatedAt = updatedAt
                )
            }
        update()
    }

    override suspend fun requeueFailed(updatedAt: Long) {
        store.values
            .filter { it.status == "FAILED" }
            .forEach { operation ->
                store[operation.id] = operation.copy(
                    status = "QUEUED",
                    attempts = 0,
                    lastError = null,
                    updatedAt = updatedAt
                )
            }
        update()
    }

    override suspend fun requeueOperation(operationId: String, updatedAt: Long) {
        val current = store[operationId] ?: return
        store[operationId] = current.copy(
            status = "QUEUED",
            attempts = 0,
            lastError = null,
            conflictDetail = null,
            updatedAt = updatedAt
        )
        update()
    }

    override suspend fun markResolved(operationId: String, updatedAt: Long) {
        val current = store[operationId] ?: return
        store[operationId] = current.copy(
            status = "RESOLVED",
            lastError = null,
            conflictDetail = null,
            updatedAt = updatedAt
        )
        update()
    }

    override suspend fun markConflict(
        operationId: String,
        error: String?,
        conflictDetail: String?,
        updatedAt: Long
    ) {
        val current = store[operationId] ?: return
        store[operationId] = current.copy(
            status = "CONFLICT",
            lastError = error,
            conflictDetail = conflictDetail,
            updatedAt = updatedAt
        )
        update()
    }

    override suspend fun getInspectionOperation(
        inspectionId: String
    ): SyncOperationEntity? {
        return store.values
            .filter { it.entityType == "INSPECTION" && it.entityId == inspectionId }
            .maxByOrNull { it.createdAt }
    }

    override suspend fun delete(operationId: String) {
        store.remove(operationId)
        update()
    }

    override suspend fun deleteForEntities(entityIds: List<String>) {
        store.values
            .filter { it.entityId in entityIds }
            .map { it.id }
            .forEach { store.remove(it) }
        update()
    }

    override suspend fun deleteAll() {
        store.clear()
        update()
    }

    fun get(id: String): SyncOperationEntity? = store[id]

    fun findByEntityId(entityId: String): SyncOperationEntity? {
        return store.values.firstOrNull { it.entityId == entityId }
    }

    fun snapshot(): List<SyncOperationEntity> = store.values.toList()
}

class FakeEvidenceDao : EvidenceDao {

    private val store = linkedMapOf<String, EvidenceEntity>()

    override suspend fun insertEvidence(evidence: EvidenceEntity) {
        store[evidence.id] = evidence
    }

    override suspend fun getEvidence(id: String): EvidenceEntity? = store[id]

    override fun observeEvidence(inspectionId: String): Flow<List<EvidenceEntity>> {
        val items = store.values
            .filter { it.inspectionId == inspectionId }
            .sortedByDescending { it.capturedAt }
        return MutableStateFlow(items)
    }

    override suspend fun getForInspection(inspectionId: String): List<EvidenceEntity> {
        return store.values
            .filter { it.inspectionId == inspectionId }
            .sortedByDescending { it.capturedAt }
    }

    override suspend fun getSyncedCapturedBefore(capturedBefore: Long): List<EvidenceEntity> {
        return store.values.filter {
            it.syncStatus == "SYNCED" && it.capturedAt < capturedBefore && it.localUri != null
        }
    }

    override suspend fun clearLocalUri(evidenceId: String) {
        store[evidenceId]?.let { store[evidenceId] = it.copy(localUri = null) }
    }

    override suspend fun deleteAll() {
        store.clear()
    }

    override suspend fun updateSyncStatus(evidenceId: String, status: String) {
        store[evidenceId]?.let {
            store[evidenceId] = it.copy(syncStatus = status)
        }
    }

    override suspend fun deleteForInspection(inspectionId: String) {
        val toRemove = store.values
            .filter { it.inspectionId == inspectionId }
            .map { it.id }
        toRemove.forEach { store.remove(it) }
    }

    override suspend fun deleteEvidence(evidenceId: String) {
        store.remove(evidenceId)
    }

    fun get(id: String): EvidenceEntity? = store[id]

    fun snapshot(): List<EvidenceEntity> = store.values.toList()
}

class FakeInspectionDao : InspectionDao {

    private val store = linkedMapOf<String, InspectionEntity>()

    override suspend fun insertInspection(inspection: InspectionEntity) {
        store[inspection.id] = inspection
    }

    override fun observeInspections(): Flow<List<InspectionEntity>> {
        return MutableStateFlow(store.values.sortedByDescending { it.updatedAt })
    }

    override fun observeInspectionSummaries(): Flow<List<InspectionSummaryEntity>> {
        val items = store.values
            .sortedByDescending { it.updatedAt }
            .map { inspection ->
                InspectionSummaryEntity(
                    id = inspection.id,
                    title = inspection.title,
                    location = inspection.location,
                    status = inspection.status,
                    createdAt = inspection.createdAt,
                    updatedAt = inspection.updatedAt,
                    totalItems = 0,
                    completedItems = 0,
                    evidenceCount = 0
                )
            }
        return MutableStateFlow(items)
    }

    override suspend fun getInspection(inspectionId: String): InspectionEntity? = store[inspectionId]

    override fun observeInspection(inspectionId: String): Flow<InspectionEntity?> =
        MutableStateFlow(store[inspectionId])

    override suspend fun deleteInspection(inspectionId: String) {
        store.remove(inspectionId)
    }

    override suspend fun deleteAll() {
        store.clear()
    }

    fun get(id: String): InspectionEntity? = store[id]
}

class FakeChecklistItemDao : ChecklistItemDao {

    private val store = linkedMapOf<String, ChecklistItemEntity>()

    override suspend fun insertChecklistItem(item: ChecklistItemEntity) {
        store[item.id] = item
    }

    override suspend fun insertChecklistItems(items: List<ChecklistItemEntity>) {
        items.forEach { store[it.id] = it }
    }

    override fun observeChecklistItems(inspectionId: String): Flow<List<ChecklistItemEntity>> {
        return MutableStateFlow(
            store.values.filter { it.inspectionId == inspectionId }.sortedBy { it.id }
        )
    }

    override suspend fun getChecklistItem(itemId: String): ChecklistItemEntity? = store[itemId]

    override suspend fun getForInspection(inspectionId: String): List<ChecklistItemEntity> {
        return store.values.filter { it.inspectionId == inspectionId }
    }

    override suspend fun setCompleted(itemId: String, completed: Boolean) {
        store[itemId]?.let { store[itemId] = it.copy(completed = completed) }
    }

    override suspend fun setOutcome(
        itemId: String,
        outcome: String,
        note: String?,
        completed: Boolean,
        syncState: String
    ) {
        store[itemId]?.let {
            store[itemId] = it.copy(
                outcome = outcome,
                note = note,
                completed = completed,
                syncState = syncState
            )
        }
    }

    override suspend fun updateSyncState(itemId: String, syncState: String) {
        store[itemId]?.let { store[itemId] = it.copy(syncState = syncState) }
    }

    override suspend fun markSynced(itemId: String, syncState: String, syncedVersion: Int?) {
        store[itemId]?.let {
            store[itemId] = it.copy(syncState = syncState, syncedVersion = syncedVersion)
        }
    }

    override suspend fun deleteForInspection(inspectionId: String) {
        store.values.filter { it.inspectionId == inspectionId }.map { it.id }
            .forEach { store.remove(it) }
    }

    override suspend fun deleteAll() {
        store.clear()
    }

    fun get(id: String): ChecklistItemEntity? = store[id]

    fun snapshot(): List<ChecklistItemEntity> = store.values.toList()
}

class FakeChecklistOutcomeUploader(
    private val outcomes: MutableList<ChecklistOutcomeUploadResult> = mutableListOf()
) : ChecklistOutcomeUploader {

    val uploaded = mutableListOf<Pair<ChecklistItemEntity, String>>()

    override suspend fun uploadChecklistOutcome(
        item: ChecklistItemEntity,
        idempotencyKey: String
    ): ChecklistOutcomeUploadResult {
        uploaded.add(item to idempotencyKey)
        return if (outcomes.isEmpty()) {
            ChecklistOutcomeUploadResult.Success(itemVersion = 2, inspectionVersion = 2)
        } else {
            outcomes.removeAt(0)
        }
    }
}

class FakeEvidenceFileReader(
    private val responses: Map<String, ByteArray?> = emptyMap()
) : EvidenceFileReader {
    override suspend fun readBytes(localUri: String): ByteArray? {
        return responses[localUri]
    }
}

class FakeEvidenceUploader(
    private val outcomes: MutableList<EvidenceUploadResult> = mutableListOf()
) : EvidenceUploader {

    val uploaded = mutableListOf<Pair<EvidenceEntity, String>>()

    override suspend fun uploadEvidence(
        evidence: EvidenceEntity,
        idempotencyKey: String
    ): EvidenceUploadResult {
        uploaded.add(evidence to idempotencyKey)

        return if (outcomes.isEmpty()) {
            EvidenceUploadResult.Success
        } else {
            outcomes.removeAt(0)
        }
    }
}

class FakeInspectionUploader(
    private val outcomes: MutableList<InspectionUploadResult> = mutableListOf()
) : InspectionUploader {

    val uploaded = mutableListOf<Pair<InspectionEntity, String>>()

    override suspend fun uploadInspection(
        inspection: InspectionEntity,
        idempotencyKey: String
    ): InspectionUploadResult {
        uploaded.add(inspection to idempotencyKey)

        return if (outcomes.isEmpty()) {
            InspectionUploadResult.Success
        } else {
            outcomes.removeAt(0)
        }
    }
}

class FakeSyncScheduler : SyncScheduler {

    var enqueued = 0
        private set

    var forcedRetries = 0
        private set

    override fun enqueueSync() {
        enqueued += 1
    }

    override fun forceRetry() {
        forcedRetries += 1
    }
}

fun inspectionEntity(
    id: String = "inspection-1",
    title: String = "Facility A",
    location: String = "Building 1",
    status: String = "DRAFT",
    createdAt: Long = 1000L,
    updatedAt: Long = 1000L
) = InspectionEntity(
    id = id,
    title = title,
    location = location,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun testConflict(
    message: String = "conflict",
    rawDetail: String = """{"detail":"$message"}"""
): ServerConflict = ServerConflict.Rejected(message, rawDetail)

fun missingEvidenceConflict(
    message: String = "Required evidence is missing.",
    missingIds: List<String>
): ServerConflict = ServerConflict.MissingRequiredEvidence(
    message = message,
    missingChecklistItemIds = missingIds,
    rawDetail = """{"detail":{"message":"$message","missing_checklist_item_ids":""" +
        missingIds.joinToString(prefix = "[", postfix = "]", transform = { "\"$it\"" }) +
        "}}"
)

class FakeInspectionWorkflowClient(
    var inspection: WorkflowResult<RemoteInspection> =
        WorkflowResult.Success(remoteInspection()),
    var submitResult: WorkflowResult<RemoteSubmitResponse> =
        WorkflowResult.Success(RemoteSubmitResponse(id = "inspection-1", status = "SUBMITTED", version = 2)),
    var reviewResult: WorkflowResult<RemoteReviewResponse>? = null,
    var reportResult: WorkflowResult<RemoteReport>? = null
) : InspectionWorkflowClient {

    val submits = mutableListOf<Pair<String, Int?>>()

    override suspend fun getInspection(
        inspectionId: String
    ): WorkflowResult<RemoteInspection> = inspection

    override suspend fun submitInspection(
        inspectionId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteSubmitResponse> {
        submits.add(inspectionId to expectedVersion)
        return submitResult
    }

    override suspend fun reviewInspection(
        inspectionId: String,
        decision: String,
        comment: String?,
        reviewerId: String,
        expectedVersion: Int?
    ): WorkflowResult<RemoteReviewResponse> {
        return reviewResult ?: WorkflowResult.Failed("no review configured", retryable = false)
    }

    override suspend fun getReport(
        inspectionId: String
    ): WorkflowResult<RemoteReport> {
        return reportResult ?: WorkflowResult.Failed("no report configured", retryable = false)
    }

    override suspend fun downloadReportPdf(
        inspectionId: String
    ): WorkflowResult<ByteArray> {
        return WorkflowResult.Failed("no report configured", retryable = false)
    }
}

fun remoteInspection(
    id: String = "inspection-1",
    status: String = "DRAFT",
    version: Int = 1
) = RemoteInspection(
    id = id,
    title = "Facility A",
    location = "Building 1",
    status = status,
    version = version,
    created_at = "2026-01-01T00:00:00Z",
    updated_at = "2026-01-01T00:00:00Z"
)

fun checklistItemEntity(
    id: String = "checklist-1",
    inspectionId: String = "inspection-1",
    title: String = "Fire extinguisher accessible",
    required: Boolean = true,
    completed: Boolean = false,
    outcome: String = ChecklistItemEntity.OUTCOME_NOT_STARTED,
    note: String? = null,
    syncedVersion: Int? = null,
    syncState: String = ChecklistItemEntity.SYNC_STATE_LOCAL
) = ChecklistItemEntity(
    id = id,
    inspectionId = inspectionId,
    title = title,
    required = required,
    completed = completed,
    outcome = outcome,
    note = note,
    syncedVersion = syncedVersion,
    syncState = syncState
)

fun evidenceEntity(
    id: String = "evidence-1",
    inspectionId: String = "inspection-1",
    checklistItemId: String? = null,
    type: String = "PHOTO",
    localUri: String? = "file:///tmp/FIELDPROOF_1.jpg",
    note: String? = null,
    capturedAt: Long = 1000L
) = EvidenceEntity(
    id = id,
    inspectionId = inspectionId,
    checklistItemId = checklistItemId,
    type = type,
    localUri = localUri,
    note = note,
    capturedAt = capturedAt,
    syncStatus = "QUEUED"
)