package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.ui.camera.CameraPermissionScreen
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for the camera permission-denied state.
 */
@RunWith(AndroidJUnit4::class)
class CameraPermissionDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deniedState_explainsWhyAndOffersRecovery() {
        composeRule.setContent {
            FieldProofTheme {
                CameraPermissionScreen(
                    onRequestPermission = {},
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("Camera permission required").assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
    }

    @Test
    fun allowCamera_invokesPermissionRequest() {
        var requested = false
        composeRule.setContent {
            FieldProofTheme {
                CameraPermissionScreen(
                    onRequestPermission = { requested = true },
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithText("Allow camera").performClick()

        assertTrue(requested)
    }

    @Test
    fun notNow_dismissesWithoutRequesting() {
        var closed = false
        var requested = false
        composeRule.setContent {
            FieldProofTheme {
                CameraPermissionScreen(
                    onRequestPermission = { requested = true },
                    onClose = { closed = true }
                )
            }
        }

        composeRule.onNodeWithText("Not now").performClick()

        assertTrue(closed)
        org.junit.Assert.assertFalse(requested)
    }

    @Test
    fun permanentlyDenied_offersOpenSettingsInsteadOfDeadEnd() {
        var openedSettings = false
        var requested = false
        composeRule.setContent {
            FieldProofTheme {
                CameraPermissionScreen(
                    onRequestPermission = { requested = true },
                    onClose = {},
                    permanentlyDenied = true,
                    onOpenSettings = { openedSettings = true }
                )
            }
        }

        composeRule.onNodeWithText("Open settings").assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera").assertDoesNotExist()

        composeRule.onNodeWithText("Open settings").performClick()

        assertTrue(openedSettings)
        org.junit.Assert.assertFalse(requested)
    }
}
