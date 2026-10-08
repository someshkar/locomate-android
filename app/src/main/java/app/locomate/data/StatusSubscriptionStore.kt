package app.locomate.data

import android.content.Context
import app.locomate.BuildConfig
import org.json.JSONObject

data class StatusSubscriptionMutation(val runId: String, val revision: Long, val enabled: Boolean, val target: String?)

/** Global monotonic mutation order for the installation's single status card, retained across restarts. */
class StatusSubscriptionStore(context: Context, private val nowMillis: () -> Long = System::currentTimeMillis) {
    private val appContext = context.applicationContext
    private val preferences get() = appContext.getSharedPreferences(
        "locomate.status-subscriptions.${railStorageScope(BuildConfig.RAIL_API_URL)}", Context.MODE_PRIVATE)
    fun load(runId: String): StatusSubscriptionMutation? = synchronized(lock) {
        preferences.getString(runId, null)?.let { text -> runCatching {
            val item = JSONObject(text)
            StatusSubscriptionMutation(runId, item.getLong("revision"), item.getBoolean("enabled"),
                if (item.isNull("target")) null else item.getString("target"))
        }.getOrNull() }
    }
    fun stage(runId: String, enabled: Boolean, target: String? = null,
              afterRevision: Long = 0): StatusSubscriptionMutation = synchronized(lock) {
        PrivacyDeletionState.withDataAccess(appContext) {
        require(JourneyAlertLink.fromRunId(runId) != null)
        val previous = load(runId)
        if (enabled && previous?.enabled == true && previous.target == target && previous.revision > afterRevision &&
            previous.revision == preferences.getLong("lastRevision", 0))
            return@withDataAccess previous
        val revision = maxOf(nowMillis().coerceAtLeast(1) * 1_000,
            preferences.getLong("lastRevision", 0) + 1, afterRevision + 1)
        require(revision in 1..9_007_199_254_740_991L)
        val result = StatusSubscriptionMutation(runId, revision, enabled, target)
        check(preferences.edit().putLong("lastRevision", revision).putString(runId, JSONObject()
            .put("revision", revision).put("enabled", enabled).put("target", target).toString()).commit()) {
            "Status delivery change could not be saved"
        }
        result
        }
    }
    companion object { private val lock = Any() }
}
