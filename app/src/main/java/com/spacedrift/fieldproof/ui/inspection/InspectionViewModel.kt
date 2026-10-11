package com.spacedrift.fieldproof.ui.inspection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spacedrift.fieldproof.data.connectivity.ConnectivityObserver
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.data.remote.InspectionWorkflowClient
import com.spacedrift.fieldproof.data.remote.RemoteReport
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.data.repository.InspectionRepository
import com.spacedrift.fieldproof.data.repository.SyncRepository
import com.spacedrift.fieldproof.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkflowUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val serverStatus: String? = null,
    val serverVersion: Int? = null,
    val report: RemoteReport? = null
)

@HiltViewModel
class InspectionViewModel @Inject constructor(
    private val repository: InspectionRepository,
    private val syncRepository: SyncRepository,
    private val workflowClient: InspectionWorkflowClient,
    private val syncScheduler: SyncScheduler,
    private val connectivity: ConnectivityObserver
) : ViewModel() {

    val inspections: StateFlow<List<InspectionEntity>> =
        repository
            .observeInspections()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val homeSummaries: StateFlow<List<InspectionSummaryEntity>> =
        repository
            .observeInspectionSummaries()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    val isOnline: StateFlow<Boolean?> =
        connectivity
            .observe()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = null
            )

    private val _homeReady = MutableStateFlow(false)

    val homeReady: StateFlow<Boolean> = _homeReady.asStateFlow()

    val syncOperations: StateFlow<List<SyncOperationEntity>> =
        syncRepository
            .observeOperations()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    private val _workflow = MutableStateFlow(WorkflowUiState())

    val workflow: StateFlow<WorkflowUiState> = _workflow.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeInspectionSummaries().first()
            _homeReady.value = true
        }
    }

    fun openInspection(inspectionId: String) {
        _workflow.value = WorkflowUiState()
        refreshRemoteState(inspectionId)
    }

    fun refreshRemoteState(inspectionId: String) {
        viewModelScope.launch {
            when (val result = workflowClient.getInspection(inspectionId)) {
                is WorkflowResult.Success -> _workflow.update {
                    it.copy(
                        serverStatus = result.value.status,
                        serverVersion = result.value.version
                    )
                }

                is WorkflowResult.Conflict -> _workflow.update {
                    it.copy(message = result.message)
                }

                is WorkflowResult.Failed -> _workflow.update {
                    it.copy(message = result.message)
                }
            }
        }
    }

    fun submitInspection(inspectionId: String) {
        viewModelScope.launch {
            _workflow.update { it.copy(busy = true, message = null) }

            val currentVersion = currentServerVersion(inspectionId)

            when (val result = workflowClient.submitInspection(
                inspectionId = inspectionId,
                expectedVersion = currentVersion
            )) {
                is WorkflowResult.Success -> _workflow.update {
                    it.copy(
                        busy = false,
                        serverStatus = result.value.status,
                        serverVersion = result.value.version,
                        message = "Inspection submitted (${result.value.status})."
                    )
                }

                is WorkflowResult.Conflict -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }

                is WorkflowResult.Failed -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }
            }
        }
    }

    fun approveInspection(inspectionId: String, comment: String?) {
        viewModelScope.launch {
            _workflow.update { it.copy(busy = true, message = null) }

            val currentVersion = currentServerVersion(inspectionId)

            when (val result = workflowClient.reviewInspection(
                inspectionId = inspectionId,
                decision = "approved",
                comment = comment,
                reviewerId = "demo-reviewer",
                expectedVersion = currentVersion
            )) {
                is WorkflowResult.Success -> _workflow.update {
                    it.copy(
                        busy = false,
                        serverStatus = result.value.status,
                        serverVersion = result.value.version,
                        message = "Reviewed by demo-reviewer: ${result.value.review.decision}."
                    )
                }

                is WorkflowResult.Conflict -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }

                is WorkflowResult.Failed -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }
            }
        }
    }

    fun fetchReport(inspectionId: String) {
        viewModelScope.launch {
            _workflow.update { it.copy(busy = true, message = null) }

            when (val result = workflowClient.getReport(inspectionId)) {
                is WorkflowResult.Success -> _workflow.update {
                    it.copy(busy = false, report = result.value)
                }

                is WorkflowResult.Conflict -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }

                is WorkflowResult.Failed -> _workflow.update {
                    it.copy(busy = false, message = result.message)
                }
            }
        }
    }

    suspend fun downloadReportPdf(
        inspectionId: String
    ): WorkflowResult<ByteArray> = workflowClient.downloadReportPdf(inspectionId)

    private suspend fun currentServerVersion(
        inspectionId: String
    ): Int? {
        return when (val latest = workflowClient.getInspection(inspectionId)) {
            is WorkflowResult.Success -> {
                latest.value.version
            }

            is WorkflowResult.Conflict -> {
                _workflow.update { it.copy(message = latest.message) }
                _workflow.value.serverVersion
            }

            is WorkflowResult.Failed -> {
                _workflow.update { it.copy(message = latest.message) }
                _workflow.value.serverVersion
            }
        }
    }

    fun retrySync() {
        viewModelScope.launch {
            syncRepository.retryFailedOperations()
            syncScheduler.forceRetry()
        }
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
        location: String,
        onCreated: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val inspection = repository.createInspection(
                title = title,
                location = location
            )
            onCreated(inspection.id)
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