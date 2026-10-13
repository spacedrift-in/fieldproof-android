package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.ChecklistItemDao
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionDao
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import com.spacedrift.fieldproof.security.EvidenceVault
import com.spacedrift.fieldproof.security.PassthroughEvidenceVault
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class InspectionRepository @Inject constructor(
    private val inspectionDao: InspectionDao,
    private val checklistItemDao: ChecklistItemDao,
    private val evidenceDao: EvidenceDao,
    private val syncRepository: SyncRepository,
    private val evidenceVault: EvidenceVault = PassthroughEvidenceVault
) {

    fun observeInspections(): Flow<List<InspectionEntity>> {
        return inspectionDao.observeInspections()
    }

    fun observeInspectionSummaries(): Flow<List<InspectionSummaryEntity>> {
        return inspectionDao.observeInspectionSummaries()
    }

    suspend fun getInspection(
        inspectionId: String
    ): InspectionEntity? {
        return inspectionDao.getInspection(inspectionId)
    }

    /**
     * Observes a single inspection so the detail screen can distinguish
     * *still loading* from *genuinely missing* instead of flashing a
     * not-found state before the first database emission.
     */
    fun observeInspection(
        inspectionId: String
    ): Flow<InspectionEntity?> {
        return inspectionDao.observeInspection(inspectionId)
    }

    suspend fun createInspection(
        title: String,
        location: String
    ): InspectionEntity {

        val now = System.currentTimeMillis()

        val inspection = InspectionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            location = location,
            status = "DRAFT",
            createdAt = now,
            updatedAt = now
        )

        inspectionDao.insertInspection(inspection)

        createDefaultChecklist(inspection.id)

        // 3. Create a durable sync operation so the inspection exists
        //    server-side before any of its evidence is uploaded.
        syncRepository.queueInspection(inspection.id)

        return inspection
    }

    fun observeChecklist(
        inspectionId: String
    ): Flow<List<ChecklistItemEntity>> {
        return checklistItemDao.observeChecklistItems(inspectionId)
    }

    fun observeEvidence(
        inspectionId: String
    ): Flow<List<EvidenceEntity>> {
        return evidenceDao.observeEvidence(inspectionId)
    }

    suspend fun completeChecklistItem(
        itemId: String,
        completed: Boolean
    ) {
        checklistItemDao.setCompleted(
            itemId = itemId,
            completed = completed
        )
    }

    /**
     * Record a checklist outcome (PASS/FAIL/NOT_APPLICABLE) and optional note
     * locally, then queue a durable sync operation so it reaches the server and
     * the report totals/PDF reflect it. Linking `completed` keeps the existing
     * progress aggregates working.
     */
    suspend fun setChecklistOutcome(
        itemId: String,
        outcome: String,
        note: String?
    ) {
        checklistItemDao.setOutcome(
            itemId = itemId,
            outcome = outcome,
            note = note,
            completed = outcome != ChecklistItemEntity.OUTCOME_NOT_STARTED,
            syncState = ChecklistItemEntity.SYNC_STATE_QUEUED
        )
        syncRepository.queueChecklistOutcome(itemId)
    }

    suspend fun addEvidence(
        inspectionId: String,
        checklistItemId: String?,
        type: String,
        localUri: String?,
        note: String?
    ) {
        val evidenceId = UUID.randomUUID().toString()

        // Encrypt photo evidence at rest before it is persisted. The camera
        // writes a short-lived plaintext file; the vault seals it with the
        // device-bound key and deletes the plaintext copy.
        val sealedUri = if (type.equals("PHOTO", ignoreCase = true)) {
            evidenceVault.import(localUri) ?: localUri
        } else {
            localUri
        }

        val evidence = EvidenceEntity(
            id = evidenceId,
            inspectionId = inspectionId,
            checklistItemId = checklistItemId,
            type = type,
            localUri = sealedUri,
            note = note,
            capturedAt = System.currentTimeMillis(),
            syncStatus = "QUEUED"
        )

        // 1. Save the actual evidence locally.
        evidenceDao.insertEvidence(evidence)

        // 2. Create a durable sync operation.
        syncRepository.queueEvidence(evidenceId)
    }

    /**
     * Deletes an inspection together with everything that references it: the
     * sealed evidence files on disk, the queued sync operations, and the Room
     * rows. Order matters — the files and sync ops are removed before the rows
     * so an interrupted delete cannot orphan sensitive bytes.
     */
    suspend fun deleteInspection(
        inspectionId: String
    ) {
        val evidence = evidenceDao.getForInspection(inspectionId)
        val checklist = checklistItemDao.getForInspection(inspectionId)

        evidence.forEach { record ->
            evidenceVault.delete(record.localUri)
        }

        syncRepository.deleteForEntities(
            buildList {
                add(inspectionId)
                evidence.forEach { add(it.id) }
                checklist.forEach { add(it.id) }
            }
        )

        checklistItemDao.deleteForInspection(inspectionId)
        evidenceDao.deleteForInspection(inspectionId)
        inspectionDao.deleteInspection(inspectionId)
    }

    /**
     * Retention sweep: removes the on-device copy of evidence that has already
     * been uploaded (`SYNCED`) and is older than [retentionDays]. The metadata
     * row is kept but its `localUri` is cleared, so the authoritative copy lives
     * on the server while the sensitive local bytes are gone. Returns the number
     * of files purged.
     */
    suspend fun purgeExpiredEvidence(
        retentionDays: Int = DEFAULT_RETENTION_DAYS,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (retentionDays <= 0) return 0

        val cutoff = now - retentionDays * DAY_MILLIS
        val expired = evidenceDao.getSyncedCapturedBefore(cutoff)

        var purged = 0
        expired.forEach { record ->
            if (evidenceVault.delete(record.localUri)) {
                purged++
            }
            evidenceDao.clearLocalUri(record.id)
        }
        return purged
    }

    /**
     * Full local wipe: deletes every sealed evidence file and all Room rows
     * (inspections, checklist items, evidence and sync operations). Backs the
     * user-facing "delete all data" action.
     */
    suspend fun deleteAllLocalData() {
        evidenceVault.deleteAll()
        evidenceDao.deleteAll()
        checklistItemDao.deleteAll()
        inspectionDao.deleteAll()
        syncRepository.deleteAll()
    }

    private suspend fun createDefaultChecklist(
        inspectionId: String
    ) {
        val items = DEMO_TEMPLATE_ITEMS.map { templateItem ->
            ChecklistItemEntity(
                id = checklistItemId(
                    inspectionId = inspectionId,
                    templateItemId = templateItem.templateId
                ),
                inspectionId = inspectionId,
                title = templateItem.title,
                required = templateItem.required
            )
        }

        checklistItemDao.insertChecklistItems(items)
    }

    private data class TemplateItem(
        val templateId: String,
        val title: String,
        val required: Boolean
    )

    // Mirrors the backend demo template (see backend/app/demo_data.py) so local
    // checklist item ids line up with the server-seeded items. The ids are derived
    // deterministically from (inspectionId, template_item_id) so offline-created
    // inspections reference the same items the server creates on sync.
    private companion object {
        // Retain local evidence copies for 30 days after a successful upload.
        // The server remains the authoritative copy past this window.
        const val DEFAULT_RETENTION_DAYS = 30
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        val DEMO_TEMPLATE_ITEMS = listOf(
            TemplateItem(
                templateId = "41adf50d-2c24-4e13-9b7e-2dfae32bfe01",
                title = "Emergency exit is unobstructed",
                required = true
            ),
            TemplateItem(
                templateId = "41adf50d-2c24-4e13-9b7e-2dfae32bfe02",
                title = "Fire extinguisher is accessible",
                required = true
            ),
            TemplateItem(
                templateId = "41adf50d-2c24-4e13-9b7e-2dfae32bfe03",
                title = "General observations",
                required = false
            )
        )

        fun checklistItemId(
            inspectionId: String,
            templateItemId: String
        ): String {
            return UUID.nameUUIDFromBytes(
                "$inspectionId:$templateItemId".toByteArray(Charsets.UTF_8)
            ).toString()
        }
    }
}