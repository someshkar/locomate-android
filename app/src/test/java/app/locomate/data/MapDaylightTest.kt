package app.locomate.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MapDaylightTest {
    @Test fun solarLightingUsesLongitudeAndActualUtcTime() {
        assertTrue(MapDaylight.isDay(Instant.parse("2026-03-20T12:00:00Z"), 0.0, 0.0))
        assertFalse(MapDaylight.isDay(Instant.parse("2026-03-20T00:00:00Z"), 0.0, 0.0))
        assertTrue(MapDaylight.isDay(Instant.parse("2026-03-20T06:30:00Z"), 19.0, 73.0))
        assertFalse(MapDaylight.isDay(Instant.parse("2026-03-20T18:30:00Z"), 19.0, 73.0))
    }
    @Test fun solarLightingHandlesPolarDayAndNight() {
        assertTrue(MapDaylight.isDay(Instant.parse("2026-06-21T00:00:00Z"), 89.0, 0.0))
        assertFalse(MapDaylight.isDay(Instant.parse("2026-12-21T12:00:00Z"), 89.0, 0.0))
    }
}
