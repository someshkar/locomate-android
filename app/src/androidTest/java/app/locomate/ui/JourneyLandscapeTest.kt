package app.locomate.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.unit.dp
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGateway
import app.locomate.data.SavedJourney
import app.locomate.ui.theme.LocomateTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** The entire recovery CTA must be reachable in a short landscape viewport. */
@OptIn(ExperimentalTestApi::class)
class JourneyLandscapeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recoverySearchActionIsReachableInLandscape() = verifyRecovery(1f)
    @Test fun recoverySearchActionIsReachableInLandscapeAtDoubleTextSize() = verifyRecovery(2f)

    @Test fun overviewPanelsKeepTheirActionsReachableInLandscapeAtDoubleTextSize() = inLandscape {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        val saved = SavedJourney.from(route.copy(isPreview = false, runDate = "2026-10-08"))
        var panel by mutableIntStateOf(0)
        var settingsOpened = false
        var savedOpened = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                LocomateTheme {
                    NavigationScaffold(Tab.Explore, {}, {}, searchActive = panel == 2) { inset ->
                        when (panel) {
                            0 -> ExploreScreen(route, gateway, bottomInset = inset)
                            1 -> PassportScreen(listOf(saved), onRemove = {}, onOpen = { savedOpened = true },
                                onSettings = { settingsOpened = true }, bottomInset = inset)
                            else -> SearchScreen(listOf(route), gateway, {}, { _, _ -> }, bottomInset = inset)
                        }
                    }
                }
            }
        }
        val credits = compose.onNodeWithText("Map attribution").performScrollTo().assertIsDisplayed()
        assertTrue(credits.getUnclippedBoundsInRoot().bottom <=
            compose.onNodeWithContentDescription("Explore").getUnclippedBoundsInRoot().top)
        compose.runOnIdle { panel = 1 }
        compose.onNodeWithContentDescription("Open settings").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
        compose.onNodeWithText(saved.trainName).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(savedOpened); panel = 2 }
        compose.onNodeWithText("Train no. or station").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Map attribution"))
        compose.onNodeWithText("Map attribution").performScrollTo().assertIsDisplayed()
    }

    private fun verifyRecovery(fontScale: Float) = inLandscape {
        var searched = false
        val recoveryMessage = "Could not restore 64422:2026-10-08: The rail feed is temporarily unavailable. Try again shortly."
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                LocomateTheme {
                    NavigationScaffold(Tab.Journeys, {}, {}) { inset ->
                        JourneyScreen(null, saved = false, productionMode = true, message = recoveryMessage,
                            onSave = {}, onSearch = { searched = true }, bottomInset = inset)
                    }
                }
            }
        }
        val action = compose.onNodeWithText("Find your train").assertIsDisplayed().assertHasClickAction()
        val bounds = action.getUnclippedBoundsInRoot()
        val dock = compose.onNodeWithContentDescription("Journeys").getUnclippedBoundsInRoot()
        assertTrue("Recovery action must retain a complete touch target", bounds.bottom - bounds.top >= 48.dp)
        assertTrue("Recovery action is clipped above the dock", bounds.bottom <= dock.top)
        val screenshot = File(compose.activity.getExternalFilesDir(null), "landscape/recovery-$fontScale.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation
            .takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        action.performClick()
        compose.runOnIdle { assertTrue(searched) }
        compose.onNodeWithText(recoveryMessage).performScrollTo().assertIsDisplayed()
    }

    private fun inLandscape(body: () -> Unit) {
        val original = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            configureEdgeToEdgeTestWindow(compose.activity)
            body()
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = original }
        }
    }
}
