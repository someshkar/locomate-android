package app.locomate.data

import android.content.Context
import org.json.JSONArray

data class RailPoint(val latitude: Double, val longitude: Double)

data class RouteStop(
    val code: String,
    val name: String,
    val scheduledArrival: String?,
    val scheduledDeparture: String?,
    val state: String = "upcoming",
    val actualArrival: String? = null,
    val actualDeparture: String? = null,
    val delayMinutes: Double? = null,
    val forecastP10: String? = null,
    val forecastP50: String? = null,
    val forecastP90: String? = null,
    val forecastSource: String? = null,
    val fallbackReason: String? = null,
    val platform: String? = null,
    val distanceKm: Double? = null,
    val scheduledArrivalMillis: Long? = null,
    val scheduledDepartureMillis: Long? = null,
    val day: Int = 1,
)

data class RoutePreview(
    val trainNumber: String,
    val name: String,
    val originCode: String,
    val originName: String,
    val destinationCode: String,
    val destinationName: String,
    val departure: String,
    val arrival: String,
    val arrivalDay: Int,
    val geometry: List<RailPoint>,
    val calls: List<RouteStop>,
    val distanceKm: Double = 0.0,
    val durationMinutes: Int = 0,
    /** Exact duration from fractional wire minutes; null denotes a legacy whole-minute record. */
    val durationMillis: Long? = null,
    val forecastDelayMinutes: Double? = null,
    val forecastLeadMinutes: Double? = null,
    val forecastAgeSeconds: Double? = null,
    val isPreview: Boolean = true,
    val runId: String? = null,
    val runDate: String? = null,
    val statusLabel: String = "PREVIEW · NOT LIVE",
    val sourceDetail: String = "Historical timetable sample. No live position or ETA.",
    val etaBand: String? = null,
    val departureInstantMillis: Long? = null,
    val arrivalInstantMillis: Long? = null,
    val positionProgress: Double? = null,
    val positionStatus: String? = null,
    /** Local receipt time; retained snapshots must not gain a new freshness window when reused. */
    val receivedAtMillis: Long? = null,
) {
    val scheduledDurationMillis: Long? get() = durationMillis?.takeIf { it > 0 }
        ?: durationMinutes.takeIf { it > 0 }?.toLong()?.times(60_000)
    val displayName: String get() = if ("Rajdhani" in name && '-' in name) {
        "${name.substringBefore('-').removeSuffix(" Central")} Rajdhani"
    } else name
    val routeLabel: String get() = "${stationCase(originName)} to ${stationCase(destinationName)}"

    private fun stationCase(value: String): String = value.split(' ').joinToString(" ") { word ->
        if (word in setOf("CST", "JN", "MMCT")) word else word.lowercase().replaceFirstChar { it.titlecase() }
    }
}

/** Historical route packs from SmartRail; these are never represented as live data. */
object PreviewRoutes {
    fun load(context: Context): List<RoutePreview> = runCatching {
        val text = context.assets.open("route-packs.json").bufferedReader().use { it.readText() }
        val packs = JSONArray(text)
        (0 until packs.length()).map { index ->
            val pack = packs.getJSONObject(index)
            val geometry = pack.getJSONArray("geometry")
            val calls = pack.getJSONArray("calls")
            RoutePreview(
                trainNumber = pack.getString("trainNumber"),
                name = pack.getString("name"),
                originCode = pack.getString("originCode"),
                originName = pack.getString("originName"),
                destinationCode = pack.getString("destinationCode"),
                destinationName = pack.getString("destinationName"),
                departure = pack.getString("departure").take(5),
                arrival = pack.getString("arrival").take(5),
                arrivalDay = calls.getJSONObject(calls.length() - 1).getInt("day"),
                geometry = (0 until geometry.length()).map { point ->
                    val pair = geometry.getJSONArray(point)
                    RailPoint(pair.getDouble(0), pair.getDouble(1))
                },
                calls = (0 until calls.length()).map { call ->
                    val stop = calls.getJSONObject(call)
                    RouteStop(
                        code = stop.getString("code"),
                        name = stop.getString("name"),
                        scheduledArrival = stop.optString("arrival").takeIf { it.isNotBlank() && it != "null" }?.take(5),
                        scheduledDeparture = stop.optString("departure").takeIf { it.isNotBlank() && it != "null" }?.take(5),
                        day = stop.getInt("day"),
                    )
                },
                distanceKm = pack.optDouble("distanceKm", 0.0),
                durationMinutes = pack.optInt("durationMinutes", 0),
                positionProgress = 0.5,
                positionStatus = "Historical route sample · not live",
            )
        }
    }.getOrElse { emptyList() }
}
