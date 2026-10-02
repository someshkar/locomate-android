package app.locomate.data

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RailClockSequenceTest {
    @Test fun overnightTimetableAdvancesIndiaServiceDays() {
        val calls = RailClockSequence.resolve("2026-10-01", listOf(
            null to "19:35",
            "23:55" to "00:05",
            "23:50" to "23:55",
            "05:15" to null,
        ))
        assertEquals(1, RailClockSequence.dayNumber("2026-10-01", calls[0].departureMillis))
        assertEquals(2, RailClockSequence.dayNumber("2026-10-01", calls[1].departureMillis))
        assertEquals(2, RailClockSequence.dayNumber("2026-10-01", calls[2].arrivalMillis))
        assertEquals(3, RailClockSequence.dayNumber("2026-10-01", calls[3].arrivalMillis))
    }

    @Test fun isoTimesRemainExact() {
        val expected = OffsetDateTime.parse("2026-10-01T19:35:00+05:30").toInstant().toEpochMilli()
        val calls = RailClockSequence.resolve("2026-10-01", listOf(
            null to "2026-10-01T19:35:00+05:30",
            "2026-10-02T05:15:00+05:30" to null,
        ))
        assertEquals(expected, calls[0].departureMillis)
        assertEquals(2, RailClockSequence.dayNumber("2026-10-01", calls[1].arrivalMillis))
    }
}
