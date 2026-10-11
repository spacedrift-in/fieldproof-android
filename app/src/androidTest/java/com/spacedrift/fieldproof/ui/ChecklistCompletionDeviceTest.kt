package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
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
 * Device/emulator UI tests for checklist item completion and its evidence state.
 */
@RunWith(AndroidJUnit4::class)
class ChecklistCompletionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun item(required: Boolean = true, completed: Boolean = false) = ChecklistItemEntity(
        id = "c1",
        inspectionId = "i1",
        title = "Fire extinguisher present",
        required = required,
        completed = completed
    )

    @Test
    fun togglingCheckbox_reportsNewCompletion() {
        var toggled: Boolean? = null
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onCheckedChange = { toggled = it },
                    onAddEvidence = {}
                )
            }
        }

        composeRule.onNodeWithText("Fire extinguisher present").assertIsDisplayed()
        composeRule.onNode(isToggleable()).performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun requiredItemWithoutPhoto_showsMissingEvidenceAndAddAction() {
        var addEvidence = false
        composeRule.setContent {
            FieldProofTheme {
                ChecklistItemRow(
                    item = item(),
                    hasSyncedPhoto = false,
                    onCheckedChange = {},
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
                    item = item(completed = true),
                    hasSyncedPhoto = true,
                    onCheckedChange = {},
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
                    onCheckedChange = {},
                    onAddEvidence = null
                )
            }
        }

        composeRule.onNodeWithText("Optional").assertIsDisplayed()
        composeRule.onNodeWithText("Evidence missing").assertDoesNotExist()
        composeRule.onNodeWithText("Add evidence").assertDoesNotExist()
    }
}
