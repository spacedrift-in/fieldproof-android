package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.ui.components.ConflictPresentation
import com.spacedrift.fieldproof.ui.components.ConflictResolutionCard
import com.spacedrift.fieldproof.ui.components.SyncStatusCard
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.data.local.SyncOperationEntity
import com.spacedrift.fieldproof.domain.conflict.ServerConflict
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for the conflict resolution flow: the user is shown
 * both versions and must make a deliberate choice, and nothing is discarded.
 */
@RunWith(AndroidJUnit4::class)
class ConflictResolutionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun presentation(
        operationId: String = "op-1",
        type: String = "EVIDENCE"
    ) = ConflictPresentation(
        operationId = operationId,
        entityType = type,
        title = "Note evidence",
        detail = "This evidence conflicts with an item already on the server.",
        localSummary = "Local note: Retest valve",
        error = "Idempotency key was reused with different content.",
        serverDetail = null,
        serverConflict = null,
        updatedAt = 1_000L
    )

    @Test
    fun conflictCard_showsBothLocalAndServerChoice_andKeepsLocalCopy() {
        composeRule.setContent {
            FieldProofTheme {
                ConflictResolutionCard(
                    conflicts = listOf(presentation()),
                    onKeepLocal = {},
                    onUseServer = {}
                )
            }
        }

        composeRule.onNodeWithText("Resolve sync conflict").assertIsDisplayed()
        composeRule.onNodeWithText("Note evidence").assertIsDisplayed()
        composeRule.onNodeWithText("Local note: Retest valve").assertIsDisplayed()
        composeRule.onNodeWithText("Keep local & retry").assertIsDisplayed()
        composeRule.onNodeWithText("Use server version").assertIsDisplayed()
    }

    @Test
    fun structuredServerDetail_isShownWhenPresent() {
        composeRule.setContent {
            FieldProofTheme {
                ConflictResolutionCard(
                    conflicts = listOf(
                        presentation().copy(
                            serverConflict = ServerConflict.Version(
                                message = "Version conflict",
                                currentVersion = 4,
                                currentStatus = "SUBMITTED",
                                rawDetail = "{}"
                            ),
                            serverDetail = "Server is at version 4 (SUBMITTED)."
                        )
                    ),
                    onKeepLocal = {},
                    onUseServer = {}
                )
            }
        }

        composeRule.onNodeWithText("Server is at version 4 (SUBMITTED).").assertIsDisplayed()
    }

    @Test
    fun keepLocal_clickFiresWithOperationId() {
        var chosen: String? = null
        composeRule.setContent {
            FieldProofTheme {
                ConflictResolutionCard(
                    conflicts = listOf(presentation(operationId = "op-42")),
                    onKeepLocal = { chosen = it },
                    onUseServer = {}
                )
            }
        }

        composeRule.onNodeWithText("Keep local & retry").performClick()

        assertEquals("op-42", chosen)
    }

    @Test
    fun useServer_clickFiresWithOperationId() {
        var chosen: String? = null
        composeRule.setContent {
            FieldProofTheme {
                ConflictResolutionCard(
                    conflicts = listOf(presentation(operationId = "op-7")),
                    onKeepLocal = {},
                    onUseServer = { chosen = it }
                )
            }
        }

        composeRule.onNodeWithText("Use server version").performClick()

        assertEquals("op-7", chosen)
    }

    @Test
    fun conflictOnlyAttention_pointsToInspectionWithoutBlanketRetry() {
        val conflict = SyncOperationEntity(
            id = "op-1",
            entityId = "e1",
            entityType = "EVIDENCE",
            operationType = "CREATE",
            status = "CONFLICT",
            createdAt = 1_000L,
            updatedAt = 1_000L
        )

        composeRule.setContent {
            FieldProofTheme {
                SyncStatusCard(
                    summary = deriveSyncSummary(listOf(conflict), online = true),
                    onRetrySync = {}
                )
            }
        }

        composeRule.onNodeWithText("Sync needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Retry sync").assertDoesNotExist()
    }
}
