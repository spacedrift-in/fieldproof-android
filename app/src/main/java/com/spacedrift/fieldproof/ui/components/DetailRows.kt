package com.spacedrift.fieldproof.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import com.spacedrift.fieldproof.R
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.ui.format.Format
import com.spacedrift.fieldproof.ui.theme.Dimens
import com.spacedrift.fieldproof.ui.theme.LocalStatusColors
import java.io.File

@Composable
fun ChecklistProgressRow(
    title: String,
    completed: Int,
    total: Int,
    showProgress: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (showProgress && total > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { completed.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        if (showProgress && total > 0) {
            Spacer(modifier = Modifier.width(Dimens.md))
            Text(
                text = "$completed / $total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChecklistItemRow(
    item: ChecklistItemEntity,
    hasSyncedPhoto: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onAddEvidence: (() -> Unit)?,
    modifier: Modifier = Modifier,
    serverMissingEvidence: Boolean = false
) {
    val statusColors = LocalStatusColors.current
    val open = statusTone("FAILED")
    val complete = statusTone("SYNCED")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.completed,
                onCheckedChange = onCheckedChange
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (item.required) {
                            stringResource(R.string.required_label)
                        } else {
                            stringResource(R.string.optional_label)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (hasSyncedPhoto) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = statusColors.success,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.photo_attached),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.success
                        )
                    } else if (item.required) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = statusColors.warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.evidence_missing),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.warning
                        )
                    }
                }

                if (serverMissingEvidence) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = statusColors.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.server_evidence_missing),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.error
                        )
                    }
                }
            }

            if (item.required && !hasSyncedPhoto && onAddEvidence != null) {
                TextButton(onClick = onAddEvidence) {
                    Text(stringResource(R.string.add_evidence))
                }
            }
        }
    }
}

@Composable
fun EvidenceCaptureCard(
    checklistItems: List<ChecklistItemEntity>,
    selectedItemId: String?,
    onItemSelected: (String?) -> Unit,
    noteText: String,
    onNoteChanged: (String) -> Unit,
    onAddNote: () -> Unit,
    onTakePhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Dimens.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    text = stringResource(R.string.capture_evidence),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(Dimens.md))

            if (checklistItems.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.applies_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedItemId == null,
                        onClick = { onItemSelected(null) },
                        label = { Text(stringResource(R.string.general)) }
                    )

                    checklistItems.forEach { item ->
                        FilterChip(
                            selected = selectedItemId == item.id,
                            onClick = { onItemSelected(item.id) },
                            label = { Text(item.title) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.md))
            }

            Button(
                onClick = onTakePhoto,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Text(stringResource(R.string.action_take_photo))
            }

            Spacer(modifier = Modifier.height(Dimens.md))

            OutlinedTextField(
                value = noteText,
                onValueChange = onNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.evidence_note_label)) },
                placeholder = {
                    Text(stringResource(R.string.evidence_note_placeholder))
                },
                minLines = 3
            )

            Spacer(modifier = Modifier.height(Dimens.md))

            Button(
                onClick = onAddNote,
                modifier = Modifier.fillMaxWidth(),
                enabled = noteText.isNotBlank()
            ) {
                Text(stringResource(R.string.action_save_note))
            }
        }
    }
}

@Composable
fun EvidenceRow(
    evidence: EvidenceEntity,
    checklistTitle: String?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    val statusColors = LocalStatusColors.current
    var showViewer by remember { mutableStateOf(false) }
    val isPhoto = evidence.type == "PHOTO" && !evidence.localUri.isNullOrBlank()

    if (showViewer && isPhoto) {
        PhotoViewerDialog(
            uriString = evidence.localUri!!,
            onDismiss = { showViewer = false }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.md),
            verticalAlignment = Alignment.Top
        ) {
            EvidenceThumb(
                evidence = evidence,
                onClick = if (isPhoto) {
                    { showViewer = true }
                } else {
                    null
                }
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimens.md)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (evidence.type == "PHOTO") {
                            "Photo evidence"
                        } else {
                            "Note evidence"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    SyncBadge(status = evidence.syncStatus)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Satisfies: ${checklistTitle ?: stringResource(R.string.general)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "Captured ${Format.relative(evidence.capturedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isPhoto) {
                    Text(
                        text = "Tap photo to view larger",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                evidence.note?.let { note ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (evidence.syncStatus == "FAILED" ||
                    evidence.syncStatus == "CONFLICT" ||
                    evidence.syncStatus == "SUPERSEDED"
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (evidence.syncStatus == "FAILED" && onRetry != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Upload failed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = statusColors.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }
                    } else {
                        Text(
                            text = when (evidence.syncStatus) {
                                "CONFLICT" ->
                                    "Conflicted on the server. Resolve it below — " +
                                        "your local copy is kept."
                                "SUPERSEDED" ->
                                    "Superseded by the server copy you chose to keep."
                                else -> "Upload failed. Tap Retry sync to try again."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (evidence.syncStatus == "SUPERSEDED") {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                statusColors.error
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewerDialog(
    uriString: String,
    onDismiss: () -> Unit
) {
    val bitmap = remember(uriString) { decodeSampledBitmap(uriString, maxDimension = 1600) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "Photo unavailable",
                        modifier = Modifier.padding(Dimens.xl)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.sm))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun EvidenceThumb(
    evidence: EvidenceEntity,
    onClick: (() -> Unit)?
) {
    val isPhoto = evidence.type == "PHOTO" && !evidence.localUri.isNullOrBlank()
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.action_view_photo),
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isPhoto) {
            val bitmap = remember(evidence.localUri) {
                decodeSampledBitmap(evidence.localUri!!)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.evidence_photo_desc),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                MissingPhotoIcon()
            }
        } else {
            Icon(
                imageVector = Icons.Outlined.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MissingPhotoIcon() {
    Icon(
        imageVector = Icons.Outlined.PhotoCamera,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun decodeSampledBitmap(uriString: String, maxDimension: Int = 256): Bitmap? {
    return try {
        val path = uriString.removePrefix("file://")
        if (!File(path).exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, options)
    } catch (_: Exception) {
        null
    }
}