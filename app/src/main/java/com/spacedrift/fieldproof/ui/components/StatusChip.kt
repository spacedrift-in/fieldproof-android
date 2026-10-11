package com.spacedrift.fieldproof.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spacedrift.fieldproof.ui.theme.LocalStatusColors
import com.spacedrift.fieldproof.ui.theme.StatusColors

enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL, OFFLINE }

data class ToneColors(
    val container: Color,
    val onContainer: Color
)

fun toneColors(
    tone: StatusTone,
    colors: StatusColors
): ToneColors {
    return when (tone) {
        StatusTone.SUCCESS -> ToneColors(colors.successContainer, colors.onSuccessContainer)
        StatusTone.WARNING -> ToneColors(colors.warningContainer, colors.onWarningContainer)
        StatusTone.ERROR -> ToneColors(colors.errorContainer, colors.onErrorContainer)
        StatusTone.OFFLINE -> ToneColors(colors.offline, colors.onOffline)
        StatusTone.NEUTRAL -> ToneColors(colors.neutral, colors.onNeutral)
    }
}

fun statusTone(status: String): StatusTone {
    return when (status.uppercase()) {
        "APPROVED", "SYNCED", "READY", "PASS", "COMPLETE", "COMPLETED" -> StatusTone.SUCCESS
        "SUBMITTED", "IN REVIEW", "QUEUED", "UPLOADING", "SYNCING", "PENDING" -> StatusTone.WARNING
        "FAILED", "CONFLICT" -> StatusTone.ERROR
        "OFFLINE" -> StatusTone.OFFLINE
        else -> StatusTone.NEUTRAL
    }
}

fun statusIcon(status: String): ImageVector {
    return when (status.uppercase()) {
        "APPROVED", "SYNCED", "READY", "SYNC COMPLETE" -> Icons.Outlined.CloudDone
        "SUBMITTED", "IN REVIEW", "QUEUED", "SYNCING", "PENDING" -> Icons.Outlined.CloudSync
        "FAILED" -> Icons.Outlined.SyncProblem
        "CONFLICT" -> Icons.Outlined.ErrorOutline
        "OFFLINE" -> Icons.Outlined.CloudOff
        "DRAFT" -> Icons.Outlined.HourglassEmpty
        else -> Icons.Outlined.CloudQueue
    }
}

fun syncStatusLabel(status: String): String {
    return when (status) {
        "QUEUED" -> "Queued"
        "UPLOADING" -> "Syncing"
        "SYNCED" -> "Synced"
        "FAILED" -> "Failed"
        "CONFLICT" -> "Conflict"
        else -> status
    }
}

@Composable
fun StatusChip(
    label: String,
    icon: ImageVector? = null,
    tone: StatusTone = statusTone(label),
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val colors = toneColors(tone, statusColors)

    Row(
        modifier = modifier
            .background(
                color = colors.container,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon ?: statusIcon(label),
            contentDescription = null,
            tint = colors.onContainer,
            modifier = Modifier
        )
        Text(
            text = label,
            color = colors.onContainer,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun CheckIcon() {
    Icon(
        imageVector = Icons.Outlined.CheckCircle,
        contentDescription = null
    )
}