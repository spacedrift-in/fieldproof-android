package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.data.local.EvidenceEntity
import com.spacedrift.fieldproof.data.local.InspectionSummaryEntity
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.ui.components.EvidenceRow
import com.spacedrift.fieldproof.ui.components.InspectionCard
import com.spacedrift.fieldproof.ui.components.SyncBadge
import com.spacedrift.fieldproof.ui.components.SyncStatusCard
import com.spacedrift.fieldproof.ui.components.SyncStatusPill
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for how sync state is surfaced across the app.
 */
@RunWith(AndroidJUnit4::class)
class SyncStateDisplayDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun inspectionOperation(status: String) = SyncOperationEntity(
        id = "op-$status",
        entityId = "i1",
        entityType = "INSPECTION",
        operationType = "CREATE",
        status = status,
        createdAt = 1_000L,
        updatedAt = 1_000L
    )

    @Test
    fun synced_showsCompleteMessage() {
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusCard(
                    summary = deriveSyncSummary(listOf(inspectionOperation("SYNCED")), online = true)
                )
            }
        }

        composeRule.onNodeWithText("Sync complete").assertIsDisplayed()
        composeRule.onNodeWithText("All inspection data uploaded").assertIsDisplayed()
    }

    @Test
    fun offline_showsSavedOnDeviceMessage() {
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusCard(
                    summary = deriveSyncSummary(listOf(inspectionOperation("QUEUED")), online = false)
                )
            }
        }

        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        composeRule.onNodeWithText("1 item(s) saved on this device").assertIsDisplayed()
    }

    @Test
    fun syncing_showsPendingCount() {
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusCard(
                    summary = deriveSyncSummary(listOf(inspectionOperation("UPLOADING")), online = true),
                    onRetrySync = {}
                )
            }
        }

        composeRule.onNodeWithText("Syncing…").assertIsDisplayed()
        composeRule.onNodeWithText("1 item(s) waiting").assertIsDisplayed()
        composeRule.onNodeWithText("Retry sync").assertDoesNotExist()
    }

    @Test
    fun attention_showsRetryActionThatFires() {
        var retried = false
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusCard(
                    summary = deriveSyncSummary(listOf(inspectionOperation("FAILED")), online = true),
                    onRetrySync = { retried = true }
                )
            }
        }

        composeRule.onNodeWithText("Sync needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Retry sync").performClick()

        assertTrue(retried)
    }

    @Test
    fun pill_showsOnlineWhileCheckingNetwork() {
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusPill(summary = deriveSyncSummary(emptyList(), online = null))
            }
        }

        composeRule.onNodeWithText("Online").assertIsDisplayed()
    }

    @Test
    fun pill_showsSyncedWhenUploaded() {
        composeRule.setContent {
            FieldProofTheme {
                SyncStatusPill(summary = deriveSyncSummary(listOf(inspectionOperation("SYNCED")), online = true))
            }
        }

        composeRule.onNodeWithText("Synced").assertIsDisplayed()
    }

    @Test
    fun inspectionCard_showsProgressAndSyncBadge() {
        composeRule.setContent {
            FieldProofTheme {
                InspectionCard(
                    inspection = InspectionSummaryEntity(
                        id = "i1",
                        title = "Facility A",
                        location = "Building 1",
                        status = "DRAFT",
                        createdAt = 1_000L,
                        updatedAt = 1_000L,
                        totalItems = 3,
                        completedItems = 1,
                        evidenceCount = 2
                    ),
                    syncBadgeLabel = "Syncing",
                    onClick = {}
                )
            }
        }

        composeRule.onNodeWithText("Facility A").assertIsDisplayed()
        composeRule.onNodeWithText("1/3 checklist").assertIsDisplayed()
        composeRule.onNodeWithText("Syncing").assertIsDisplayed()
    }

    @Test
    fun inspectionCard_opensOnClick() {
        var openedId: String? = null
        composeRule.setContent {
            FieldProofTheme {
                InspectionCard(
                    inspection = InspectionSummaryEntity(
                        id = "i1",
                        title = "Facility A",
                        location = "Building 1",
                        status = "DRAFT",
                        createdAt = 1_000L,
                        updatedAt = 1_000L,
                        totalItems = 0,
                        completedItems = 0,
                        evidenceCount = 0
                    ),
                    syncBadgeLabel = null,
                    onClick = { openedId = "i1" }
                )
            }
        }

        composeRule.onNodeWithText("Facility A").performClick()

        org.junit.Assert.assertEquals("i1", openedId)
    }

    @Test
    fun queuedBadge_showsQueued() {
        composeRule.setContent {
            FieldProofTheme {
                SyncBadge(status = "QUEUED")
            }
        }

        composeRule.onNodeWithText("Queued").assertIsDisplayed()
    }

    @Test
    fun uploadingBadge_showsUploading() {
        composeRule.setContent {
            FieldProofTheme {
                SyncBadge(status = "UPLOADING")
            }
        }

        composeRule.onNodeWithText("Uploading").assertIsDisplayed()
    }

    @Test
    fun conflictBadge_showsConflict() {
        composeRule.setContent {
            FieldProofTheme {
                SyncBadge(status = "CONFLICT")
            }
        }

        composeRule.onNodeWithText("Conflict").assertIsDisplayed()
    }

    @Test
    fun failedEvidence_offersRetryActionThatFires() {
        var retried = false
        composeRule.setContent {
            FieldProofTheme {
                EvidenceRow(
                    evidence = EvidenceEntity(
                        id = "ev1",
                        inspectionId = "i1",
                        checklistItemId = null,
                        type = "NOTE",
                        localUri = null,
                        note = "Observed a loose railing.",
                        capturedAt = 1_000L,
                        syncStatus = "FAILED"
                    ),
                    checklistTitle = null,
                    onRetry = { retried = true }
                )
            }
        }

        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()

        assertTrue(retried)
    }

    @Test
    fun queuedEvidence_offersNoRetryAction() {
        composeRule.setContent {
            FieldProofTheme {
                EvidenceRow(
                    evidence = EvidenceEntity(
                        id = "ev1",
                        inspectionId = "i1",
                        checklistItemId = null,
                        type = "NOTE",
                        localUri = null,
                        note = "Queued note.",
                        capturedAt = 1_000L,
                        syncStatus = "QUEUED"
                    ),
                    checklistTitle = null,
                    onRetry = { }
                )
            }
        }

        composeRule.onNodeWithText("Queued").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertDoesNotExist()
    }
}
