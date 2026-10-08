package app.locomate.data

import java.time.LocalDate

/** Human-readable timing with no status inferred from a timetable. */
object RailTimeText {
    /** Only presentation rounds; all timetable and forecast arithmetic retains the wire precision. */
    fun delay(minutes: Number?): String {
        val value = minutes?.toDouble()?.takeIf { it.isFinite() } ?: return "Delay unavailable"
        if (value == 0.0) return "On time"
        val magnitude = kotlin.math.abs(value)
        val label = if (magnitude < 1.0) "Less than a minute" else {
            val whole = kotlin.math.floor(magnitude + 0.5).toLong()
            quantity(whole, "minute")
        }
        return "$label ${if (value < 0) "early" else "late"}"
    }

    fun untilScheduledDeparture(departureAtMillis: Long, nowMillis: Long): String? {
        if (departureAtMillis <= nowMillis) return null
        val remaining = departureAtMillis - nowMillis
        if (remaining < 60_000) return "Less than a minute until scheduled departure"
        val minutes = remaining / 60_000
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val remainder = minutes % 60
        val parts = when {
            days > 0 -> listOf(quantity(days, "day"), hours.takeIf { it > 0 }?.let { quantity(it, "hour") })
            hours > 0 -> listOf(quantity(hours, "hour"), remainder.takeIf { it > 0 }?.let { quantity(it, "minute") })
            else -> listOf(quantity(minutes, "minute"))
        }
        return "${parts.filterNotNull().joinToString(" ")} until scheduled departure"
    }

    /** A personal boarding plan must use its own known departure, never the train origin's clock. */
    fun boardingDeparture(route: RoutePreview, plan: JourneyPlan?): ScheduledBoarding? {
        if (route.isPreview || route.runDate == null || runCatching { LocalDate.parse(route.runDate) }.isFailure) return null
        val selected = plan?.takeIf { it.isValidFor(route) } ?: JourneyPlan.default(route)
        val board = route.calls.firstOrNull { it.code == selected.boardingCode } ?: return null
        if (board.state == "passed" || board.actualDeparture != null) return null
        val at = board.scheduledDepartureMillis
            ?: route.departureInstantMillis.takeIf { board == route.calls.firstOrNull() }
            ?: return null
        return ScheduledBoarding(board.code, at)
    }

    private fun quantity(value: Long, unit: String) = "$value $unit${if (value == 1L) "" else "s"}"
}

data class ScheduledBoarding(val stationCode: String, val departureAtMillis: Long)
