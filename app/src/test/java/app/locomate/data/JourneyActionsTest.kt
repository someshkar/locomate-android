package app.locomate.data

import org.junit.Assert.*
import org.junit.Test

class JourneyActionsTest {
    @Test fun productionShareKeepsPersonalSegmentAndCanonicalDatedLink() {
        val route = route()
        val text = journeyShareText(route, JourneyPlan("B", "C"))
        assertTrue(text.contains("Boarding → Destination"))
        assertTrue(text.contains("Origin date (India): 2026-10-01"))
        assertTrue(text.contains("STALE · LAST KNOWN"))
        assertEquals(JourneyAlertLink("12951", "2026-10-01"), JourneyAlertLink.parse(text.lines().last()))
        val anotherDay = journeyShareText(route.copy(runDate = "2026-10-02"), null)
        assertEquals(JourneyAlertLink("12951", "2026-10-02"), JourneyAlertLink.parse(anotherDay.lines().last()))
        assertTrue(anotherDay.contains("Origin → Destination"))
    }

    @Test fun previewSharesAreClearlyLabeledAndNeverLinkToARealDatedRun() {
        val text = journeyShareText(route().copy(isPreview = true), JourneyPlan("B", "C"))
        assertTrue(text.contains("Historical preview · not a live journey"))
        assertTrue(text.contains("Boarding → Destination"))
        assertFalse(text.contains("locomate://"))
        assertFalse(text.contains("Origin date (India)"))
    }

    @Test fun invalidOrAbsentProductionDateCannotCreateAnActionableLink() {
        for (date in listOf(null, "2026-02-30", "2026-10-01?extra=1")) {
            val text = journeyShareText(route().copy(runDate = date), null)
            assertTrue(text.contains("Origin date unavailable"))
            assertFalse(text.contains("locomate://"))
        }
    }

    @Test fun savedRowsRequireAValidRunReferenceButHistoricalPreviewsRemainOpenable() {
        val saved = SavedJourney.from(route())
        assertNull(saved.openUnavailableReason())
        assertTrue(saved.copy(originDate = null).openUnavailableReason()!!.startsWith("Origin date missing."))
        assertTrue(saved.copy(originDate = "2026-02-30").openUnavailableReason()!!.startsWith("Saved run details are invalid."))
        assertNotNull(saved.copy(trainNumber = "unknown").openUnavailableReason())
        assertNull(saved.copy(preview = true, originDate = null).openUnavailableReason())
    }

    private fun route() = RoutePreview("12951", "Test Express", "A", "Origin", "C", "Destination", "10:00", "14:00", 1,
        emptyList(), listOf(RouteStop("A", "Origin", null, "10:00"),
            RouteStop("B", "Boarding", "12:00", "12:05"), RouteStop("C", "Destination", "14:00", null)),
        isPreview = false, runDate = "2026-10-01", statusLabel = "STALE · LAST KNOWN",
        sourceDetail = "Saved timetable. Live refresh unavailable.")
}
