package app.locomate.data

import android.content.Context
import android.content.SharedPreferences
import app.locomate.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Mutations are committed before work is enqueued and survive process death or an offline gateway. */
class JourneyAlertStore(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "locomate.journey-alerts.${railStorageScope(baseUrl)}", Context.MODE_PRIVATE)

    fun all(): List<JourneyAlertSubscription> = synchronized(lock) { read() }
    fun get(runId: String): JourneyAlertSubscription? = all().firstOrNull { it.runId == runId }
    fun hasActive(): Boolean = all().any { it.active() }
    fun clear(): Boolean = synchronized(lock) { preferences.edit().clear().commit() }

    fun observe(changed: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun enable(route: RoutePreview, channels: Set<JourneyAlertChannel>, quietHours: JourneyAlertQuietHours?) =
        synchronized(lock) {
            require(channels.isNotEmpty()) { "Choose at least one alert" }
            require(quietHours == null || quietHours.valid()) { "Enter different quiet-hour times in HH:mm format" }
            val expiry = JourneyAlertSubscription.localExpiry(route) ?: error("A current dated journey is required")
            val runId = requireNotNull(route.runId)
            val previous = read().firstOrNull { it.runId == runId }
            put(JourneyAlertSubscription(runId, JourneyAlertSubscription.nextRevision(previous?.revision ?: 0),
                true, channels, quietHours, expiry, previous?.target))
        }

    fun disable(runId: String) = synchronized(lock) {
        val previous = read().firstOrNull { it.runId == runId } ?: return@synchronized
        if (!previous.enabled) return@synchronized
        put(previous.copy(revision = JourneyAlertSubscription.nextRevision(previous.revision),
            enabled = false, pending = true, error = null))
    }

    fun expire(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        read().filter { it.enabled && (it.expiresAt <= now || !it.hasCurrentConsent) }.forEach { disable(it.runId) }
    }

    fun updateTarget(target: String) = synchronized(lock) {
        require(target.length in 20..4096 && target.all { it.code in 0x21..0x7e })
        read().filter { it.active() && it.target != target }.forEach {
            put(it.copy(target = target, revision = JourneyAlertSubscription.nextRevision(it.revision),
                pending = true, error = null))
        }
    }

    fun acknowledge(runId: String, revision: Long, expiresAt: Long? = null) = synchronized(lock) {
        val current = read().firstOrNull { it.runId == runId && it.revision == revision } ?: return@synchronized
        put(current.copy(pending = false, error = null,
            expiresAt = expiresAt?.coerceAtMost(current.expiresAt) ?: current.expiresAt))
    }

    fun failed(runId: String, revision: Long, message: String) = synchronized(lock) {
        val current = read().firstOrNull { it.runId == runId && it.revision == revision } ?: return@synchronized
        put(current.copy(error = message))
    }

    fun resolveConflict(runId: String, revision: Long, serverRevision: Long) = synchronized(lock) {
        val current = read().firstOrNull { it.runId == runId && it.revision == revision } ?: return@synchronized
        put(current.copy(revision = JourneyAlertSubscription.nextRevision(maxOf(revision, serverRevision)),
            pending = true, error = null))
    }

    /** Called under the same store lock as displaying a notification to serialize withdrawal and delivery. */
    fun deliver(data: Map<String, String>, display: (JourneyAlertPayload) -> Boolean): Boolean = synchronized(lock) {
        val current = read().firstOrNull { it.runId == data["runId"] } ?: return@synchronized false
        val payload = JourneyAlertPayload.parse(data, current) ?: return@synchronized false
        val seenKey = "seen.${current.runId}"
        val seenArray = JSONArray(preferences.getString(seenKey, "[]"))
        val seen = (0 until seenArray.length()).map { seenArray.getString(it) }
        if (payload.eventId in seen || current.quietHours?.contains(System.currentTimeMillis()) == true) {
            return@synchronized false
        }
        // Persist first. A restart must not ring twice for an already handled event.
        val retained = seen.takeLast(99) + payload.eventId
        if (!preferences.edit().putString(seenKey, JSONArray(retained).toString()).commit()) return@synchronized false
        display(payload)
    }

    private fun read(): List<JourneyAlertSubscription> {
        val values = JSONArray(preferences.getString("subscriptions", "[]"))
        return (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val channels = item.getJSONArray("channels")
            val quiet = item.optJSONObject("quietHours")?.let {
                JourneyAlertQuietHours(it.getString("start"), it.getString("end"), it.getString("timeZone"))
            }
            val subscription = JourneyAlertSubscription(item.getString("runId"), item.getLong("revision"),
                item.getBoolean("enabled"), (0 until channels.length()).mapNotNull { i ->
                    JourneyAlertChannel.entries.firstOrNull { it.wire == channels.getString(i) }
                }.toSet(), quiet, item.getLong("expiresAt"),
                item.optString("target").takeIf { it.isNotEmpty() }, item.getBoolean("pending"),
                item.optString("error").takeIf { it.isNotEmpty() },
                item.optString("consentVersion", ""), item.optString("noticeHash", ""))
            check(JourneyAlertLink.fromRunId(subscription.runId) != null &&
                subscription.revision in 1..9_007_199_254_740_991L && subscription.channels.isNotEmpty() &&
                (subscription.quietHours == null || subscription.quietHours.valid()) && subscription.expiresAt > 0 &&
                (subscription.target == null || (subscription.target.length in 20..4096 &&
                    subscription.target.all { it.code in 0x21..0x7e }))) { "Saved alert choices contain invalid data" }
            subscription
        }
    }

    private fun put(record: JourneyAlertSubscription) {
        val records = read().filterNot { it.runId == record.runId } + record
        val encoded = JSONArray().also { array -> records.forEach { item ->
            array.put(JSONObject().put("runId", item.runId).put("revision", item.revision)
                .put("enabled", item.enabled).put("channels", JSONArray(item.channels.map { it.wire }))
                .put("quietHours", item.quietHours?.let { JSONObject().put("start", it.start)
                    .put("end", it.end).put("timeZone", it.timeZone) })
                .put("expiresAt", item.expiresAt).put("target", item.target)
                .put("pending", item.pending).put("error", item.error)
                .put("consentVersion", item.consentVersion).put("noticeHash", item.noticeHash))
        } }
        check(preferences.edit().putString("subscriptions", encoded.toString()).commit()) {
            "Could not save alert preferences on this device"
        }
    }

    companion object { private val lock = Any() }
}
