package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import app.locomate.data.JourneyPlan
import app.locomate.data.NetworkTrain
import app.locomate.data.NetworkSnapshot
import app.locomate.data.RailPoint
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGateway
import app.locomate.data.SavedJourney
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.LocomateTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import android.os.ParcelFileDescriptor
import android.os.SystemClock

/** ATF checks are unsuppressed; 200% text also checks actual visible text layout for truncation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoreAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    companion object {
        // One system change before all Activity launches avoids rapid configuration-update races.
        // Android Dialogs create their density from this setting, unlike local Compose overrides.
        @ClassRule @JvmField val fontScale: TestRule = SystemFontScaleRule()
    }

    @Test fun navigationKeepsNamedActionsAtTwoHundredPercentText() {
        compose.setContent { AuditTheme { CapsuleNavBar(Tab.Journeys, {}, {}) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithContentDescription("Journeys").assertIsSelected()
        for (label in listOf("Journeys", "Explore", "Passport")) {
            compose.onNodeWithContentDescription(label).assertHasClickAction().assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Search trains").assertHasClickAction().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun originCalendarAndQuickDatesRemainReachableAtTwoHundredPercentText() {
        var date by mutableStateOf("2026-10-02")
        var selectedDayDescription = ""
        compose.setContent { AuditTheme {
            selectedDayDescription = androidx.compose.material3.DatePickerDefaults.dateFormatter().formatDate(
                java.time.LocalDate.parse("2019-02-15").atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                compose.activity.resources.configuration.locales[0], forContentDescription = true).orEmpty()
            NavigationScaffold(Tab.Journeys, {}, {}, searchActive = true) { inset ->
                Column(Modifier.fillMaxSize().padding(bottom = inset).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    OriginDatePicker(date, { date = it })
                }
            }
        } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithContentDescription("Yest").performClick().assertIsSelected().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("origin-dates-200")
        val input = openOriginDateInput(compose, beforeInput = {
            compose.onNodeWithText("Enter date").performScrollTo().assertIsDisplayed().tryPerformAccessibilityChecks()
            assertVisibleTextFits()
            saveScreenshot("origin-calendar-days-200")
        })
        input.performTextReplacement("2026-02-30")
        compose.onNodeWithText("Use date").assertIsNotEnabled()
        compose.onNodeWithText("Enter a valid date as YYYY-MM-DD.").performScrollTo()
            .assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("origin-invalid-200")
        input.performScrollTo().performTextReplacement("2019-02-15")
        compose.onNodeWithText("Use date").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("origin-calendar-200")
        compose.onNodeWithText("Show calendar").performScrollTo().performClick()
        assertTrue(selectedDayDescription.isNotBlank())
        compose.onNodeWithText(selectedDayDescription, substring = true).assertIsDisplayed()
            .assertIsSelected().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("origin-calendar-return-200")
        compose.onNodeWithText("Use date").performClick()
        compose.runOnIdle { assertEquals("2019-02-15", date) }
        compose.onNodeWithContentDescription("Choose origin date").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("origin-selected-200")
    }

    @Test fun actualStationChoicesAndHistoricalServicesRemainReadableAtTwoHundredPercentText() {
        val routes = PreviewRoutes.load(compose.activity)
        val train = routes.first { it.trainNumber == "12951" }
        var selected: String? = null
        compose.setContent { AuditTheme {
            val gateway = androidx.compose.runtime.remember { RailGateway(compose.activity, "") }
            NavigationScaffold(Tab.Journeys, {}, {}, searchActive = true) { inset ->
                SearchScreen(routes, gateway, { selected = it }, { _, _ -> }, inset)
            }
        } }
        compose.enableAccessibilityChecks()
        val shortcut = "Find trains at New Delhi, NDLS"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription(shortcut))
        compose.onNodeWithContentDescription(shortcut).assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("station-shortcuts-200")
        compose.onNodeWithContentDescription(shortcut).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(train.name))
        compose.onNodeWithText(train.name).performScrollTo().assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("station-services-200")
        compose.onNodeWithText(train.name).performClick()
        compose.runOnIdle { assertEquals("12951", selected) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Train no. or station"))
        val field = compose.onNodeWithText("Train no. or station").performScrollTo()
        field.performTextReplacement("Delhi"); field.performImeAction()
        val previewStation = app.locomate.data.StationSearch.previewStations(routes).first { it.code == "NDLS" }
        val label = "Find trains at ${previewStation.name}, NDLS"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription(label))
        compose.onNodeWithContentDescription(label).assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("station-lookup-200")
    }

    @Test fun routePickerAndScheduledResultRemainReadableAtTwoHundredPercentText() {
        val routes = PreviewRoutes.load(compose.activity)
        var selected: String? = null
        compose.setContent { AuditTheme {
            val gateway = androidx.compose.runtime.remember { RailGateway(compose.activity, "") }
            NavigationScaffold(Tab.Journeys, {}, {}, searchActive = true) { inset ->
                SearchScreen(routes, gateway, { selected = it }, { _, _ -> }, inset)
            }
        } }
        compose.enableAccessibilityChecks()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription("Board at station"))
        compose.onNodeWithContentDescription("Board at station").assertIsDisplayed().tryPerformAccessibilityChecks()
        compose.onNodeWithContentDescription("Board at station").performClick()
        compose.onNodeWithContentDescription("Choose Vadodara Jn, BRC").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription("Leave at station"))
        compose.onNodeWithContentDescription("Leave at station").performClick()
        compose.onNodeWithContentDescription("Choose Kota Jn, KOTA").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasContentDescription("Find trains between stations"))
        compose.onNodeWithContentDescription("Find trains between stations").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("route-picker-200")
        compose.onNodeWithContentDescription("Find trains between stations").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mumbai Central-New Delhi Rajdhani Express"))
        compose.onNodeWithText("Historical route pack").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("route-result-200")
        compose.onNodeWithText("train origin", substring = true).performScrollTo()
            .assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits(); saveScreenshot("route-origin-date-200")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Mumbai Central-New Delhi Rajdhani Express"))
        compose.onNodeWithText("Mumbai Central-New Delhi Rajdhani Express").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("12951", selected) }
    }

    @Test fun actualRecentSelectionAndClearingRemainReadableAtTwoHundredPercentText() {
        val store = app.locomate.data.RecentTrainStore(compose.activity, "")
        assertTrue(store.clear())
        val routes = PreviewRoutes.load(compose.activity)
        val train = routes.first { it.trainNumber == "12951" }
        var selections = 0
        compose.setContent { AuditTheme {
            val gateway = androidx.compose.runtime.remember { RailGateway(compose.activity, "") }
            NavigationScaffold(Tab.Journeys, {}, {}, searchActive = true) { inset ->
                SearchScreen(routes, gateway, { number -> assertEquals("12951", number); selections++ }, { _, _ -> }, inset)
            }
        } }
        compose.enableAccessibilityChecks()
        val field = compose.onNodeWithText("Train no. or station")
        field.performTextInput("12951")
        field.performImeAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(train.name))
        compose.onNodeWithText(train.name).performScrollTo().performClick()
        field.performScrollTo().performTextReplacement("")
        field.performImeAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(train.name))
        compose.onNodeWithText(train.name).assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("search-recents-200")
        compose.onNodeWithText(train.name).performClick()
        compose.runOnIdle { assertEquals(2, selections) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(
            androidx.compose.ui.test.hasContentDescription("Clear recent trains"))
        compose.onNodeWithContentDescription("Clear recent trains").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        saveScreenshot("search-recents-clear-200")
        compose.onNodeWithContentDescription("Clear recent trains").performClick()
        compose.onNodeWithText(train.name).assertDoesNotExist()
        assertTrue(store.load().isEmpty())
    }

    @Test fun journeyDetailsCanBeExpandedWithoutDragging() {
        val route = PreviewRoutes.load(compose.activity).first()
        val glass = MapGlassController()
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Journeys, {}, {}, mapGlass = glass) { inset ->
                JourneyScreen(route, saved = false, onSave = {}, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse journey details").assertHasClickAction().performClick()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand journey details").assertHasClickAction().performClick()
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.onNodeWithText("My Journeys").tryPerformAccessibilityChecks()
        saveScreenshot("journey-200")
        assertVisibleTextFits()
        compose.onNodeWithText("Status card").performScrollTo().assertHasClickAction()
        val dockTop = compose.onNodeWithContentDescription("Journeys").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Status card must remain above the dock",
            compose.onNodeWithText("Status card").fetchSemanticsNode().boundsInRoot.bottom <= dockTop)
        assertVisibleTextFits()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(route.calls.first().name))
        compose.onNodeWithText(route.calls.first().name).assertIsDisplayed()
        saveScreenshot("journey-timeline-200")
        compose.onNodeWithText(route.calls.first().name).tryPerformAccessibilityChecks()
        assertVisibleTextFits()
    }

    @Test fun emptyJourneyKeepsSearchAndAttributionReachable() {
        var searchOpened = false
        compose.setContent { AuditTheme {
            JourneyScreen(null, saved = false, productionMode = true, onSave = {}, onSearch = { searchOpened = true })
        } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse journey details").performClick()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand journey details").performClick()
        compose.onNodeWithText("Find your train").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, searchOpened) }
        assertVisibleTextFits()
    }

    @Test fun searchKeepsItsFieldLabelAfterTyping() {
        val routes = PreviewRoutes.load(compose.activity)
        val train = routes.first { it.trainNumber == "12951" }
        val gateway = RailGateway(compose.activity, "")
        val glass = MapGlassController()
        var selected: String? = null
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Passport, {}, {}, searchActive = true, mapGlass = glass) { inset ->
                SearchScreen(routes, gateway, { selected = it }, { _, _ -> }, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.enableAccessibilityChecks()
        val field = compose.onNodeWithText("Train no. or station").performScrollTo()
        field.performClick().performTextInput(train.trainNumber)
        compose.waitUntil(10_000) { glass.snapshot == null }
        compose.onNodeWithText("Train no. or station").assertIsDisplayed().tryPerformAccessibilityChecks()
        for (label in listOf("Journeys", "Explore", "Passport", "Search trains")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
        }
        compose.onNodeWithContentDescription("Search trains").assertIsSelected()
        saveScreenshot("search-200")
        assertVisibleTextFits()
        field.performImeAction()
        compose.waitUntil(15_000) { glass.snapshot != null }
        val result = compose.onNodeWithText(train.name, useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().tryPerformAccessibilityChecks()
        val layouts = mutableListOf<TextLayoutResult>()
        result.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Every line of the full result must be in the reading viewport",
            layouts.single().multiParagraph.height <= result.fetchSemanticsNode().boundsInRoot.height + 1)
        assertTrue("Result name overlaps navigation", result.fetchSemanticsNode().boundsInRoot.bottom
            < compose.onNodeWithContentDescription("Search trains").fetchSemanticsNode().boundsInRoot.top)
        saveScreenshot("search-full-result-200")
        assertVisibleTextFits()
        result.performClick()
        compose.runOnIdle { assertEquals("12951", selected) }
    }

    @Test fun boardingAndAlightingChoicesExposeSelection() {
        val route = PreviewRoutes.load(compose.activity).first()
        compose.setContent { AuditTheme { JourneySetupDialog(route, JourneyPlan.default(route), {}, {}) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("BOARD").assertIsSelected().tryPerformAccessibilityChecks()
        compose.onNodeWithText(route.calls.first().name).assertIsSelected()
        compose.onNodeWithText("LEAVE").performClick().assertIsSelected()
        compose.onNodeWithText("Save this segment").assertIsDisplayed()
        assertVisibleTextFits()
    }

    @Test fun passportRemovalNamesTheSpecificJourney() {
        val route = PreviewRoutes.load(compose.activity).first().copy(isPreview = false, runDate = "2026-10-01")
        val saved = SavedJourney.from(route)
        val glass = MapGlassController()
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Passport, {}, {}, mapGlass = glass) { inset ->
                PassportScreen(listOf(saved), onRemove = {}, onOpen = {}, onSettings = {}, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.enableAccessibilityChecks()
        compose.onNode(hasText("Passport") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).tryPerformAccessibilityChecks()
        saveScreenshot("passport-glass-200")
        assertVisibleTextFits()
        compose.onNodeWithContentDescription("Remove ${saved.trainNumber}, ${saved.originCode} to ${saved.destinationCode}, ${saved.originDate}")
            .performScrollTo().assertHasClickAction().tryPerformAccessibilityChecks()
        saveScreenshot("passport-glass-removal-200")
        assertVisibleTextFits()
    }

    @Test fun exploreOverviewRemainsReadableAtLargeText() {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        val glass = MapGlassController()
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Explore, {}, {}, mapGlass = glass) { inset ->
                ExploreScreen(route, gateway, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.enableAccessibilityChecks()
        compose.onNode(hasText("Explore") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).tryPerformAccessibilityChecks()
        compose.onNodeWithText("PREVIEW").assertIsDisplayed()
        saveScreenshot("explore-200")
        assertVisibleTextFits()
    }

    @Test fun exploreCreditsStayAboveTheProductionNavigationDockAtLargeText() {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        val glass = MapGlassController()
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Explore, {}, {}, mapGlass = glass) { inset -> ExploreScreen(route, gateway, bottomInset = inset) }
        } }
        compose.waitUntil(15_000) { glass.snapshot != null }
        compose.enableAccessibilityChecks()
        val credits = compose.onNodeWithText("Map attribution").performScrollTo().assertIsDisplayed()
        val dock = compose.onNodeWithContentDescription("Explore").assertIsSelected()
        assertTrue("Map credits overlap the production navigation dock",
            credits.fetchSemanticsNode().boundsInRoot.bottom <= dock.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithContentDescription("Search trains").assertHasClickAction().tryPerformAccessibilityChecks()
        saveScreenshot("explore-navigation-200")
        assertVisibleTextFits()
    }

    @Test fun networkTrainListExposesMarkerSourceAndDatedRunWithoutMapGestures() {
        val train = NetworkTrain("12951:2026-10-01", "12951", "Mumbai Rajdhani", "2026-10-01",
            RailPoint(19.0, 72.8), "2026-10-01T10:00:00Z", "observed", "station-report", 8)
        compose.setContent { AuditTheme {
            NetworkTrainListDialog(NetworkSnapshot(listOf(train), train.observedAt, "2026-10-01T10:05:00Z"), {}, onOpenTrain = {})
        } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Origin date 2026-10-01", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Observed position · Source: station-report", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Observed 1 Oct, 15:30 IST", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8 minutes late", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Open journey for train 12951 on 2026-10-01")
            .performScrollTo().assertIsDisplayed().assertHasClickAction().tryPerformAccessibilityChecks()
        compose.onNodeWithText("Close train list").assertHasClickAction().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
    }

    @Test fun alertControlsAndConsentRemainReachableAtLargeText() {
        val route = PreviewRoutes.load(compose.activity).first().copy(isPreview = false, runDate = "2026-10-01")
        compose.setContent { AuditTheme {
            JourneyAlertsDialog(route, null, true, {}, {}, {}, { _, _ -> })
        } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Arrival").performScrollTo().assertHasClickAction().tryPerformAccessibilityChecks()
        compose.onNodeWithContentDescription("Daily quiet hours").performScrollTo().performClick()
        compose.onNodeWithText("Until (HH:mm)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("I agree · enable alerts").assertIsDisplayed().tryPerformAccessibilityChecks()
        saveScreenshot("alerts-200")
        assertVisibleTextFits()
    }

    @Test fun settingsActionsRemainReachableAtLargeText() {
        compose.setContent { AuditTheme {
            SettingsScreen(true, 0, {}, {}, {}, {}, false, null, true, false, false, null, false,
                emptyList(), null, null, {}, {}, {}, {}, {})
        } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Settings").tryPerformAccessibilityChecks()
        compose.onNodeWithText("Background contribution: off").performScrollTo().assertHasClickAction().assertIsOff()
        compose.onNodeWithText("Delete my data").performScrollTo().assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
    }

    private fun saveScreenshot(name: String) {
        val file = File(compose.activity.getExternalFilesDir(null), "accessibility/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertVisibleTextFits() {
        val textNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val overflow = mutableListOf<String>()
        repeat(textNodes.fetchSemanticsNodes().size) { index ->
            val node = textNodes[index]
            if (runCatching { node.assertIsDisplayed() }.isSuccess) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.forEach { result ->
                    assertEquals("Text did not inherit the 200% configuration: ${result.layoutInput.text}",
                        2f, result.layoutInput.density.fontScale, 0f)
                    // Semantics reconstructs a paragraph with the parent's maxWidth, even when
                    // Text measures to its shorter intrinsic width. Compare painted line extents,
                    // not multiParagraph.width (which causes false positives for short labels).
                    val widestLine = (0 until result.lineCount).maxOfOrNull {
                        result.getLineRight(it) - result.getLineLeft(it)
                    } ?: 0f
                    val clipped = result.multiParagraph.didExceedMaxLines ||
                        (0 until result.lineCount).any { result.isLineEllipsized(it) } ||
                        result.multiParagraph.height > result.size.height + 1f ||
                        widestLine > result.size.width + 1f
                    if (clipped) overflow +=
                        "${result.layoutInput.text}: box=${result.size}, paintedWidth=$widestLine, " +
                            "height=${result.multiParagraph.height}, lines=${result.lineCount}"
                }
            }
        }
        assertFalse("Visible text is truncated at 200% scale:\n${overflow.joinToString("\n")}", overflow.isNotEmpty())
    }
}

@Composable
private fun AuditTheme(content: @Composable () -> Unit) {
    LocomateTheme { Box(Modifier.fillMaxSize().background(LM.Ground)) { content() } }
}

private class SystemFontScaleRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = shell("settings get system font_scale").trim()
            try {
                shell("settings put system font_scale 2.0")
                awaitConfiguration(2f)
                base.evaluate()
            } finally {
                if (previous.toFloatOrNull() != null) shell("settings put system font_scale $previous")
                else shell("settings delete system font_scale")
                awaitConfiguration(previous.toFloatOrNull() ?: 1f)
            }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun awaitConfiguration(scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            instrumentation.waitForIdleSync()
            if (instrumentation.targetContext.resources.configuration.fontScale == scale) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("System font scale configuration did not arrive", scale,
            instrumentation.targetContext.resources.configuration.fontScale, 0f)
    }
}
