package app.locomate.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local train choices; no dated status, location or provider credentials are persisted. */
class RecentTrainStore(context: Context, baseUrl: String) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("locomate.recent-trains.${railStorageScope(baseUrl)}", Context.MODE_PRIVATE)
    private val epoch = synchronized(lock) { generation }

    fun load(): List<TrainSearchResult> = synchronized(lock) {
        if (!usable()) return@synchronized emptyList()
        runCatching {
            val records = JSONArray(prefs.getString("trains", "[]"))
            (0 until records.length()).mapNotNull { index ->
                runCatching {
                    val row = records.getJSONObject(index)
                    TrainSearchResult(row.getString("number"), row.getString("name"),
                        row.getString("originCode"), row.getString("originName"),
                        row.getString("destinationCode"), row.getString("destinationName"), live = false,
                        sourceLabel = row.getString("sourceLabel"), distanceKm = row.getDouble("distanceKm"))
                }.getOrNull()?.takeIf(::valid)
            }.distinctBy { it.number }.take(10)
        }.getOrDefault(emptyList())
    }

    fun record(train: TrainSearchResult): Boolean = synchronized(lock) {
        if (!usable() || !valid(train)) return@synchronized false
        persist((listOf(train) + load().filter { it.number != train.number }).take(10))
    }

    fun clear(): Boolean = synchronized(lock) { usable() && persist(emptyList()) }

    private fun usable() = epoch == generation && !PrivacyDeletionState.pending(appContext)

    // KTX edit discards commit's result; failed writes must not report that history was cleared.
    @android.annotation.SuppressLint("UseKtx")
    private fun persist(trains: List<TrainSearchResult>): Boolean {
        val records = JSONArray()
        for (train in trains) records.put(JSONObject()
            .put("number", train.number).put("name", train.name)
            .put("originCode", train.originCode).put("originName", train.originName)
            .put("destinationCode", train.destinationCode).put("destinationName", train.destinationName)
            .put("sourceLabel", train.sourceLabel)
            .put("distanceKm", train.distanceKm.takeIf { it.isFinite() && it >= 0 } ?: 0.0))
        return prefs.edit().putString("trains", records.toString()).commit()
    }

    companion object {
        private val lock = Any()
        private var generation = 0L
        private fun valid(train: TrainSearchResult) = train.number.matches(Regex("[0-9]{4,6}")) && train.name.isNotBlank()

        /** Old callbacks cannot recreate erased preferences, even after the durable latch clears. */
        internal fun beginPrivacyDeletion() = synchronized(lock) { generation++ }
    }
}
