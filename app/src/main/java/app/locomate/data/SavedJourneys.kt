package app.locomate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    companion object {
        fun from(route: RoutePreview): SavedJourney = SavedJourney(
            key = if (route.isPreview) "preview:${route.trainNumber}" else "run:${route.trainNumber}:${route.runDate}",
            trainNumber = route.trainNumber,
            trainName = route.displayName,
            originCode = route.originCode,
            originName = route.originName,
            destinationCode = route.destinationCode,
            destinationName = route.destinationName,
            originDate = route.runDate,
            distanceKm = route.distanceKm,
            durationMinutes = route.durationMinutes,
            preview = route.isPreview,
        )
    }
}

data class PassportMetrics(
    val runCount: Int,
    val previewCount: Int,
    val knownDistanceKm: Int?,
    val stationCount: Int,
    val knownScheduledHours: Int?,
) {
    companion object {
        fun from(journeys: List<SavedJourney>): PassportMetrics {
            val runs = journeys.filterNot { it.preview }
            val knownDistance = runs.filter { it.distanceKm > 0 }
            val knownDuration = runs.filter { it.durationMinutes > 0 }
            return PassportMetrics(
                runCount = runs.size,
                previewCount = journeys.size - runs.size,
                knownDistanceKm = knownDistance.takeIf { it.isNotEmpty() }?.sumOf { it.distanceKm }?.toInt(),
                stationCount = runs.flatMap { listOf(it.originCode, it.destinationCode) }.distinct().size,
                knownScheduledHours = knownDuration.takeIf { it.isNotEmpty() }
                    ?.sumOf { it.durationMinutes }?.div(60),
            )
        }
    }
}

class SavedJourneyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("locomate.passport", Context.MODE_PRIVATE)

    fun load(): List<SavedJourney> = runCatching {
        val json = JSONArray(preferences.getString("journeys_v1", "[]"))
        (0 until json.length()).map { index ->
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
