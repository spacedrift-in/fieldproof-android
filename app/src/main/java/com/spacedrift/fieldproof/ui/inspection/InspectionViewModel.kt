package com.spacedrift.fieldproof.ui.inspection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.repository.InspectionRepository
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InspectionViewModel @Inject constructor(
    private val repository: InspectionRepository,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    val inspections: StateFlow<List<InspectionEntity>> =
        repository
            .observeInspections()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val syncOperations: StateFlow<List<SyncOperationEntity>> =
        syncRepository
            .observeOperations()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    fun retrySync() {
        syncScheduler.forceRetry()
    }

    fun observeChecklist(
        inspectionId: String
    ): Flow<List<ChecklistItemEntity>> {
        return repository.observeChecklist(inspectionId)
    }

    fun observeEvidence(
        inspectionId: String
    ): Flow<List<EvidenceEntity>> {
        return repository.observeEvidence(inspectionId)
    }

    fun createInspection(
        title: String,
        location: String
    ) {
        viewModelScope.launch {
            repository.createInspection(
                title = title,
                location = location
            )
        }
    }

    fun completeChecklistItem(
        itemId: String,
        completed: Boolean
    ) {
        viewModelScope.launch {
            repository.completeChecklistItem(
                itemId = itemId,
                completed = completed
            )
        }
    }

    fun addEvidence(
        inspectionId: String,
        checklistItemId: String?,
        type: String,
        localUri: String?,
        note: String?
    ) {
        viewModelScope.launch {
            repository.addEvidence(
                inspectionId = inspectionId,
                checklistItemId = checklistItemId,
                type = type,
                localUri = localUri,
                note = note
            )
        }
    }

    fun deleteInspection(
        inspectionId: String
    ) {
        viewModelScope.launch {
            repository.deleteInspection(inspectionId)
        }
    }
}