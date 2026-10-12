package com.spacedrift.fieldproof.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.domain.conflict.ServerConflictParser
import com.spacedrift.fieldproof.ui.format.Format
import com.spacedrift.fieldproof.ui.theme.Dimens

/**
 * User-facing description of a conflicted sync operation, including the local
 * copy so the user can decide which version to keep.
 */
data class ConflictPresentation(
    val operationId: String,
    val entityType: String,
    val title: String,
    val detail: String,
    val localSummary: String?,
    val error: String?,
    val serverDetail: String?,
    val serverConflict: ServerConflict?,
    val updatedAt: Long
)

/**
 * Pure mapping from a conflicted [SyncOperationEntity] plus the current local
 * records to the strings shown to the user. The operation's persisted 409
 * payload (if any) is parsed through [ServerConflictParser], so structured
 * server detail survives to the UI. Kept free of Android types so it can be
 * unit-tested directly.
 */
fun describeConflict(
    operation: SyncOperationEntity,
    evidence: List<EvidenceEntity>,
    inspection: InspectionEntity?
): ConflictPresentation {
    val serverConflict = operation.conflictDetail
        ?.let { ServerConflictParser.parse(it) }
    val serverDetail = serverDetailFor(serverConflict)

    return when (operation.entityType) {
        "INSPECTION" -> {
            val title = inspection?.title ?: "Inspection"
            val location = inspection?.location
            ConflictPresentation(
                operationId = operation.id,
                entityType = operation.entityType,
                title = "Inspection “$title”",
                detail = buildString {
                    append("The server already has an inspection with this id and ")
                    append("different details.")
                    if (!location.isNullOrBlank()) {
                        append(" Local location: $location.")
                    }
                },
                localSummary = inspection?.let { "Local: ${it.title} · ${it.location}" },
                error = operation.lastError,
                serverDetail = serverDetail,
                serverConflict = serverConflict,
                updatedAt = operation.updatedAt
            )
        }

        "EVIDENCE" -> {
            val local = evidence.firstOrNull { it.id == operation.entityId }
            val kind = when (local?.type) {
                "PHOTO" -> "Photo evidence"
                "NOTE" -> "Note evidence"
                else -> "Evidence"
            }
            ConflictPresentation(
                operationId = operation.id,
                entityType = operation.entityType,
                title = kind,
                detail = "This evidence conflicts with an item already on the server.",
                localSummary = local?.let { evidenceSummary(it) },
                error = operation.lastError,
                serverDetail = serverDetail,
                serverConflict = serverConflict,
                updatedAt = operation.updatedAt
            )
        }

        else -> ConflictPresentation(
            operationId = operation.id,
            entityType = operation.entityType,
            title = "${operation.entityType.lowercase()} ${operation.operationType.lowercase()}",
            detail = "This change conflicts with the server.",
            localSummary = null,
            error = operation.lastError,
            serverDetail = serverDetail,
            serverConflict = serverConflict,
            updatedAt = operation.updatedAt
        )
    }
}

private fun serverDetailFor(conflict: ServerConflict?): String? {
    return when (conflict) {
        is ServerConflict.Version ->
            "Server is at version ${conflict.currentVersion} (${conflict.currentStatus})."

        is ServerConflict.MissingRequiredEvidence ->
            "Missing required evidence for ${conflict.missingChecklistItemIds.size} item(s)."

        else -> null
    }
}

private fun evidenceSummary(evidence: EvidenceEntity): String {
    val note = evidence.note?.trim()
    if (!note.isNullOrEmpty()) {
        return "Local note: $note"
    }
    return when (evidence.type) {
        "PHOTO" -> "Local photo, captured ${Format.relative(evidence.capturedAt)}"
        else -> "Local evidence, captured ${Format.relative(evidence.capturedAt)}"
    }
}

/**
 * Presents each conflicted operation with its details and the two deliberate
 * resolution choices. Local work is retained by [onKeepLocal] and [onUseServer]
 * (see ``ConflictResolver``); nothing is discarded implicitly.
 */
@Composable
fun ConflictResolutionCard(
    conflicts: List<ConflictPresentation>,
    onKeepLocal: (String) -> Unit,
    onUseServer: (String) -> Unit,
    busyOperationId: String? = null,
    modifier: Modifier = Modifier
) {
    if (conflicts.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = toneColors(
                StatusTone.ERROR,
                com.spacedrift.fieldproof.ui.theme.LocalStatusColors.current
            ).container
        )
    ) {
        Column(modifier = Modifier.padding(Dimens.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Resolve sync conflict",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "These changes conflict with the server. Your local copy " +
                    "is kept until you choose. Nothing is discarded automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            conflicts.forEach { conflict ->
                Spacer(modifier = Modifier.height(Dimens.md))
                ConflictRow(
                    conflict = conflict,
                    busy = conflict.operationId == busyOperationId,
                    onKeepLocal = { onKeepLocal(conflict.operationId) },
                    onUseServer = { onUseServer(conflict.operationId) }
                )
            }
        }
    }
}

@Composable
private fun ConflictRow(
    conflict: ConflictPresentation,
    busy: Boolean,
    onKeepLocal: () -> Unit,
    onUseServer: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(Dimens.md)) {
            Text(
                text = conflict.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = conflict.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            conflict.localSummary?.let { local ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = local,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            conflict.serverDetail?.let { serverDetail ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = serverDetail,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
            conflict.error?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Last attempt ${Format.relative(conflict.updatedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onKeepLocal,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) {
                    Text("Keep local & retry")
                }
                OutlinedButton(
                    onClick = onUseServer,
                    modifier = Modifier.weight(1f),
                    enabled = !busy
                ) {
                    Text("Use server version")
                }
            }
        }
    }
}
