package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.ui.components.ChecklistItemRow
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for checklist outcome capture and its evidence state.
 */
@RunWith(AndroidJUnit4::class)
class ChecklistCompletionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun item(
        required: Boolean = true,
        completed: Boolean = false,
        outcome: String = ChecklistItemEntity.OUTCOME_NOT_STARTED
    ) = ChecklistItemEntity(
        id = "c1",
        inspectionId = "i1",
        title = "Fire extinguisher present",
        required = required,
        completed = completed,
        outcome = outcome
    )

    @Test
    fun selectingPass_reportsOutcome() {
        var selected: String? = null
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onOutcomeChange = { selected = it },
                    onNoteChange = {},
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Fire extinguisher present").assertIsDisplayed()
        composeRule.onNodeWithText("Pass").performClick()

        assertEquals(ChecklistItemEntity.OUTCOME_PASS, selected)
    }

    @Test
    fun requiredItemWithoutOutcome_showsOutcomeNeeded() {
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Outcome needed").assertIsDisplayed()
    }

    @Test
    fun savingBlankNote_reportsNull() {
        var note: String? = "unset"
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onOutcomeChange = {},
                    onNoteChange = { note = it },
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Item note").performClick()
        composeRule.onNodeWithText("Save").performClick()

        assertEquals(null, note)
    }

    @Test
    fun requiredItemWithoutPhoto_showsMissingEvidenceAndAddAction() {
        var addEvidence = false
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = { addEvidence = true }
                )
            }
        }

        composeRule.onNodeWithText("Required").assertIsDisplayed()
        composeRule.onNodeWithText("Evidence missing").assertIsDisplayed()
        composeRule.onNodeWithText("Add evidence").performClick()

        assertTrue(addEvidence)
    }

    @Test
    fun requiredItemWithSyncedPhoto_showsAttachedAndHidesAddAction() {
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(completed = true, outcome = ChecklistItemEntity.OUTCOME_PASS),
                    hasSyncedPhoto = true,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Photo attached").assertIsDisplayed()
        composeRule.onNodeWithText("Add evidence").assertDoesNotExist()
    }

    @Test
    fun optionalItem_neverShowsMissingOrAddAction() {
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(required = false),
                    hasSyncedPhoto = false,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = null
                )
            }
        }

        composeRule.onNodeWithText("Optional").assertIsDisplayed()
        composeRule.onNodeWithText("Evidence missing").assertDoesNotExist()
        composeRule.onNodeWithText("Add evidence").assertDoesNotExist()
    }

    @Test
    fun serverFlaggedItem_showsFieldLevelServerValidation() {
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(completed = true, outcome = ChecklistItemEntity.OUTCOME_PASS),
                    hasSyncedPhoto = true,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = {},
                    serverMissingEvidence = true
                )
            }
        }

        composeRule.onNodeWithText("Server requires evidence").assertIsDisplayed()
    }

    @Test
    fun unFlaggedItem_hidesServerValidation() {
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onOutcomeChange = {},
                    onNoteChange = {},
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Server requires evidence").assertDoesNotExist()
    }
}
