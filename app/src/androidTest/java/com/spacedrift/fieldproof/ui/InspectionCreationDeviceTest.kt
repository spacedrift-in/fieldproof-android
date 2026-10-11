package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.ui.home.HomeScreen
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for inspection creation from the home screen.
 */
@RunWith(AndroidJUnit4::class)
class InspectionCreationDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setHome(onCreate: (String, String) -> Unit = { _, _ -> }) {
        composeRule.setContent {
            FieldProofTheme {
                HomeScreen(
                    ready = true,
                    summaries = emptyList(),
                    syncOperations = emptyList(),
                    syncSummary = deriveSyncSummary(emptyList(), online = true),
                    onRetrySync = {},
                    onCreateInspection = onCreate,
                    onOpenInspection = {}
                )
            }
        }
    }

    @Test
    fun emptyState_offersCreateInspection() {
        setHome()
        composeRule.onNodeWithText("No inspections yet").assertIsDisplayed()
        composeRule.onNodeWithText("Create inspection").assertIsDisplayed()
    }

    @Test
    fun createFromDialog_sendsEnteredTitleAndLocation() {
        var created: Pair<String, String>? = null
        setHome(onCreate = { title, location -> created = title to location })

        composeRule.onNodeWithContentDescription("New inspection").performClick()
        composeRule.onNodeWithText("New inspection").assertIsDisplayed()

        composeRule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Pump Station 7")
        composeRule.onAllNodes(hasSetTextAction()).onLast().performTextInput("North Wing")

        composeRule.onNodeWithText("Create").performClick()

        assertEquals("Pump Station 7" to "North Wing", created)
    }

    @Test
    fun createWithBlankFields_usesDefaultTitleAndLocation() {
        var created: Pair<String, String>? = null
        setHome(onCreate = { title, location -> created = title to location })

        composeRule.onNodeWithContentDescription("New inspection").performClick()
        composeRule.onNodeWithText("Create").performClick()

        assertEquals("New Facility Inspection" to "Demo Site", created)
    }

    @Test
    fun cancelDialog_doesNotCreate() {
        var created = false
        setHome(onCreate = { _, _ -> created = true })

        composeRule.onNodeWithContentDescription("New inspection").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        org.junit.Assert.assertFalse(created)
        composeRule.onNodeWithText("New inspection").assertDoesNotExist()
    }
}
