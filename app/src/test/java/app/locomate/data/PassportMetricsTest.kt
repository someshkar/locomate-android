package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportMetricsTest {
    private fun saved(key: String, preview: Boolean, distance: Double, minutes: Int) = SavedJourney(
        key = key, trainNumber = "12137", trainName = "Punjab Mail",
        originCode = "CSTM", originName = "Mumbai CST",
        destinationCode = "FZR", destinationName = "Firozpur Cant",
        originDate = if (preview) null else "2026-10-01",
        distanceKm = distance, durationMinutes = minutes, preview = preview,
    )

    @Test fun previewRoutesNeverCountAsSavedRuns() {
        val metrics = PassportMetrics.from(listOf(
            saved("preview", true, 1930.0, 2040),
            saved("production", false, 1451.0, 1020),
        ))
        assertEquals(1, metrics.runCount)
        assertEquals(1, metrics.previewCount)
        assertEquals(1451, metrics.knownDistanceKm)
        assertEquals(17, metrics.knownScheduledHours)
    }

    @Test fun missingGatewayDistanceStaysUnavailable() {
        val metrics = PassportMetrics.from(listOf(saved("unknown", false, 0.0, 0)))
        assertEquals(1, metrics.runCount)
        assertNull(metrics.knownDistanceKm)
        assertNull(metrics.knownScheduledHours)
    }

    @Test fun selectedSegmentUsesOnlyKnownStationDistance() {
        val route = RoutePreview(
            trainNumber = "12137", name = "Punjab Mail", originCode = "A", originName = "A",
            destinationCode = "C", destinationName = "C", departure = "10:00", arrival = "14:00",
            arrivalDay = 1, geometry = emptyList(),
            calls = listOf(
                RouteStop("A", "A", null, "10:00", distanceKm = 0.0),
                RouteStop("B", "B", "12:00", "12:05", distanceKm = 100.0),
                RouteStop("C", "C", "14:00", null, distanceKm = 220.0),
            ),
            distanceKm = 220.0, durationMinutes = 240, isPreview = false,
            runDate = "2026-10-01",
        )
        val segment = JourneyPlan("B", "C")
        assertTrue(segment.isValidFor(route))
        assertFalse(JourneyPlan("C", "B").isValidFor(route))
        val saved = SavedJourney.from(route, segment)
        assertEquals("B", saved.originCode)
        assertEquals("C", saved.destinationCode)
        assertEquals(120.0, saved.distanceKm, 0.01)
        assertEquals(0, saved.durationMinutes)
        assertEquals(120, PassportMetrics.from(listOf(saved)).knownDistanceKm)
    }
}
