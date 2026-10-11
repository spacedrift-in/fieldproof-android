package com.spacedrift.fieldproof.data.repository

import com.spacedrift.fieldproof.data.local.ChecklistItemDao
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceDao
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionDao
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject

class InspectionRepository @Inject constructor(
    private val inspectionDao: InspectionDao,
    private val checklistItemDao: ChecklistItemDao,
    private val evidenceDao: EvidenceDao,
    private val syncRepository: SyncRepository
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

    suspend fun addEvidence(
        inspectionId: String,
        checklistItemId: String?,
        type: String,
        localUri: String?,
        note: String?
    ) {
        val evidenceId = UUID.randomUUID().toString()

        val evidence = EvidenceEntity(
            id = evidenceId,
            inspectionId = inspectionId,
            checklistItemId = checklistItemId,
            type = type,
            localUri = localUri,
            note = note,
            capturedAt = System.currentTimeMillis(),
            syncStatus = "QUEUED"
        )

        // 1. Save the actual evidence locally.
        evidenceDao.insertEvidence(evidence)

        // 2. Create a durable sync operation.
        syncRepository.queueEvidence(evidenceId)
    }

    suspend fun deleteInspection(
        inspectionId: String
    ) {
        checklistItemDao.deleteForInspection(inspectionId)
        evidenceDao.deleteForInspection(inspectionId)
        inspectionDao.deleteInspection(inspectionId)
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