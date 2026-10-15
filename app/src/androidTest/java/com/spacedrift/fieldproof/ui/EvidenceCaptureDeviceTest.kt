package com.spacedrift.fieldproof.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.data.local.ChecklistItemEntity
import com.spacedrift.fieldproof.ui.camera.CaptureHeader
import com.spacedrift.fieldproof.ui.camera.CapturedPreview
import com.spacedrift.fieldproof.ui.components.EvidenceCaptureCard
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for the evidence capture flow states: the capture
 * header (required vs optional), the post-capture review state, and the
 * evidence composer.
 */
@RunWith(AndroidJUnit4::class)
class EvidenceCaptureDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureHeader_indicatesRequiredPhotoForItem() {
        composeRule.setContent {
            FieldProofTheme {
                CaptureHeader(title = "Fire extinguisher", requiresPhoto = true, onClose = {})
            }
        }

        composeRule.onNodeWithText("CAPTURE EVIDENCE").assertIsDisplayed()
        composeRule.onNodeWithText("Fire extinguisher").assertIsDisplayed()
        composeRule.onNodeWithText("Required photo").assertIsDisplayed()
    }

    @Test
    fun captureHeader_generalOptionalEvidence() {
        composeRule.setContent {
            FieldProofTheme {
                CaptureHeader(title = null, requiresPhoto = false, onClose = {})
            }
        }

        composeRule.onNodeWithText("General evidence").assertIsDisplayed()
        composeRule.onNodeWithText("Optional evidence").assertIsDisplayed()
    }

    @Test
    fun postCapture_reviewStateOffersRetakeAndUse() {
        var retook = false
        var used = false
        composeRule.setContent {
            FieldProofTheme {
                CapturedPreview(
                    uri = Uri.parse("file:///nonexistent/fieldproof/photo.jpg"),
                    onRetake = { retook = true },
                    onUse = { used = true },
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("Review photo").assertIsDisplayed()
        composeRule.onNodeWithText("Retake").performClick()
        assertTrue(retook)
        composeRule.onNodeWithText("Use photo").performClick()
        assertTrue(used)
    }

    @Test
    fun evidenceComposer_saveNoteDisabledUntilTextEntered() {
        var saved = false
        composeRule.setContent {
            var note by remember { mutableStateOf("") }
            FieldProofTheme {
                EvidenceCaptureCard(
                    checklistItems = listOf(
                        ChecklistItemEntity(
                            id = "c1",
                            inspectionId = "i1",
                            title = "Fire extinguisher"
                        )
                    ),
                    selectedItemId = null,
                    onItemSelected = {},
                    noteText = note,
                    onNoteChanged = { note = it },
                    onAddNote = { saved = true },
                    onTakePhoto = {}
                )
            }
        }

        composeRule.onNodeWithText("Capture evidence").assertIsDisplayed()
        composeRule.onNodeWithText("Take photo").assertIsDisplayed()
        composeRule.onNodeWithText("Save note").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction()).onFirst()
            .performTextInput("Extinguisher tagged and in date")

        composeRule.onNodeWithText("Save note").performClick()

        assertTrue(saved)
    }

    @Test
    fun evidenceComposer_selectingItemReportsIt() {
        var selected: String? = null
        composeRule.setContent {
            FieldProofTheme {
                EvidenceCaptureCard(
                    checklistItems = listOf(
                        ChecklistItemEntity(
                            id = "c1",
                            inspectionId = "i1",
                            title = "Fire extinguisher"
                        )
                    ),
                    selectedItemId = null,
                    onItemSelected = { selected = it },
                    noteText = "",
                    onNoteChanged = {},
                    onAddNote = {},
                    onTakePhoto = {}
                )
            }
        }

        composeRule.onNodeWithText("Fire extinguisher").performClick()

        assertEquals("c1", selected)
    }
}
