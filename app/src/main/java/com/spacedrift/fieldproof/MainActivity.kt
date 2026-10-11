package com.spacedrift.fieldproof

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.ui.inspection.InspectionViewModel
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import com.spacedrift.fieldproof.ui.camera.CameraCaptureScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FieldProofTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FieldProofApp()
                }
            }
        }
    }
}

@Composable
fun FieldProofApp(
    viewModel: InspectionViewModel = hiltViewModel()
) {
    val inspections by viewModel.inspections.collectAsState()
    val syncOperations by viewModel.syncOperations.collectAsState()

    var selectedInspection by remember {
        mutableStateOf<InspectionEntity?>(null)
    }

    if (selectedInspection == null) {
        InspectionListScreen(
            inspections = inspections,
            syncOperations = syncOperations,
            onRetrySync = {
                viewModel.retrySync()
            },
            onCreateInspection = {
                viewModel.createInspection(
                    title = "New Facility Inspection",
                    location = "Demo Site"
                )
            },
            onInspectionClick = {
                selectedInspection = it
            }
        )
    } else {
        InspectionDetailScreen(
            inspection = selectedInspection!!,
            viewModel = viewModel,
            onBack = {
                selectedInspection = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectionListScreen(
    inspections: List<InspectionEntity>,
    syncOperations: List<SyncOperationEntity>,
    onRetrySync: () -> Unit,
    onCreateInspection: () -> Unit,
    onInspectionClick: (InspectionEntity) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "FieldProof",
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                Spacer(modifier = Modifier.height(8.dp))

                SyncStatusCard(
                    operations = syncOperations,
                    onRetrySync = onRetrySync
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Inspections",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (inspections.isEmpty()) {

                item {
                    EmptyInspectionCard(
                        onCreateInspection = onCreateInspection
                    )
                }

            } else {

                items(
                    items = inspections,
                    key = { it.id }
                ) { inspection ->

                    InspectionCard(
                        inspection = inspection,
                        onClick = {
                            onInspectionClick(inspection)
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onCreateInspection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null
                        )

                        Spacer(modifier = Modifier.padding(4.dp))

                        Text("New inspection")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    operations: List<SyncOperationEntity>,
    onRetrySync: () -> Unit
) {
    val queued = operations.count { it.status == "QUEUED" }
    val uploading = operations.count { it.status == "UPLOADING" }
    val synced = operations.count { it.status == "SYNCED" }
    val failed = operations.count { it.status == "FAILED" }
    val conflict = operations.count { it.status == "CONFLICT" }

    val pending = queued + uploading

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.padding(8.dp))

                Column {
                    Text(
                        text = "Sync status",
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Changes are stored locally until they can sync.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (operations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SyncCount(label = "Queued", count = queued)
                    SyncCount(label = "Uploading", count = uploading)
                    SyncCount(label = "Synced", count = synced)
                    SyncCount(label = "Failed", count = failed)
                    SyncCount(label = "Conflict", count = conflict)
                }
            }

            val issue = operations
                .filter { it.status == "FAILED" || it.status == "CONFLICT" }
                .maxByOrNull { it.updatedAt }

            issue?.let { operation ->
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = operation.lastError
                        ?: "${syncStatusLabel(operation.status)} — manual retry may help.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (pending > 0 || conflict > 0 || failed > 0) {
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onRetrySync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null
                    )

                    Spacer(modifier = Modifier.padding(4.dp))

                    Text("Retry sync")
                }
            }
        }
    }
}

@Composable
private fun SyncCount(
    label: String,
    count: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

fun syncStatusLabel(status: String): String {
    return when (status) {
        "QUEUED" -> "Queued"
        "UPLOADING" -> "Uploading"
        "SYNCED" -> "Synced"
        "FAILED" -> "Failed"
        "CONFLICT" -> "Conflict"
        else -> status
    }
}

@Composable
private fun EmptyInspectionCard(
    onCreateInspection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = "No inspections yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Create an inspection to start capturing field evidence."
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onCreateInspection,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.padding(4.dp))

                Text("Create inspection")
            }
        }
    }
}

@Composable
private fun InspectionCard(
    inspection: InspectionEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = inspection.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                FilterChip(
                    selected = true,
                    onClick = {},
                    label = {
                        Text(inspection.status)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = inspection.location,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectionDetailScreen(
    inspection: InspectionEntity,
    viewModel: InspectionViewModel,
    onBack: () -> Unit
) {
    val checklist by viewModel
        .observeChecklist(inspection.id)
        .collectAsState(initial = emptyList())

    val evidence by viewModel
        .observeEvidence(inspection.id)
        .collectAsState(initial = emptyList())

    var noteText by remember {
        mutableStateOf("")
    }

    var showCamera by remember {
        mutableStateOf(false)
    }

    if (showCamera) {
        CameraCaptureScreen(
            onPhotoCaptured = { uri ->

                viewModel.addEvidence(
                    inspectionId = inspection.id,
                    checklistItemId = null,
                    type = "PHOTO",
                    localUri = uri.toString(),
                    note = null
                )

                showCamera = false
            },
            onClose = {
                showCamera = false
            }
        )

        return
    }

    val requiredItems = checklist.filter { it.required }
    val completedRequired = requiredItems.count { it.completed }
    val allRequiredComplete =
        requiredItems.isNotEmpty() &&
                completedRequired == requiredItems.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Inspection")
                },
                navigationIcon = {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = inspection.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = inspection.location,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                StatusChip(
                    status = inspection.status
                )
            }

            item {
                ChecklistProgressCard(
                    completed = completedRequired,
                    total = requiredItems.size
                )
            }

            item {
                Text(
                    text = "Checklist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            items(
                items = checklist,
                key = { it.id }
            ) { item ->

                ChecklistItemCard(
                    item = item,
                    onCheckedChange = { checked ->
                        viewModel.completeChecklistItem(
                            itemId = item.id,
                            completed = checked
                        )
                    }
                )
            }

            item {
                Text(
                    text = "Evidence",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                EvidenceCaptureCard(
                    noteText = noteText,
                    onNoteChanged = {
                        noteText = it
                    },
                    onAddNote = {
                        if (noteText.isNotBlank()) {
                            viewModel.addEvidence(
                                inspectionId = inspection.id,
                                checklistItemId = null,
                                type = "NOTE",
                                localUri = null,
                                note = noteText.trim()
                            )

                            noteText = ""
                        }
                    },
                    onTakePhoto = {
                        showCamera = true
                    }
                )
            }

            items(
                items = evidence,
                key = { it.id }
            ) { item ->

                EvidenceCard(item)
            }

            item {
                if (!allRequiredComplete) {

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null
                            )

                            Spacer(modifier = Modifier.padding(8.dp))

                            Text(
                                text = "Complete all required checklist items before submission."
                            )
                        }
                    }

                } else {

                    Button(
                        onClick = {
                            // Submission workflow will be connected later.
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Submit inspection")
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(
    status: String
) {
    FilterChip(
        selected = true,
        onClick = {},
        label = {
            Text(status)
        }
    )
}

@Composable
private fun ChecklistProgressCard(
    completed: Int,
    total: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null
            )

            Spacer(modifier = Modifier.padding(8.dp))

            Column {
                Text(
                    text = "Required checklist",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "$completed of $total completed"
                )
            }
        }
    }
}

@Composable
private fun ChecklistItemCard(
    item: ChecklistItemEntity,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            androidx.compose.material3.Checkbox(
                checked = item.completed,
                onCheckedChange = onCheckedChange
            )

            Spacer(modifier = Modifier.padding(4.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Medium
                )

                if (item.required) {
                    Text(
                        text = "Required",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceCaptureCard(
    noteText: String,
    onNoteChanged: (String) -> Unit,
    onAddNote: () -> Unit,
    onTakePhoto: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.padding(8.dp))

                Text(
                    text = "Capture evidence",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onTakePhoto,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.padding(4.dp))

                Text("Take photo")
            }

            Spacer(modifier = Modifier.height(12.dp))


            OutlinedTextField(
                value = noteText,
                onValueChange = onNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text("Evidence note")
                },
                placeholder = {
                    Text("Describe what you observed")
                },
                minLines = 3
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onAddNote,
                modifier = Modifier.fillMaxWidth(),
                enabled = noteText.isNotBlank()
            ) {
                Text("Save note")
            }
        }
    }
}

@Composable
private fun EvidenceCard(
    evidence: EvidenceEntity
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = evidence.type,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = syncStatusLabel(evidence.syncStatus),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            evidence.note?.let { note ->
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = note
                )
            }
        }
    }
}