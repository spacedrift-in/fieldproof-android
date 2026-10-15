package com.spacedrift.fieldproof.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spacedrift.fieldproof.ui.components.deriveSyncSummary
import com.spacedrift.fieldproof.ui.home.HomeScreen
import com.spacedrift.fieldproof.ui.theme.FieldProofTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device/emulator UI tests for the home screen's first-frame loading state,
 * ensuring a cold start reads as *in progress* rather than *empty*.
 */
@RunWith(AndroidJUnit4::class)
class HomeLoadingStateDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setHome(ready: Boolean) {
        composeRule.setContent {
            FieldProofTheme {
                HomeScreen(
                    ready = ready,
                    summaries = emptyList(),
                    syncOperations = emptyList(),
                    syncSummary = deriveSyncSummary(emptyList(), online = true),
                    onRetrySync = {},
                    onCreateInspection = { _, _ -> },
                    onOpenInspection = {}
                )
            }
        }
    }

    @Test
    fun notReady_showsProgressIndicator() {
        setHome(ready = false)

        composeRule.onNodeWithTag("loading_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("Loading inspections…").assertIsDisplayed()
        composeRule.onNodeWithText("No inspections yet").assertDoesNotExist()
    }

    @Test
    fun readyWithNoData_showsEmptyStateNotLoading() {
        setHome(ready = true)

        composeRule.onNodeWithTag("loading_indicator").assertDoesNotExist()
        composeRule.onNodeWithText("No inspections yet").assertIsDisplayed()
    }
}
