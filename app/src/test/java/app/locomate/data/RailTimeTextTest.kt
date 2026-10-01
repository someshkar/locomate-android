package app.locomate.data

import org.junit.Assert.*
import org.junit.Test

class RailTimeTextTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test fun delayUsesNaturalLanguageAndHandlesUnknownAndEarlyValues() {
        assertEquals("18 minutes late", RailTimeText.delay(18))
        assertEquals("1 minute early", RailTimeText.delay(-1))
        assertEquals("On time", RailTimeText.delay(0))
        assertEquals("Delay unavailable", RailTimeText.delay(null))
        assertEquals("2147483648 minutes early", RailTimeText.delay(Int.MIN_VALUE))
    }

    @Test fun futureTimesUseFullDaysHoursAndMinutes() {
        assertEquals("1 day 4 hours until scheduled departure", RailTimeText.untilScheduledDeparture(now + 28 * hour, now))
        assertEquals("2 days until scheduled departure", RailTimeText.untilScheduledDeparture(now + 48 * hour, now))
        assertEquals("1 hour 1 minute until scheduled departure", RailTimeText.untilScheduledDeparture(now + hour + 60_000, now))
        assertEquals("1 minute until scheduled departure", RailTimeText.untilScheduledDeparture(now + 60_000, now))
        assertEquals("Less than a minute until scheduled departure", RailTimeText.untilScheduledDeparture(now + 59_999, now))
        assertNull(RailTimeText.untilScheduledDeparture(now, now))
        assertNull(RailTimeText.untilScheduledDeparture(now - 1, now))
    }

    @Test fun countdownFollowsPersonalBoardingAfterTheTrainOriginHasDeparted() {
        val route = route()
        val board = RailTimeText.boardingDeparture(route, JourneyPlan("B", "C"))!!
        assertEquals("B", board.stationCode)
        assertEquals(now + 28 * hour, board.departureAtMillis)
        assertNull(RailTimeText.boardingDeparture(route, JourneyPlan.default(route)))
        assertEquals("1 day 4 hours until scheduled departure", RailTimeText.untilScheduledDeparture(board.departureAtMillis, now))
    }

    @Test fun missingIntermediateDepartureNeverFallsBackToArrivalOrTrainOrigin() {
        val route = route().copy(calls = route().calls.map {
            if (it.code == "B") it.copy(scheduledDepartureMillis = null, scheduledArrivalMillis = now + hour) else it
        })
        assertNull(RailTimeText.boardingDeparture(route, JourneyPlan("B", "C")))
    }

    @Test fun previewUndatedPassedAndActuallyDepartedRunsHaveNoCountdown() {
        val route = route()
        val plan = JourneyPlan("B", "C")
        assertNull(RailTimeText.boardingDeparture(route.copy(isPreview = true), plan))
        assertNull(RailTimeText.boardingDeparture(route.copy(runDate = null), plan))
        assertNull(RailTimeText.boardingDeparture(route.copy(runDate = "2026-02-30"), plan))
        for (board in listOf(route.calls[1].copy(state = "passed"), route.calls[1].copy(actualDeparture = "12:00"))) {
            assertNull(RailTimeText.boardingDeparture(route.copy(calls = listOf(route.calls[0], board, route.calls[2])), plan))
        }
    }

    @Test fun cachedTimetableRemainsScheduledWithoutPretendingItIsLive() {
        val route = route().copy(statusLabel = "STALE · LAST KNOWN")
        val board = RailTimeText.boardingDeparture(route, JourneyPlan("B", "C"))!!
        assertEquals("1 day 4 hours until scheduled departure", RailTimeText.untilScheduledDeparture(board.departureAtMillis, now))
    }

    private fun route() = RoutePreview("12951", "Test", "A", "Origin", "C", "Destination", "10:00", "14:00", 2,
        emptyList(), listOf(
            RouteStop("A", "Origin", null, "10:00", state = "passed", scheduledDepartureMillis = now - hour),
            RouteStop("B", "Boarding", "13:00", "13:05", scheduledDepartureMillis = now + 28 * hour),
            RouteStop("C", "Destination", "14:00", null, scheduledArrivalMillis = now + 29 * hour),
        ), isPreview = false, runDate = "2026-10-01", departureInstantMillis = now - hour)
}
