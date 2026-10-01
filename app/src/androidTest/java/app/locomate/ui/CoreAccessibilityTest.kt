package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
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

    @Test fun journeyDetailsCanBeExpandedWithoutDragging() {
        val route = PreviewRoutes.load(compose.activity).first()
        compose.setContent { AuditTheme { JourneyScreen(route, saved = false, onSave = {}) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse journey details").assertHasClickAction().performClick()
        compose.onNodeWithText("Map attribution").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand journey details").assertHasClickAction().performClick()
        compose.onNodeWithText("My Journeys").tryPerformAccessibilityChecks()
        saveScreenshot("journey-200")
        assertVisibleTextFits()
        compose.onNodeWithText("Status card").performScrollTo().assertHasClickAction()
        assertVisibleTextFits()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(route.calls.first().name))
        compose.onNodeWithText(route.calls.first().name).assertIsDisplayed().tryPerformAccessibilityChecks()
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
        val gateway = RailGateway(compose.activity, "")
        compose.setContent { AuditTheme { SearchSheet(routes, gateway, {}, {}, { _, _ -> }) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Train name or number").performScrollTo().performTextInput(routes.first().trainNumber)
        compose.onNodeWithText("Train name or number").assertIsDisplayed().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
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
        compose.setContent { AuditTheme { PassportScreen(listOf(saved), onRemove = {}, onOpen = {}, onSettings = {}) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Passport").tryPerformAccessibilityChecks()
        assertVisibleTextFits()
        compose.onNodeWithContentDescription("Remove ${saved.trainNumber}, ${saved.originCode} to ${saved.destinationCode}, ${saved.originDate}")
            .performScrollTo().assertHasClickAction().tryPerformAccessibilityChecks()
        assertVisibleTextFits()
    }

    @Test fun exploreOverviewRemainsReadableAtLargeText() {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        compose.setContent { AuditTheme { ExploreScreen(route, gateway) } }
        compose.enableAccessibilityChecks()
        compose.onNodeWithText("Explore").tryPerformAccessibilityChecks()
        compose.onNodeWithText("PREVIEW").assertIsDisplayed()
        saveScreenshot("explore-200")
        assertVisibleTextFits()
    }

    @Test fun exploreCreditsStayAboveTheProductionNavigationDockAtLargeText() {
        val route = PreviewRoutes.load(compose.activity).first()
        val gateway = RailGateway(compose.activity, "")
        compose.setContent { AuditTheme {
            NavigationScaffold(Tab.Explore, {}, {}) { inset -> ExploreScreen(route, gateway, bottomInset = inset) }
        } }
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
