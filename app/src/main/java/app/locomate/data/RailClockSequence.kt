package app.locomate.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ScheduledStopTimes(val arrivalMillis: Long?, val departureMillis: Long?)

/** Reconstructs India service days from the ordered canonical station timetable. */
object RailClockSequence {
    private val india = ZoneId.of("Asia/Kolkata")

    fun resolve(originDate: String, calls: List<Pair<String?, String?>>): List<ScheduledStopTimes> {
        var day = LocalDate.parse(originDate)
        var lastMinute: Int? = null

        fun one(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            if (value.contains('T')) {
                val instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
                    ?: return null
                val local = instant.atZone(india)
                day = local.toLocalDate()
                lastMinute = local.hour * 60 + local.minute
                return instant.toEpochMilli()
            }
            val clock = runCatching { LocalTime.parse(value.take(5)) }.getOrNull() ?: return null
            val minute = clock.hour * 60 + clock.minute
            if (lastMinute != null && minute < lastMinute!!) day = day.plusDays(1)
            lastMinute = minute
            return day.atTime(clock).atZone(india).toInstant().toEpochMilli()
        }

        return calls.map { (arrival, departure) ->
            ScheduledStopTimes(one(arrival), one(departure))
        }
    }

    fun dayNumber(originDate: String, millis: Long?): Int {
        if (millis == null) return 1
        val serviceDate = LocalDate.parse(originDate)
        val target = java.time.Instant.ofEpochMilli(millis).atZone(india).toLocalDate()
        return (ChronoUnit.DAYS.between(serviceDate, target) + 1).toInt().coerceAtLeast(1)
    }
}
