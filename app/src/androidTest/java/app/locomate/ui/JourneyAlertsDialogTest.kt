package app.locomate.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.JourneyAlertChannel
import app.locomate.data.JourneyAlertQuietHours
import app.locomate.data.PreviewRoutes
import app.locomate.ui.theme.LocomateTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class JourneyAlertsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeDialogExplainsConsentAndSavesChosenEventsAndQuietHours() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val route = PreviewRoutes.load(context).first().copy(isPreview = false,
            trainNumber = "12951", runId = "12951:2026-10-01", runDate = "2026-10-01")
        var savedChannels: Set<JourneyAlertChannel>? = null
        var savedQuiet: JourneyAlertQuietHours? = null
        compose.setContent {
            LocomateTheme {
                JourneyAlertsDialog(route, null, pushAvailable = true,
                    onDismiss = {}, onDisable = {}, onNotificationSettings = {},
                    onSave = { channels, quiet -> savedChannels = channels; savedQuiet = quiet })
            }
        }
        compose.onNodeWithText("Alerts for 12951").assertIsDisplayed()
        compose.onNodeWithText("Platform changes").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("Daily quiet hours").performScrollTo().performClick()
        compose.onNodeWithText("Until (HH:mm)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("I agree · enable alerts").assertIsDisplayed()
        compose.waitForIdle()
        File(context.getExternalFilesDir(null), "journey-alert-controls.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("I agree · enable alerts").performClick()
        compose.runOnIdle {
            assertEquals(JourneyAlertChannel.entries.toSet() - JourneyAlertChannel.Platform, savedChannels)
            assertEquals(JourneyAlertQuietHours("22:00", "07:00"), savedQuiet)
        }
    }
}
