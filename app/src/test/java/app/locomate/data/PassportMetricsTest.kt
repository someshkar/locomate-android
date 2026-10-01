package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
