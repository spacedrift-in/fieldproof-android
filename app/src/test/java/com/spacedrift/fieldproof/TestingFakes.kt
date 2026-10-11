package com.spacedrift.fieldproof

import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.SyncOperationDao
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.EvidenceFileReader
import com.spacedrift.fieldproof.data.remote.EvidenceUploadResult
import com.spacedrift.fieldproof.data.remote.EvidenceUploader
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

    override suspend fun delete(operationId: String) {
        store.remove(operationId)
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

    fun get(id: String): EvidenceEntity? = store[id]
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