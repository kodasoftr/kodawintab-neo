package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.koda.engine.KodaEngineClient
import com.example.service.KodaHubForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PermanentPrivilegeFlowTest {

    private val preActivateRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                // Simulate 1-time ADB already completed earlier AND app previously force-stopped
                context.getSharedPreferences("koda_engine_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("adb_permanently_activated", true)
                    .putInt("adb_server_uid", 2000)
                    .commit()
                KodaHubForegroundService.setUserTerminatedFlag(context, true)
                try {
                    base.evaluate()
                } finally {
                    context.getSharedPreferences("koda_engine_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                    KodaHubForegroundService.setUserTerminatedFlag(context, false)
                }
            }
        }
    }

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(preActivateRule).around(composeTestRule)

    @Test
    fun `clicking privilege status on Home after permanent activation never deactivates or shows ADB wait error`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeTestRule.waitForIdle()

        // Opening the app after Force-Stop clears user_terminated and restores active OTG mode
        assertFalse(KodaHubForegroundService.wasUserTerminated(context))
        assertTrue(KodaEngineClient(context).isPermanentlyActivated())

        // App opens directly to Home (not ActivationScreen)
        composeTestRule.onNodeWithTag("home_engine_status_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("privilege_status_screen").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodes(hasTestTag("verify_connection_button")).fetchSemanticsNodes().isEmpty())
        assertTrue(composeTestRule.onAllNodes(hasTestTag("error_popup_ok_button")).fetchSemanticsNodes().isEmpty())

        // Silent refresh button also never raises any error popup or disables privileges
        composeTestRule.onNodeWithTag("privileges_silent_reconnect_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.onAllNodes(hasTestTag("error_popup_ok_button")).fetchSemanticsNodes().isEmpty())
        assertTrue(KodaEngineClient(context).isPermanentlyActivated())

        // Navigate back and verify Home OTG permanent banner also routes cleanly to PrivilegeStatusScreen
        composeTestRule.onNodeWithTag("privileges_back_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_otg_permanent_banner").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("privilege_status_screen").assertIsDisplayed()
    }
}
