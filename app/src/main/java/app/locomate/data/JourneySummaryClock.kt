package app.locomate.data

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The summary clock belongs to its selected station call. */
data class JourneySummaryClock(val time: String?, val label: String, val evidence: Evidence) {
    enum class Evidence { Scheduled, Estimated, Recorded }

    companion object {
        fun forStop(stop: RouteStop?, departure: Boolean, preview: Boolean, stale: Boolean,
                    originDeparture: String? = null): JourneySummaryClock {
            val event = if (departure) "departure" else "arrival"
            val actual = if (preview) null else validTime(if (departure) stop?.actualDeparture else stop?.actualArrival)
            if (actual != null) return JourneySummaryClock(actual,
                "${if (stale) "Saved actual" else "Actual"} $event", Evidence.Recorded)
            val forecast = if (!departure && !preview && !stale && stop?.forecastSource in setOf("observed", "empirical", "baseline", "physical-transition")) validTime(stop?.forecastP50) else null
            if (forecast != null) return JourneySummaryClock(forecast,
                if (stop?.forecastSource == "observed") "Observed arrival" else "Estimated arrival",
                if (stop?.forecastSource == "observed") Evidence.Recorded else Evidence.Estimated)
            val schedule = if (departure) validTime(stop?.scheduledDeparture) ?: validTime(originDeparture)
                else validTime(stop?.scheduledArrival)
            return JourneySummaryClock(schedule, "${if (stale) "Saved scheduled" else "Scheduled"} $event", Evidence.Scheduled)
        }

        private fun validTime(value: String?): String? {
            if (value == null) return null
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            if ('T' in value) return runCatching {
                OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Kolkata")).format(formatter)
            }.getOrNull()
            if (!Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?").matches(value)) return null
            return runCatching { LocalTime.parse(value).format(formatter) }.getOrNull()
        }
    }
}
