package app.locomate.data

import android.content.Context
import app.locomate.BuildConfig

/** Only the selected dated run is durable; consent and notification state are separate. */
class CurrentJourneyStore(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val appContext = context.applicationContext
    private val source = railStorageScope(baseUrl)
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    fun read(): JourneyAlertLink? {
        if (source == "preview" || prefs.getString("source", null) != source) {
            clear()
            return null
        }
        val value = prefs.getString("runId", null) ?: return null
        return JourneyAlertLink.fromRunId(value).also { if (it == null) clear() }
    }

    fun select(reference: JourneyAlertLink) {
        check(!PrivacyDeletionState.pending(appContext)) { "Data deletion is pending. Retry Delete my data in Settings." }
        require(source != "preview" && JourneyAlertLink.fromRunId(reference.runId) == reference)
        check(prefs.edit().putString("source", source).putString("runId", reference.runId).commit()) {
            "The selected journey could not be saved on this device."
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "The previous journey reference could not be removed." }
    }

    companion object { const val PREFERENCE_NAME = "locomate.current-journey" }
}
