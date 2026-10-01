package app.locomate.data

import android.content.Context
import app.locomate.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Private, on-device journey summary; no PNR, seat, or precise location. */
data class SavedJourney(
    val key: String,
    val trainNumber: String,
    val trainName: String,
    val originCode: String,
    val originName: String,
    val destinationCode: String,
    val destinationName: String,
    val originDate: String?,
    val distanceKm: Double,
    val durationMinutes: Int,
    val preview: Boolean,
) {
    /** The service origin date, not when this record was saved or a claim about travel completion. */
    val originYear: Int? get() = if (preview || originDate?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) != true) null
        else runCatching { LocalDate.parse(originDate).year }.getOrNull()

    companion object {
        fun from(route: RoutePreview, plan: JourneyPlan = JourneyPlan.default(route)): SavedJourney {
            val validPlan = plan.takeIf { it.isValidFor(route) } ?: JourneyPlan.default(route)
            val board = route.calls.firstOrNull { it.code == validPlan.boardingCode }
            val leave = route.calls.firstOrNull { it.code == validPlan.alightingCode }
            val wholeRun = board == route.calls.firstOrNull() && leave == route.calls.lastOrNull()
            val baseKey = if (route.isPreview) "preview:${route.trainNumber}" else "run:${route.trainNumber}:${route.runDate}"
            val distance = if (wholeRun) route.distanceKm else if (board?.distanceKm != null && leave?.distanceKm != null)
                (leave.distanceKm - board.distanceKm).coerceAtLeast(0.0) else 0.0
            val duration = if (wholeRun) route.durationMinutes else {
                val departure = board?.scheduledDepartureMillis
                val arrival = leave?.scheduledArrivalMillis
                if (departure == null || arrival == null || arrival <= departure) 0
                else ((arrival - departure) / 60_000)
                    .takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: 0
            }
            return SavedJourney(
            key = if (wholeRun) baseKey else "$baseKey:${validPlan.boardingCode}:${validPlan.alightingCode}",
            trainNumber = route.trainNumber,
            trainName = route.displayName,
            originCode = board?.code ?: route.originCode,
            originName = board?.name ?: route.originName,
            destinationCode = leave?.code ?: route.destinationCode,
            destinationName = leave?.name ?: route.destinationName,
            originDate = route.runDate,
            distanceKm = distance,
            durationMinutes = duration,
            preview = route.isPreview,
        )
        }
    }
}

object PassportPeriods {
    fun years(journeys: List<SavedJourney>): List<Int> = journeys.mapNotNull { it.originYear }.distinct().sortedDescending()
    fun filter(journeys: List<SavedJourney>, year: Int?): List<SavedJourney> =
        if (year == null) journeys else journeys.filter { it.originYear == year }
}

data class PassportMetrics(
    val runCount: Int,
    val previewCount: Int,
    val knownDistanceKm: Int?,
    val stationCount: Int,
    val knownScheduledHours: Int?,
    val knownScheduledMinutes: Long? = knownScheduledHours?.toLong()?.times(60),
) {
    val scheduledDurationLabel: String get() {
        val minutes = knownScheduledMinutes?.takeIf { it > 0 } ?: return "—"
        val hours = minutes / 60
        val remainder = minutes % 60
        return when {
            hours == 0L -> "${minutes}m"
            remainder == 0L -> "${hours}h"
            else -> "${hours}h ${remainder}m"
        }
    }

    companion object {
        fun from(journeys: List<SavedJourney>): PassportMetrics {
            val runs = journeys.filterNot { it.preview }
            val knownDistance = runs.filter { it.distanceKm > 0 }
            val knownDuration = runs.filter { it.durationMinutes > 0 }
            val totalMinutes = knownDuration.takeIf { it.isNotEmpty() }?.sumOf { it.durationMinutes.toLong() }
            return PassportMetrics(
                runCount = runs.size,
                previewCount = journeys.size - runs.size,
                knownDistanceKm = knownDistance.takeIf { it.isNotEmpty() }?.sumOf { it.distanceKm }?.toInt(),
                stationCount = runs.flatMap { listOf(it.originCode, it.destinationCode) }.distinct().size,
                knownScheduledHours = totalMinutes?.div(60)?.takeIf { it <= Int.MAX_VALUE }?.toInt(),
                knownScheduledMinutes = totalMinutes,
            )
        }
    }
}

class SavedJourneyStore(context: Context) {
    private val scope = railStorageScope(BuildConfig.RAIL_API_URL)
    private val preferences = context.applicationContext.getSharedPreferences("locomate.passport.$scope", Context.MODE_PRIVATE)
    private val legacy = context.applicationContext.getSharedPreferences("locomate.passport", Context.MODE_PRIVATE)

    fun load(): List<SavedJourney> = runCatching {
        val scoped = preferences.getString("journeys_v1", null)
        val json = JSONArray(scoped ?: if (scope == "preview") legacy.getString("journeys_v1", "[]") else "[]")
        val parsed = (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            SavedJourney(
                key = item.getString("key"), trainNumber = item.getString("trainNumber"),
                trainName = item.getString("trainName"), originCode = item.getString("originCode"),
                originName = item.getString("originName"), destinationCode = item.getString("destinationCode"),
                destinationName = item.getString("destinationName"),
                originDate = if (item.isNull("originDate")) null else item.optString("originDate"),
                distanceKm = item.optDouble("distanceKm", 0.0),
                durationMinutes = item.optInt("durationMinutes", 0),
                preview = item.optBoolean("preview", false),
            )
        }
        if (scoped == null && scope == "preview") parsed.filter(SavedJourney::preview).also(::save)
        else parsed
    }.getOrDefault(emptyList())

    fun save(journeys: List<SavedJourney>) {
        val array = JSONArray()
        journeys.forEach { journey ->
            array.put(JSONObject().apply {
                put("key", journey.key)
                put("trainNumber", journey.trainNumber)
                put("trainName", journey.trainName)
                put("originCode", journey.originCode)
                put("originName", journey.originName)
                put("destinationCode", journey.destinationCode)
                put("destinationName", journey.destinationName)
                put("originDate", journey.originDate)
                put("distanceKm", journey.distanceKm)
                put("durationMinutes", journey.durationMinutes)
                put("preview", journey.preview)
            })
        }
        preferences.edit().putString("journeys_v1", array.toString()).apply()
    }
}
