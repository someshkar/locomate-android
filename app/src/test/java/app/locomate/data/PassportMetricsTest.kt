package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

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

    @Test fun yearFiltersUseServiceOriginYearAndKeepPreviewAndUndatedOnlyInAllTime() {
        val current = saved("current", false, 400.0, 600)
        val earlier = saved("earlier", false, 120.0, 180).copy(originDate = "2025-12-31")
        val undated = saved("undated", false, 0.0, 0).copy(originDate = null)
        val malformed = saved("invalid", false, 0.0, 0).copy(originDate = "2025-02-30")
        val preview = saved("preview", true, 900.0, 1_200).copy(originDate = "2024-01-01")
        val all = listOf(current, earlier, undated, malformed, preview)
        assertEquals(listOf(2026, 2025), PassportPeriods.years(all))
        assertEquals(all, PassportPeriods.filter(all, null))
        assertEquals(listOf(earlier), PassportPeriods.filter(all, 2025))
        assertTrue(PassportPeriods.filter(all, 2024).isEmpty())
        val year = PassportMetrics.from(PassportPeriods.filter(all, 2025))
        assertEquals(1, year.runCount)
        assertEquals(120, year.knownDistanceKm)
        assertEquals(3, year.knownScheduledHours)
        assertEquals(0, year.previewCount)
        assertEquals(520, PassportMetrics.from(all).knownDistanceKm)
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

    @Test fun selectedSegmentDurationCrossesMidnightUsingDatedStopTimes() {
        val route = timedRoute(millis("2026-10-01T23:40:00+05:30"), millis("2026-10-02T01:10:00+05:30"))
        val segment = SavedJourney.from(route, JourneyPlan("B", "C"))
        assertEquals(90, segment.durationMinutes)
        assertEquals(1, PassportMetrics.from(listOf(segment)).knownScheduledHours)
        assertEquals(90L, PassportMetrics.from(listOf(segment)).knownScheduledMinutes)
        assertEquals("1h 30m", PassportMetrics.from(listOf(segment)).scheduledDurationLabel)
    }

    @Test fun selectedSegmentDurationPreservesEveryMidnightAcrossMultipleDays() {
        val route = timedRoute(millis("2026-10-01T23:40:00+05:30"), millis("2026-10-04T02:10:00+05:30"))
        val segment = SavedJourney.from(route, JourneyPlan("B", "C"))
        assertEquals(3_030, segment.durationMinutes)
        assertEquals(50, PassportMetrics.from(listOf(segment)).knownScheduledHours)
        assertEquals("50h 30m", PassportMetrics.from(listOf(segment)).scheduledDurationLabel)
    }

    @Test fun missingOrInvalidSegmentTimingDoesNotInventHoursFromTheFullRun() {
        val start = millis("2026-10-01T23:40:00+05:30")
        val end = millis("2026-10-02T01:10:00+05:30")
        val invalidTimes = listOf(
            null to end, start to null, start to start, end to start,
            Long.MIN_VALUE to Long.MAX_VALUE,
            0L to (Int.MAX_VALUE.toLong() + 1) * 60_000,
        )
        invalidTimes.forEach { (departure, arrival) ->
            val segment = SavedJourney.from(timedRoute(departure, arrival), JourneyPlan("B", "C"))
            assertEquals("Unexpected duration for $departure to $arrival", 0, segment.durationMinutes)
            assertNull(PassportMetrics.from(listOf(segment)).knownScheduledHours)
            assertNull(PassportMetrics.from(listOf(segment)).knownScheduledMinutes)
            assertEquals("—", PassportMetrics.from(listOf(segment)).scheduledDurationLabel)
        }
    }

    @Test fun wholeRunKeepsTheTrustedGatewayDuration() {
        val route = timedRoute(millis("2026-10-01T23:40:00+05:30"), millis("2026-10-04T02:10:00+05:30"))
        assertEquals(4_000, SavedJourney.from(route).durationMinutes)
    }

    @Test fun shortKnownDurationsRemainMinutesAndUnknownOrPreviewRunsAddNoHours() {
        val short = saved("short", false, 10.0, 10)
        val unknown = saved("unknown", false, 0.0, 0)
        val invalid = saved("invalid", false, 0.0, -1)
        val preview = saved("preview", true, 100.0, 180)
        val metrics = PassportMetrics.from(listOf(short, unknown, invalid, preview))
        assertEquals(10L, metrics.knownScheduledMinutes)
        assertEquals("10m", metrics.scheduledDurationLabel)
        assertEquals("—", PassportMetrics.from(listOf(unknown, invalid, preview)).scheduledDurationLabel)
        assertEquals("1h", PassportMetrics.from(listOf(short.copy(durationMinutes = 60))).scheduledDurationLabel)
    }

    private fun millis(value: String) = OffsetDateTime.parse(value).toInstant().toEpochMilli()

    private fun timedRoute(boardingDeparture: Long?, alightingArrival: Long?) = RoutePreview(
        "12137", "Punjab Mail", "A", "Origin", "C", "Destination", "10:00", "02:10", 4,
        emptyList(), listOf(
            RouteStop("A", "Origin", null, "10:00", scheduledDepartureMillis = millis("2026-10-01T10:00:00+05:30")),
            RouteStop("B", "Boarding", "23:35", "23:40",
                scheduledArrivalMillis = millis("2026-10-01T23:35:00+05:30"), scheduledDepartureMillis = boardingDeparture),
            RouteStop("C", "Destination", "02:10", null,
                scheduledArrivalMillis = alightingArrival, scheduledDepartureMillis = millis("2026-10-04T02:15:00+05:30")),
        ), durationMinutes = 4_000, isPreview = false, runDate = "2026-10-01",
        departureInstantMillis = millis("2026-10-01T10:00:00+05:30"),
        arrivalInstantMillis = millis("2026-10-04T02:10:00+05:30"),
    )
}
