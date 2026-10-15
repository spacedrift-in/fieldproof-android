package com.spacedrift.fieldproof.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spacedrift.fieldproof.R
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.ui.components.EmptyState
import com.spacedrift.fieldproof.ui.components.InspectionCard
import com.spacedrift.fieldproof.ui.components.LoadingState
import com.spacedrift.fieldproof.ui.components.StatusChip
import com.spacedrift.fieldproof.ui.components.SyncStatusCard
import com.spacedrift.fieldproof.ui.components.SyncSummary
import com.spacedrift.fieldproof.ui.components.SyncStatusPill
import com.spacedrift.fieldproof.ui.format.Format
import com.spacedrift.fieldproof.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    ready: Boolean,
    summaries: List<InspectionSummaryEntity>,
    syncOperations: List<SyncOperationEntity>,
    syncSummary: SyncSummary,
    onRetrySync: () -> Unit,
    onCreateInspection: (title: String, location: String) -> Unit,
    onOpenInspection: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.home_title),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    SyncStatusPill(
                        summary = syncSummary,
                        modifier = Modifier.padding(end = Dimens.lg)
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_inspection)
                )
            }
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                horizontal = Dimens.pagePadding,
                vertical = Dimens.md
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SyncStatusCard(
                    summary = syncSummary,
                    onRetrySync = onRetrySync
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.inspections_heading),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (!ready) {
                item {
                    LoadingState(label = stringResource(R.string.loading_inspections))
                }
            } else if (summaries.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(R.string.empty_inspections_title),
                        body = stringResource(R.string.empty_inspections_body),
                        action = {
                            TextButton(onClick = { showCreateDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.padding(4.dp))
                                Text(stringResource(R.string.create_inspection))
                            }
                        }
                    )
                }
            } else {
                items(
                    items = summaries,
                    key = { it.id }
                ) { inspection ->
                    InspectionCard(
                        inspection = inspection,
                        syncBadgeLabel = inspectionSyncBadgeLabel(
                            inspectionId = inspection.id,
                            operations = syncOperations
                        ),
                        onClick = {
                            onOpenInspection(inspection.id)
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Last updated",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (summaries.isEmpty()) {
                        Text(
                            text = "—",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        summaries.maxByOrNull { it.updatedAt }?.let { latest ->
                            Text(
                                text = Format.relative(latest.updatedAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateInspectionDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, location ->
                showCreateDialog = false
                onCreateInspection(title, location)
            }
        )
    }
}

@Composable
private fun inspectionSyncBadgeLabel(
    inspectionId: String,
    operations: List<SyncOperationEntity>
): String? {
    val inspectionOps = operations.filter { it.entityId == inspectionId }
    val inspectionSynced = inspectionOps.any {
        it.entityType == "INSPECTION" && it.status == "SYNCED"
    }
    if (!inspectionSynced) {
        if (inspectionOps.none { it.entityType == "INSPECTION" }) return null
        val status = inspectionOps.first { it.entityType == "INSPECTION" }.status
        return when (status) {
            "QUEUED", "UPLOADING" -> "Syncing"
            "FAILED" -> "Failed"
            "CONFLICT" -> "Conflict"
            else -> "Queued"
        }
    }
    val evidenceOps = operations.filter { it.entityId == inspectionId && it.entityType == "EVIDENCE" }
    if (evidenceOps.any { it.status == "FAILED" }) return "Failed"
    if (evidenceOps.any { it.status == "CONFLICT" }) return "Conflict"
    if (evidenceOps.any { it.status == "QUEUED" || it.status == "UPLOADING" }) return "Syncing"
    return "Synced"
}

@Composable
private fun CreateInspectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    val defaultTitle = stringResource(R.string.field_title_default)
    val defaultLocation = stringResource(R.string.field_location_default)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.create_dialog_title))
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.create_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(Dimens.lg))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    placeholder = { Text(defaultTitle) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Dimens.md))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(stringResource(R.string.field_location)) },
                    placeholder = { Text(defaultLocation) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        title.trim().ifBlank { defaultTitle },
                        location.trim().ifBlank { defaultLocation }
                    )
                }
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}