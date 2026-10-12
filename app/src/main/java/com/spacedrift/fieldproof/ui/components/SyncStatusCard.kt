package com.spacedrift.fieldproof.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.ui.theme.Dimens
import com.spacedrift.fieldproof.ui.theme.LocalStatusColors

/**
 * Compact, persistent sync status pill. Used in app bars so the sync state is
 * always visible without being intrusive.
 */
@Composable
fun SyncStatusPill(
    summary: SyncSummary,
    modifier: Modifier = Modifier
) {
    val tone = when (summary.state) {
        SyncState.SYNCED -> StatusTone.SUCCESS
        SyncState.ATTENTION -> StatusTone.ERROR
        SyncState.OFFLINE -> StatusTone.OFFLINE
        SyncState.SYNCING -> StatusTone.WARNING
        SyncState.CHECKING -> StatusTone.NEUTRAL
    }
    val label = when (summary.state) {
        SyncState.SYNCED -> "Synced"
        SyncState.ATTENTION -> "Attention"
        SyncState.OFFLINE -> "Offline"
        SyncState.SYNCING -> "Syncing"
        SyncState.CHECKING -> "Online"
    }
    StatusChip(
        label = label,
        tone = tone,
        modifier = modifier
    )
}

/**
 * Full-width sync status card shown at the top of the home screen.
 */
@Composable
fun SyncStatusCard(
    summary: SyncSummary,
    onRetrySync: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val statusColors = LocalStatusColors.current
    val tone = when (summary.state) {
        SyncState.SYNCED -> StatusTone.SUCCESS
        SyncState.ATTENTION -> StatusTone.ERROR
        SyncState.OFFLINE -> StatusTone.OFFLINE
        SyncState.SYNCING -> StatusTone.WARNING
        SyncState.CHECKING -> StatusTone.NEUTRAL
    }
    val colors = toneColors(tone, statusColors)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = colors.container
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = statusIcon(
                    when (summary.state) {
                        SyncState.SYNCED -> "SYNCED"
                        SyncState.ATTENTION ->
                            if (summary.conflict > 0) "CONFLICT" else "FAILED"
                        SyncState.OFFLINE -> "OFFLINE"
                        SyncState.SYNCING -> "SYNCING"
                        SyncState.CHECKING -> "QUEUED"
                    }
                ),
                contentDescription = null,
                tint = colors.onContainer
            )

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    text = summary.title,
                    color = colors.onContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = summary.body,
                    color = colors.onContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Conflicts need a deliberate per-item choice, so they do not get a
        // blanket "Retry sync" button here — only genuinely retryable failures do.
        if (summary.state == SyncState.ATTENTION && summary.failed > 0 && onRetrySync != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRetrySync,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.lg, vertical = Dimens.md),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = colors.onContainer,
                    contentColor = colors.container
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Sync,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.padding(4.dp))
                Text("Retry sync")
            }
        }
    }
}

/** Lightweight per-card sync badge. */
@Composable
fun SyncBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    StatusChip(
        label = syncStatusLabel(status),
        tone = statusTone(status),
        modifier = modifier
    )
}