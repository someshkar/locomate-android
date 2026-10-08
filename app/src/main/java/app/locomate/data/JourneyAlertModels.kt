package app.locomate.data

import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

object JourneyAlertConsent {
    const val VERSION = "journey-alerts-v1"
    const val NOTICE_HASH = "025a174cd8ee918c5bab69cf42d5d497128a0493a33631e5582b78dd361e2250"
    const val NOTICE = "Journey alerts send this device's push token, selected train runs, alert channels, and quiet hours to Locomate's gateway. The gateway uses fresh rail updates to send notifications for those runs. You can turn alerts off at any time; turning them off stops future sends after the gateway receives the change. Alerts may appear on your Lock Screen."
}

enum class JourneyAlertChannel(val wire: String, val label: String) {
    Position("position", "Observed station changes"),
    Delay("delay", "Delay changes of 5 minutes or more"),
    Platform("platform", "Platform changes"),
    Departure("departure", "Departure"),
    Arrival("arrival", "Arrival"),
}

data class JourneyAlertQuietHours(val start: String, val end: String, val timeZone: String = "Asia/Kolkata") {
    fun valid(): Boolean = runCatching {
        val clock = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        clock.matches(start) && clock.matches(end) && start != end &&
            ZoneId.of(timeZone).id.isNotBlank()
    }.getOrDefault(false)

    fun contains(timestamp: Long): Boolean {
        if (!valid()) return false
        val time = Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(timeZone)).toLocalTime()
        val from = LocalTime.parse(start)
        val until = LocalTime.parse(end)
        return if (from < until) time >= from && time < until else time >= from || time < until
    }
}

data class JourneyAlertLink(val trainNumber: String, val serviceDate: String) {
    val runId: String get() = "$trainNumber:$serviceDate"
    val url: String get() = "locomate://journeys/$trainNumber?date=$serviceDate"

    companion object {
        fun fromRunId(runId: String): JourneyAlertLink? {
            val match = Regex("^([0-9]{4,6}):([0-9]{4}-[0-9]{2}-[0-9]{2})$").matchEntire(runId) ?: return null
            val date = match.groupValues[2]
            if (runCatching { LocalDate.parse(date) }.getOrNull()?.toString() != date) return null
            return JourneyAlertLink(match.groupValues[1], date)
        }

        fun parse(value: String?): JourneyAlertLink? = runCatching {
            val uri = URI(value ?: return null)
            if (uri.scheme != "locomate" || uri.host != "journeys" || uri.userInfo != null ||
                uri.port != -1 || uri.fragment != null) return null
            val number = uri.rawPath?.removePrefix("/") ?: return null
            val date = uri.rawQuery?.takeIf { it.startsWith("date=") }?.removePrefix("date=") ?: return null
            val link = fromRunId("$number:$date") ?: return null
            link.takeIf { it.url == value }
        }.getOrNull()
    }
}

data class JourneyAlertSubscription(
    val runId: String,
    val revision: Long,
    val enabled: Boolean,
    val channels: Set<JourneyAlertChannel>,
    val quietHours: JourneyAlertQuietHours?,
    val expiresAt: Long,
    val target: String? = null,
    val pending: Boolean = true,
    val error: String? = null,
    val consentVersion: String = JourneyAlertConsent.VERSION,
    val noticeHash: String = JourneyAlertConsent.NOTICE_HASH,
) {
    val hasCurrentConsent: Boolean get() = consentVersion == JourneyAlertConsent.VERSION &&
        noticeHash == JourneyAlertConsent.NOTICE_HASH
    fun active(now: Long = System.currentTimeMillis()): Boolean = enabled && hasCurrentConsent && expiresAt > now
    val status: String get() = when {
        error != null -> error
        !enabled && pending -> "Off on this device · removal pending"
        !enabled -> "Off"
        !hasCurrentConsent -> "Review the current alert notice to enable alerts"
        !active() -> "Expired"
        pending && target == null -> "Waiting for device registration"
        pending -> "Waiting for gateway confirmation"
        else -> "Alerts on"
    }

    companion object {
        fun nextRevision(previous: Long, now: Long = System.currentTimeMillis()): Long {
            val next = maxOf(now, previous + 1, 1)
            require(next <= 9_007_199_254_740_991L)
            return next
        }

        fun localExpiry(route: RoutePreview, now: Long = System.currentTimeMillis()): Long? {
            if (route.isPreview || route.statusLabel.startsWith("STALE") ||
                route.runId?.let(JourneyAlertLink::fromRunId) == null) return null
            val departure = route.departureInstantMillis ?: return null
            val arrival = route.arrivalInstantMillis ?: route.scheduledDurationMillis
                ?.let { departure + it } ?: return null
            if (arrival <= departure) return null
            return minOf(arrival + 24 * 3_600_000L, now + 5 * 24 * 3_600_000L).takeIf { it > now }
        }
    }
}

data class JourneyAlertPayload(
    val eventId: String, val link: JourneyAlertLink, val revision: Long,
    val channel: JourneyAlertChannel, val title: String, val body: String,
    val observedAt: Long, val expiresAt: Long,
) {
    companion object {
        fun parse(data: Map<String, String>, subscription: JourneyAlertSubscription,
                  now: Long = System.currentTimeMillis()): JourneyAlertPayload? {
            if (data["type"] != "journey-alert" || data["version"] != "1" ||
                !subscription.active(now) || data["runId"] != subscription.runId ||
                data["revision"]?.toLongOrNull() != subscription.revision) return null
            val link = JourneyAlertLink.parse(data["deepLink"]) ?: return null
            if (link.runId != subscription.runId || data["trainNumber"] != link.trainNumber ||
                data["serviceDate"] != link.serviceDate) return null
            val channel = JourneyAlertChannel.entries.firstOrNull { it.wire == data["channel"] } ?: return null
            if (channel !in subscription.channels) return null
            val observed = data["observedAt"]?.toLongOrNull() ?: return null
            val expires = data["expiresAt"]?.toLongOrNull() ?: return null
            if (observed < now - 10 * 60_000L || observed > now + 60_000L || expires <= now ||
                expires <= observed || expires > observed + 10 * 60_000L || expires > subscription.expiresAt) return null
            fun text(key: String, max: Int): String? = data[key]?.takeIf {
                it.isNotBlank() && it.length <= max && it.none { char -> char.isISOControl() }
            }
            val eventId = text("eventId", 200) ?: return null
            val title = text("title", 160) ?: return null
            val body = text("body", 1_000) ?: return null
            return JourneyAlertPayload(eventId, link, subscription.revision, channel, title, body, observed, expires)
        }
    }
}
