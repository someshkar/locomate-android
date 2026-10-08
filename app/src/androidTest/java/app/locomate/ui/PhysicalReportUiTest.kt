package app.locomate.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.locomate.data.*
import app.locomate.ui.theme.LocomateTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhysicalReportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun window() = configureEdgeToEdgeTestWindow(compose.activity)

    @Test fun publicReportingWithdrawalRemainsAvailableWithLocationContributionOff() {
        var withdrawals = 0
        compose.setContent { LocomateTheme {
            SettingsScreen(productionMode = true, savedCount = 0, onBack = {}, onOfficialRailway = {},
                onExportData = {}, onDeleteData = {}, privacyBusy = false, privacyNotice = null,
                contributionEnabled = false, contributionBackground = false, contributionBusy = false,
                contributionNotice = null, withdrawalRetryNeeded = false, alertSubscriptions = emptyList(),
                alertReadError = null, alertNotice = null, onDisableAlerts = {}, onRetryAlerts = {},
                onContributionGrant = {}, onContributionRevoke = { withdrawals++ }, onContributionBackground = {})
        } }
        compose.onNodeWithText("Withdraw public-number reporting consent").performScrollTo().performClick()
        compose.onNodeWithText("Withdraw contribution consent?").assertIsDisplayed()
        compose.onNodeWithText("Withdraw and delete").performClick()
        assertEquals(1, withdrawals)
    }

    @Test fun expiredReportRequiresExplicitRenewalAndCanBeRemovedAtDoubleTextSize() {
        val source = "https://outbox-ui.example"
        val context = compose.activity
        val gateway = RailGateway(context, source)
        val queue = PhysicalReportQueue(context, source)
        val now = System.currentTimeMillis()
        val original = queue.stage("12345", "2026-08-24", PhysicalSightingBatch(
            listOf(PhysicalSighting(SightingKind.Coach, "123456", now)), now - 16 * 60_000), gateway.reportInstallationGeneration())
        try {
            compose.setContent { LocomateTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { PhysicalReportOutboxCard(gateway, false) }
                }
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Coach 123456").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Consent expired · no automatic submission").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Retry exact report").assertDoesNotExist()
            compose.onNodeWithText("Review and renew consent").performScrollTo().performClick()
            compose.onNodeWithText("Renew report consent?").assertIsDisplayed()
            compose.onNodeWithText("Agree, renew and retry").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(original, PhysicalReportQueue(context, source).pending().single())
            compose.onNodeWithText("Remove pending report").performScrollTo().performClick()
            compose.waitUntil(5_000) { queue.pending().isEmpty() }
        } finally { queue.clear(); File(context.filesDir, "community/${railStorageScope(source)}").deleteRecursively() }
    }

    @Test fun proposedReportRequiresPublicNumberAndFreshCheckboxWithoutEnablingGps() {
        val context = compose.activity
        val source = "https://public-report-ui.example"
        val preferences = CommunityPreferences(context, source)
        preferences.revoke()
        val route = PreviewRoutes.load(context).first().copy(isPreview = false, runDate = "2026-08-24")
        compose.setContent { LocomateTheme { PhysicalSightingDialog(route, RailGateway(context, source), {}, {}) } }
        compose.onNodeWithText("Submit proposed evidence").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Coach").performScrollTo().performClick()
        compose.onNodeWithText("Public number").performScrollTo().performTextInput("B1")
        compose.onNodeWithText("Submit proposed evidence").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Public number").performScrollTo().performTextReplacement("123456")
        compose.onNodeWithText("Station code (optional)").performScrollTo().performTextInput("NRL-DLS")
        compose.onNodeWithText("Submit proposed evidence").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I read the notice and agree to submit this public number as proposed community evidence.")
            .performScrollTo().performClick()
        compose.onNodeWithText("Submit proposed evidence").performScrollTo().assertIsEnabled()
        assertFalse(preferences.enabled)
        assertTrue(PhysicalReportQueue(context, source).pending().isEmpty())
    }
}
