package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGateway
import androidx.compose.ui.test.assertHasClickAction
import org.junit.Assert.assertTrue
import app.locomate.data.JourneyPlan
import app.locomate.data.RoutePreview
import app.locomate.data.RouteStop
import app.locomate.data.SavedJourney
import app.locomate.ui.theme.LocomateTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class JourneyTimingPassportTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun journeySummaryKeepsClocksAndSaveAboveTheDockAtNormalText() {
        val route = PreviewRoutes.load(compose.activity).first()
        var saved by mutableStateOf(false)
        var edited = false
        compose.setContent { LocomateTheme {
            NavigationScaffold(Tab.Journeys, {}, {}) { inset ->
                JourneyScreen(route, saved = saved, onSave = { saved = !saved }, onEdit = { edited = true }, bottomInset = inset)
            }
        } }
        val dock = compose.onNodeWithContentDescription("Journeys")
        for (name in listOf(route.calls.first().name, route.calls.last().name)) {
            val clock = compose.onNodeWithContentDescription(name).assertIsDisplayed()
            assertTrue("Station clock overlaps navigation", clock.fetchSemanticsNode().boundsInRoot.bottom
                <= dock.fetchSemanticsNode().boundsInRoot.top)
        }
        val save = compose.onNodeWithContentDescription("Save journey").assertHasClickAction()
        assertTrue("Save overlaps navigation", save.fetchSemanticsNode().boundsInRoot.bottom
            <= dock.fetchSemanticsNode().boundsInRoot.top)
        screenshot("journey-card-normal")
        save.performClick()
        compose.onNodeWithContentDescription("Remove saved journey").assertIsDisplayed()
        compose.runOnIdle { assertTrue(saved) }
        compose.onNodeWithContentDescription("Expand journey details").performClick()
        val edit = compose.onNodeWithContentDescription("Edit boarding and alighting stops. Board at ${route.originCode}, leave at ${route.destinationCode}")
            .performScrollTo().assertHasClickAction()
        assertTrue("Edit overlaps navigation", edit.fetchSemanticsNode().boundsInRoot.bottom
            <= dock.fetchSemanticsNode().boundsInRoot.top)
        edit.performClick()
        compose.runOnIdle { assertTrue(edited) }
    }

    @Test fun overviewNavigationKeepsSavedActionsAboveTheDockAtNormalText() {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        val entry = saved("Saved Express", "2026-10-01", 8412.0)
        var tab by mutableStateOf(Tab.Explore)
        var opened: SavedJourney? = null
        var settingsOpened = false
        compose.setContent { LocomateTheme {
            NavigationScaffold(tab, { tab = it }, {}) { inset ->
                if (tab == Tab.Explore) ExploreScreen(route, gateway, bottomInset = inset)
                else PassportScreen(listOf(entry), onRemove = {}, onOpen = { opened = it },
                    onSettings = { settingsOpened = true }, bottomInset = inset)
            }
        } }
        compose.onNodeWithText("The network, in preview").assertIsDisplayed()
        screenshot("explore-normal")
        compose.onNodeWithContentDescription("Passport").performClick().assertIsSelected()
        compose.onNodeWithText("8,412 km").assertIsDisplayed()
        screenshot("passport-normal")
        compose.onNodeWithContentDescription("Open settings").assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(settingsOpened) }
        val savedRow = compose.onNodeWithText("Saved Express").performScrollTo().assertIsDisplayed()
        val dock = compose.onNodeWithContentDescription("Passport")
        assertTrue("Saved journey overlaps navigation", savedRow.fetchSemanticsNode().boundsInRoot.bottom
            <= dock.fetchSemanticsNode().boundsInRoot.top)
        savedRow.performClick()
        compose.runOnIdle { assertEquals(entry, opened) }
        val credits = compose.onNodeWithText("Map attribution").performScrollTo().assertIsDisplayed()
        assertTrue("Passport map credits overlap navigation", credits.fetchSemanticsNode().boundsInRoot.bottom
            <= dock.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun passportYearFiltersRestoreAndUseTheSameRowsForStatsAndOpening() {
        val current = saved("current", "2026-01-01", 400.0)
        val earlier = saved("earlier", "2025-12-31", 120.0)
        val sample = saved("sample", null, 900.0).copy(preview = true)
        var routes by mutableStateOf(listOf(current, earlier, sample))
        var opened: SavedJourney? = null
        val restore = StateRestorationTester(compose)
        restore.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                LocomateTheme {
                    PassportScreen(routes, onRemove = { key -> routes = routes.filterNot { it.key == key } },
                        onOpen = { opened = it }, onSettings = {})
                }
            }
        }
        compose.onNodeWithText("All-Time").assertIsSelected()
        compose.onNodeWithText("520 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2025").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("120 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("current").assertDoesNotExist()
        compose.onNodeWithText("sample").assertDoesNotExist()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2025").performScrollTo().assertIsSelected()
        screenshot("passport-year-200")
        compose.onNodeWithText("earlier").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(earlier, opened) }
        compose.onNodeWithContentDescription("Remove 12951, A to C, 2025-12-31").performClick()
        // Removing the last run in a year returns to All-Time instead of a hidden empty selection.
        compose.onNodeWithText("All-Time").performScrollTo().assertIsSelected()
        compose.onNodeWithText("2025").assertDoesNotExist()
        compose.onNodeWithText("400 km").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("sample").performScrollTo().assertIsDisplayed()
    }

    @Test fun countdownUsesFuturePersonalBoardingAndDoesNotInventPreviewTiming() {
        val now = System.currentTimeMillis()
        var route by mutableStateOf(route(now))
        var plan by mutableStateOf(JourneyPlan.default(route))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                LocomateTheme { JourneyScreen(route, plan, saved = false, onSave = {}) }
            }
        }
        val countdown = "1 day 4 hours until scheduled departure"
        compose.onNodeWithText(countdown).assertDoesNotExist()
        compose.runOnIdle { plan = JourneyPlan("B", "C") }
        compose.onNodeWithText(countdown).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved timetable · Scheduled boarding at B").assertIsDisplayed()
        screenshot("boarding-countdown-200")
        compose.runOnIdle { route = route.copy(isPreview = true) }
        compose.onNodeWithText(countdown).assertDoesNotExist()
        compose.onNodeWithText("Saved timetable · Scheduled boarding at B").assertDoesNotExist()
    }

    @Test fun timelineUsesNaturalDelayWordsAndKeepsStaleEvidenceQualified() {
        val route = route(System.currentTimeMillis())
        compose.setContent { LocomateTheme { JourneyScreen(route, saved = false, onSave = {}) } }
        compose.onNodeWithContentDescription("Expand journey details").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Last known · 18 minutes early"))
        compose.onNodeWithText("Last known · 18 minutes early").assertIsDisplayed()
    }

    private fun saved(name: String, date: String?, distance: Double) = SavedJourney(name, "12951", name,
        "A", "Origin", "C", "Destination", date, distance, 180, false)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "timing-passport/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun route(now: Long) = RoutePreview("12951", "Timing Express", "A", "Origin", "C", "Destination",
        "10:00", "14:00", 2, emptyList(), listOf(
            RouteStop("A", "Origin", null, "10:00", state = "passed", actualDeparture = "09:42",
                delayMinutes = -18, scheduledDepartureMillis = now - 3_600_000),
            RouteStop("B", "Boarding", "13:00", "13:05", scheduledDepartureMillis = now + 28 * 3_600_000 + 30 * 60_000),
            RouteStop("C", "Destination", "14:00", null, scheduledArrivalMillis = now + 30 * 3_600_000),
        ), isPreview = false, runDate = "2026-10-01", departureInstantMillis = now - 3_600_000,
        statusLabel = "STALE · LAST KNOWN", sourceDetail = "Saved timetable. Live refresh unavailable.")
}
