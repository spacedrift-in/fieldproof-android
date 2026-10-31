package com.spacedrift.fieldproof.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.ui.camera.CameraErrorScreen
import com.spacedrift.fieldproof.ui.camera.CaptureHeader
import com.spacedrift.fieldproof.ui.camera.CapturedPreview
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for the camera capture navigation states: the
 * interactive close affordance, the post-capture review actions and the
 * explicit camera-error recovery state.
 */
@RunWith(AndroidJUnit4::class)
class CameraStateDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureHeader_closeIsInteractive() {
        var closed = false

        composeRule.setContent {
            FieldProofTheme {
                CaptureHeader(
                    title = "Emergency exit is unobstructed",
                    requiresPhoto = true,
                    onClose = { closed = true }
                )
            }
        }

        composeRule.onNodeWithText("Required photo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()

        assertTrue(closed)
    }

    @Test
    fun capturedPreview_offersRetakeAndUse_andClose() {
        var retook = false
        var used = false
        var closed = false

        composeRule.setContent {
            FieldProofTheme {
                CapturedPreview(
                    uri = Uri.parse("file:///fieldproof/does-not-exist.jpg"),
                    onRetake = { retook = true },
                    onUse = { used = true },
                    onClose = { closed = true }
                )
            }
        }

        composeRule.onNodeWithText("Photo preview unavailable").assertIsDisplayed()

        composeRule.onNodeWithText("Retake").performClick()
        assertTrue(retook)

        composeRule.onNodeWithText("Use photo").performClick()
        assertTrue(used)

        composeRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun cameraError_showsReason_andRetryRecovers() {
        var retried = false
        var closed = false

        composeRule.setContent {
            FieldProofTheme {
                CameraErrorScreen(
                    reason = "no back camera",
                    onRetry = { retried = true },
                    onClose = { closed = true }
                )
            }
        }

        composeRule.onNodeWithText("Camera unavailable").assertIsDisplayed()
        composeRule.onNodeWithText(
            "The camera could not be started: no back camera. Try again, or close and add a written note instead."
        ).assertIsDisplayed()

        composeRule.onNodeWithText("Try again").performClick()
        assertTrue(retried)
        assertFalse(closed)

        composeRule.onNodeWithText("Close").performClick()
        assertTrue(closed)
    }
}
