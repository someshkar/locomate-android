package app.locomate.data

import org.junit.Assert.*
import org.junit.Test

class JourneySummaryClockTest {
    private val stop = RouteStop("B", "Intermediate", "19:48", "19:50",
        forecastP50 = "23:59", forecastSource = "unavailable")

    @Test fun unavailableOrUnknownForecastsCannotSupplyAnArrival() {
        assertEquals("19:48", clock(stop).time)
        assertEquals("Scheduled arrival", clock(stop).label)
        assertEquals("19:48", clock(stop.copy(forecastSource = "unknown")).time)
        assertEquals("19:48", clock(stop.copy(forecastSource = "baseline", forecastP50 = "25:00")).time)
    }

    @Test fun matchingForecastIsEstimatedWhilePreviewAndSavedRunsRemainScheduled() {
        val forecast = stop.copy(forecastP50 = "20:05", forecastSource = "baseline")
        assertEquals("20:05", clock(forecast).time)
        assertEquals("Estimated arrival", clock(forecast).label)
        assertEquals("19:48", clock(forecast, preview = true).time)
        assertEquals("Saved scheduled arrival", clock(forecast, stale = true).label)
        assertEquals("19:48", clock(forecast, stale = true).time)
    }

    @Test fun actualEventWinsAndSavedActualEvidenceKeepsItsLabel() {
        val actual = stop.copy(actualArrival = "19:49", actualDeparture = "19:51", forecastSource = "baseline")
        assertEquals("19:49", clock(actual).time)
        assertEquals("Saved actual arrival", clock(actual, stale = true).label)
        assertEquals("19:51", JourneySummaryClock.forStop(actual, true, false, false).time)
        assertEquals("19:48", clock(actual, preview = true).time)
    }

    @Test fun arrivalOnlyCallNeverGainsAnOriginDepartureFallback() {
        assertNull(JourneySummaryClock.forStop(stop.copy(scheduledDeparture = null), true, false, false).time)
        assertNull(clock(stop.copy(scheduledArrival = "invalid", forecastSource = "unavailable")).time)
    }

    private fun clock(stop: RouteStop, preview: Boolean = false, stale: Boolean = false) =
        JourneySummaryClock.forStop(stop, departure = false, preview = preview, stale = stale)
}
