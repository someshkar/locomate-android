package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityLocationFilterTest {
    private val route = listOf(RailPoint(19.0, 72.8), RailPoint(19.1, 72.9))

    @Test fun mapMatchesPlausibleFixAndRejectsMockOrOffRoute() {
        val now = 1_000_000L
        val fix = CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now - 1_000, 12.0, 20.0, false, now)
        assertNotNull(fix)
        assertEquals(CommunityConsent.VERSION, fix!!.consentVersion)
        assertTrue(fix.routeProgress in 0.45..0.55)
        assertTrue(fix.matchDistanceM < 30)
        assertEquals(fix.localId, CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now - 1_000, 12.0, 20.0, false, now)!!.localId)
        assertNull(CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now - 1_000, 12.0, 20.0, true, now))
        assertNull(CommunityLocationFilter.compact("12137:2026-10-01", route,
            20.0, 75.0, now - 1_000, 12.0, 20.0, false, now))
    }

    @Test fun rejectsStaleInaccurateStoppedAndPreviewFixes() {
        val now = 1_000_000L
        assertNull(CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now - 31_000, 12.0, 20.0, false, now))
        assertNull(CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now, 101.0, 20.0, false, now))
        assertNull(CommunityLocationFilter.compact("12137:2026-10-01", route,
            19.05, 72.85, now, 12.0, 0.0, false, now))
        assertNull(CommunityLocationFilter.compact("preview:12137", route,
            19.05, 72.85, now, 12.0, 20.0, false, now))
    }

    @Test fun contributionRequiresCurrentDatedRun() {
        val now = 1_000_000_000L
        val current = RoutePreview("12137", "Test", "A", "A", "B", "B", "00:00", "01:00", 0,
            route, emptyList(), isPreview = false, runId = "12137:2026-10-01", runDate = "2026-10-01",
            departureInstantMillis = now - 1_000, arrivalInstantMillis = now + 3_600_000)
        assertTrue(CommunityLocationFilter.inRunWindow(current, now))
        assertFalse(CommunityLocationFilter.inRunWindow(current.copy(isPreview = true), now))
        assertFalse(CommunityLocationFilter.inRunWindow(current.copy(statusLabel = "STALE · LAST KNOWN"), now))
        assertFalse(CommunityLocationFilter.inRunWindow(current, now + 26 * 3_600_000L))
    }
}
