package com.spacedrift.fieldproof.ui.detail

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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spacedrift.fieldproof.R
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.remote.RemoteReport
import com.spacedrift.fieldproof.data.remote.WorkflowResult
import com.spacedrift.fieldproof.ui.components.ChecklistItemRow
import com.spacedrift.fieldproof.ui.components.ChecklistProgressRow
import com.spacedrift.fieldproof.ui.components.EmptyState
import com.spacedrift.fieldproof.ui.components.EvidenceCaptureCard
import com.spacedrift.fieldproof.ui.components.EvidenceRow
import com.spacedrift.fieldproof.ui.components.SectionHeader
import com.spacedrift.fieldproof.ui.components.StatusChip
import com.spacedrift.fieldproof.ui.components.StatusTone
import com.spacedrift.fieldproof.ui.components.SyncStatusCard
import com.spacedrift.fieldproof.ui.components.SyncSummary
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.ui.components.statusTone
import com.spacedrift.fieldproof.ui.components.syncStatusLabel
import com.spacedrift.fieldproof.ui.inspection.InspectionViewModel
import com.spacedrift.fieldproof.ui.report.ReportPdfExporter
import com.spacedrift.fieldproof.ui.theme.Dimens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionDetailScreen(
    inspectionId: String,
    viewModel: InspectionViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onCaptureForItem: (String?, String?) -> Unit
) {
    val inspections by viewModel.inspections.collectAsState()
    val inspection = inspections.firstOrNull { it.id == inspectionId }

    LaunchedEffect(inspectionId) {
        viewModel.openInspection(inspectionId)
    }

    if (inspection == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.inspection_heading)) },
                    navigationIcon = {
                        OutlinedIconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            EmptyState(
                icon = Icons.Outlined.Warning,
                title = "Inspection not found",
                body = "It may have been deleted on this device.",
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    DetailContent(
        inspection = inspection,
        inspectionId = inspectionId,
        viewModel = viewModel,
        onBack = onBack,
        onCaptureForItem = onCaptureForItem
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    inspection: InspectionEntity,
    inspectionId: String,
    viewModel: InspectionViewModel,
    onBack: () -> Unit,
    onCaptureForItem: (String?, String?) -> Unit
) {
    val checklist by viewModel
        .observeChecklist(inspectionId)
        .collectAsState(initial = emptyList())

    val evidence by viewModel
        .observeEvidence(inspectionId)
        .collectAsState(initial = emptyList())

    val syncOperations by viewModel.syncOperations.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()

    val workflow by viewModel.workflow.collectAsState()

    var noteText by remember(inspectionId) { mutableStateOf("") }
    var evidenceItemId by remember(inspectionId) { mutableStateOf<String?>(null) }

    val requiredItems = checklist.filter { it.required }
    val requiredEvidenceComplete = requiredItems.isNotEmpty() && requiredItems.all { item ->
        evidence.any { ev ->
            ev.checklistItemId == item.id &&
                ev.type == "PHOTO" &&
                ev.syncStatus == "SYNCED"
        }
    }
    val inspectionOperation = syncOperations.firstOrNull {
        it.entityType == "INSPECTION" && it.entityId == inspectionId
    }
    val inspectionSynced = inspectionOperation?.status == "SYNCED"
    val evidenceSynced = evidence.all { ev -> ev.syncStatus == "SYNCED" }
    val canSubmit = requiredEvidenceComplete && inspectionSynced && evidenceSynced

    val syncSummary: SyncSummary = deriveSyncSummary(syncOperations, isOnline)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.inspection_heading))
                },
                navigationIcon = {
                    OutlinedIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Dimens.pagePadding,
                vertical = Dimens.md
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            item {
                Column {
                    Text(
                        text = inspection.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = inspection.location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(Dimens.sm))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(label = inspection.status)

                        workflow.serverStatus?.let { serverStatus ->
                            StatusChip(label = serverStatus.take(20))
                        }
                    }
                }
            }

            item {
                SyncStatusCard(
                    summary = syncSummary,
                    onRetrySync = { viewModel.retrySync() }
                )
            }

            workflow.message?.let { message ->
                item {
                    WorkflowMessageCard(message = message)
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.lg)
                    ) {
                        Text(
                            text = "Progress",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(Dimens.md))
                        ChecklistProgressRow(
                            title = "Required evidence",
                            completed = requiredEvidenceCount(requiredItems, evidence),
                            total = requiredItems.size
                        )
                        Spacer(modifier = Modifier.height(Dimens.md))
                        ChecklistProgressRow(
                            title = "Checklist",
                            completed = checklist.count { it.completed },
                            total = checklist.size
                        )
                        Spacer(modifier = Modifier.height(Dimens.md))
                        InspectionMetaRow(
                            label = "Sync",
                            value = syncSummary.body
                        )
                        Spacer(modifier = Modifier.height(Dimens.sm))
                        InspectionMetaRow(
                            label = "Last updated",
                            value = com.spacedrift.fieldproof.ui.format.Format.relative(inspection.updatedAt)
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.checklist_heading),
                    trailing = {
                        if (requiredItems.isNotEmpty() && !requiredEvidenceComplete) {
                            StatusChip(
                                label = "Action needed",
                                tone = statusTone("FAILED")
                            )
                        }
                    }
                )
            }

            items(
                items = checklist,
                key = { it.id }
            ) { item ->
                ChecklistItemRow(
                    item = item,
                    hasSyncedPhoto = evidence.any {
                        it.checklistItemId == item.id && it.type == "PHOTO" && it.syncStatus == "SYNCED"
                    },
                    onCheckedChange = { checked ->
                        viewModel.completeChecklistItem(item.id, checked)
                    },
                    onAddEvidence = if (item.required) {
                        { onCaptureForItem(item.id, item.title) }
                    } else {
                        null
                    }
                )
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.evidence_heading),
                    trailing = {
                        if (evidence.any { it.syncStatus == "QUEUED" || it.syncStatus == "UPLOADING" }) {
                            StatusChip(label = syncStatusLabel("UPLOADING"))
                        }
                    }
                )
            }

            item {
                EvidenceCaptureCard(
                    checklistItems = checklist,
                    selectedItemId = evidenceItemId,
                    onItemSelected = { evidenceItemId = it },
                    noteText = noteText,
                    onNoteChanged = { noteText = it },
                    onAddNote = {
                        if (noteText.isNotBlank()) {
                            viewModel.addEvidence(
                                inspectionId = inspectionId,
                                checklistItemId = evidenceItemId,
                                type = "NOTE",
                                localUri = null,
                                note = noteText.trim()
                            )
                            noteText = ""
                        }
                    },
                    onTakePhoto = {
                        onCaptureForItem(
                            evidenceItemId,
                            checklist.firstOrNull { it.id == evidenceItemId }?.title
                        )
                    }
                )
            }

            if (evidence.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.PhotoCamera,
                        title = "No evidence yet",
                        body = "Attach a photo or note to every required checklist item before submitting.",
                        action = {
                            Button(onClick = {
                                onCaptureForItem(
                                    evidenceItemId,
                                    checklist.firstOrNull { it.id == evidenceItemId }?.title
                                )
                            }) {
                                Text(stringResource(R.string.action_take_photo))
                            }
                        }
                    )
                }
            } else {
                items(
                    items = evidence,
                    key = { it.id }
                ) { item ->
                    EvidenceRow(
                        evidence = item,
                        checklistTitle = checklist.firstOrNull { it.id == item.checklistItemId }?.title
                    )
                }
            }

            item {
                when (workflow.serverStatus) {
                    "SUBMITTED" -> {
                        WorkflowStatusCard(
                            title = "Awaiting review",
                            body = "The inspection was submitted and is ready for reviewer approval.",
                            tone = StatusTone.WARNING,
                            icon = Icons.Outlined.CheckCircle
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ReviewSignoffCard(
                            busy = workflow.busy,
                            onApprove = {
                                viewModel.approveInspection(inspectionId, null)
                            }
                        )
                    }

                    "APPROVED" -> {
                        WorkflowStatusCard(
                            title = stringResource(R.string.report_ready_title),
                            body = stringResource(R.string.report_ready_body),
                            tone = StatusTone.SUCCESS,
                            icon = Icons.Outlined.CheckCircle
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ReportCardSection(
                            report = workflow.report,
                            busy = workflow.busy,
                            onFetchReport = { viewModel.fetchReport(inspectionId) },
                            loadPdf = { viewModel.downloadReportPdf(inspectionId) }
                        )
                    }

                    else -> {
                        SubmitActionCard(
                            canSubmit = canSubmit,
                            busy = workflow.busy,
                            hint = submitHint(
                                canSubmit = canSubmit,
                                inspectionSynced = inspectionSynced,
                                requiredEvidenceComplete = requiredEvidenceComplete,
                                evidenceSynced = evidenceSynced
                            ),
                            inspectionSynced = inspectionSynced,
                            requiredEvidenceComplete = requiredEvidenceComplete,
                            evidenceSynced = evidenceSynced,
                            requiredCompleted = requiredEvidenceCount(requiredItems, evidence),
                            requiredTotal = requiredItems.size,
                            onSubmit = { viewModel.submitInspection(inspectionId) }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(Dimens.xl))
            }
        }
    }
}

private fun requiredEvidenceCount(
    requiredItems: List<ChecklistItemEntity>,
    evidence: List<EvidenceEntity>
): Int {
    return requiredItems.count { item ->
        evidence.any { ev ->
            ev.checklistItemId == item.id &&
                ev.type == "PHOTO" &&
                ev.syncStatus == "SYNCED"
        }
    }
}

@Composable
private fun InspectionMetaRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = Dimens.md)
        )
    }
}

private fun submitHint(
    canSubmit: Boolean,
    inspectionSynced: Boolean,
    requiredEvidenceComplete: Boolean,
    evidenceSynced: Boolean
): String? {
    if (canSubmit) return null
    return when {
        !inspectionSynced ->
            "The inspection must sync first. Wait for sync or tap Retry sync."
        !requiredEvidenceComplete ->
            "Attach a photo to every required checklist item before submitting."
        !evidenceSynced ->
            "Wait for all evidence to sync, then try again."
        else -> "Not ready to submit yet."
    }
}

@Composable
internal fun WorkflowMessageCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(Dimens.lg),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun SubmitActionCard(
    canSubmit: Boolean,
    busy: Boolean,
    hint: String?,
    inspectionSynced: Boolean,
    requiredEvidenceComplete: Boolean,
    evidenceSynced: Boolean,
    requiredCompleted: Int,
    requiredTotal: Int,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (canSubmit) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (canSubmit) Icons.Outlined.CloudDone else Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = if (canSubmit) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    text = if (canSubmit) "Ready to submit" else "Not ready to submit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(Dimens.md))

            ReadinessRow(
                label = "Required photos attached",
                done = requiredEvidenceComplete,
                detail = "$requiredCompleted of $requiredTotal"
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
            ReadinessRow(
                label = "Evidence synced",
                done = evidenceSynced,
                detail = if (evidenceSynced) "All uploaded" else "Waiting to upload"
            )
            Spacer(modifier = Modifier.height(Dimens.sm))
            ReadinessRow(
                label = "Inspection synced",
                done = inspectionSynced,
                detail = if (inspectionSynced) "On server" else "Waiting to upload"
            )

            if (hint != null) {
                Spacer(modifier = Modifier.height(Dimens.md))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(Dimens.md))

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit && !busy
            ) {
                Text(if (busy) "Working…" else stringResource(R.string.submit_inspection))
            }
        }
    }
}

@Composable
private fun ReadinessRow(
    label: String,
    done: Boolean,
    detail: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Outlined.Check else Icons.Outlined.Warning,
            contentDescription = null,
            tint = if (done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.padding(6.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ReviewSignoffCard(
    busy: Boolean,
    onApprove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.lg)
        ) {
            Text(
                text = stringResource(R.string.reviewer_signoff),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Review as Demo Reviewer. This is a demo — it is not real authentication or certification.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.md))

            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.padding(4.dp))
                Text(
                    if (busy) "Submitting…" else stringResource(R.string.approve_demo_signoff)
                )
            }
        }
    }
}

@Composable
internal fun ReportCardSection(
    report: RemoteReport?,
    busy: Boolean,
    onFetchReport: () -> Unit,
    loadPdf: suspend () -> WorkflowResult<ByteArray>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pdfBusy by remember { mutableStateOf(false) }
    var pdfMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                }
                pdfMessage = context.getString(R.string.report_pdf_exported)
            } catch (exception: Exception) {
                pdfMessage = context.getString(
                    R.string.report_pdf_failed,
                    exception.message ?: "unknown error"
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.lg)
        ) {
            Text(
                text = stringResource(R.string.reviewed_report),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.demo_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.md))

            Button(
                onClick = onFetchReport,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Text(if (busy) "Loading…" else "Fetch reviewed report")
            }

            report?.let { currentReport ->
                Spacer(modifier = Modifier.height(Dimens.md))
                ReportBody(report = currentReport)

                Spacer(modifier = Modifier.height(Dimens.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                pdfBusy = true
                                pdfMessage = null
                                when (val result = loadPdf()) {
                                    is WorkflowResult.Success -> {
                                        val file = ReportPdfExporter.writeToCache(
                                            context,
                                            currentReport.inspection_id,
                                            result.value
                                        )
                                        if (!ReportPdfExporter.open(context, file)) {
                                            pdfMessage = context.getString(
                                                R.string.report_pdf_no_viewer
                                            )
                                        }
                                    }

                                    is WorkflowResult.Conflict -> pdfMessage = result.message
                                    is WorkflowResult.Failed -> pdfMessage = result.message
                                }
                                pdfBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !pdfBusy
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            if (pdfBusy) {
                                stringResource(R.string.report_pdf_opening)
                            } else {
                                stringResource(R.string.report_pdf_open)
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                pdfBusy = true
                                pdfMessage = null
                                when (val result = loadPdf()) {
                                    is WorkflowResult.Success -> {
                                        pendingExport = result.value
                                        exportLauncher.launch(
                                            ReportPdfExporter.suggestedFileName(
                                                currentReport.inspection_id
                                            )
                                        )
                                    }

                                    is WorkflowResult.Conflict -> pdfMessage = result.message
                                    is WorkflowResult.Failed -> pdfMessage = result.message
                                }
                                pdfBusy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !pdfBusy
                    ) {
                        Icon(Icons.Filled.FileDownload, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(stringResource(R.string.export_pdf))
                    }
                }

                pdfMessage?.let { message ->
                    Spacer(modifier = Modifier.height(Dimens.sm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReportBody(report: RemoteReport) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = report.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text("Location: ${report.location}", style = MaterialTheme.typography.bodyMedium)
        Text("Inspection ID: ${report.inspection_id}", style = MaterialTheme.typography.bodySmall)
        Text(
            "Status: ${report.status} · Version ${report.version}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Generated: ${report.generated_at}", style = MaterialTheme.typography.bodySmall)

        report.review?.let { review ->
            Text(
                "Reviewed by ${review.reviewer_id} · ${review.decision} · version ${review.inspection_version}",
                style = MaterialTheme.typography.bodyMedium
            )
            review.comment?.let { comment ->
                Text("Comment: $comment", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            "Summary: ${report.summary.passed} passed / ${report.summary.total_items} total · ${report.summary.evidence_count} evidence",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(4.dp))

        report.checklist.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = item.status,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
internal fun WorkflowStatusCard(
    title: String,
    body: String,
    tone: com.spacedrift.fieldproof.ui.components.StatusTone,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val colors = com.spacedrift.fieldproof.ui.components.toneColors(
        tone,
        com.spacedrift.fieldproof.ui.theme.LocalStatusColors.current
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onContainer
            )
            Spacer(modifier = Modifier.padding(10.dp))
            Column {
                Text(
                    text = title,
                    color = colors.onContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = body,
                    color = colors.onContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}