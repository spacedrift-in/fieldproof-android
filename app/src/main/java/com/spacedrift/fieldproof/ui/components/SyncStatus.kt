package com.spacedrift.fieldproof.ui.components

import com.spacedrift.fieldproof.data.local.SyncOperationEntity

enum class SyncState { CHECKING, OFFLINE, SYNCING, SYNCED, ATTENTION }

data class SyncSummary(
    val state: SyncState,
    val pending: Int,
    val failed: Int,
    val conflict: Int,
    val synced: Int,
    val title: String,
    val body: String
)

fun deriveSyncSummary(
    operations: List<SyncOperationEntity>,
    online: Boolean?
): SyncSummary {
    val pending = operations.count {
        it.status == "QUEUED" || it.status == "UPLOADING"
    }
    val failed = operations.count { it.status == "FAILED" }
    val conflict = operations.count { it.status == "CONFLICT" }
    val synced = operations.count { it.status == "SYNCED" }

    return when {
        failed + conflict > 0 ->
            SyncSummary(
                state = SyncState.ATTENTION,
                pending = pending,
                failed = failed,
                conflict = conflict,
                synced = synced,
                title = "Sync needs attention",
                body = listOfNotNull(
                    conflict.takeIf { it > 0 }?.let { "$it item conflict" },
                    failed.takeIf { it > 0 }?.let { "$it item failed" }
                ).joinToString(" · ")
            )

        online == false ->
            SyncSummary(
                state = SyncState.OFFLINE,
                pending = pending,
                failed = failed,
                conflict = conflict,
                synced = synced,
                title = "Offline",
                body = if (pending > 0) {
                    "$pending item(s) saved on this device"
                } else {
                    "Changes saved on this device"
                }
            )

        pending > 0 ->
            SyncSummary(
                state = SyncState.SYNCING,
                pending = pending,
                failed = failed,
                conflict = conflict,
                synced = synced,
                title = "Syncing…",
                body = "$pending item(s) waiting"
            )

        online == null ->
            SyncSummary(
                state = SyncState.CHECKING,
                pending = pending,
                failed = failed,
                conflict = conflict,
                synced = synced,
                title = "Sync",
                body = "Checking network…"
            )

        else ->
            SyncSummary(
                state = SyncState.SYNCED,
                pending = pending,
                failed = failed,
                conflict = conflict,
                synced = synced,
                title = "Sync complete",
                body = "All inspection data uploaded"
            )
    }
}