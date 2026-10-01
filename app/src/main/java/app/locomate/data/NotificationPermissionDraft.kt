package app.locomate.data

import org.json.JSONArray
import org.json.JSONObject

/** Minimal saved-instance state for an explicit action awaiting Android's permission result. */
data class NotificationPermissionDraft(
    val reference: JourneyAlertLink,
    val channels: Set<JourneyAlertChannel> = emptySet(),
    val quietHours: JourneyAlertQuietHours? = null,
) {
    fun encode(sourceUrl: String): String = JSONObject()
        .put("source", railStorageScope(sourceUrl)).put("runId", reference.runId)
        .put("consentVersion", JourneyAlertConsent.VERSION).put("noticeHash", JourneyAlertConsent.NOTICE_HASH)
        .put("channels", JSONArray(channels.map { it.wire }))
        .put("quietHours", quietHours?.let { JSONObject().put("start", it.start)
            .put("end", it.end).put("timeZone", it.timeZone) } ?: JSONObject.NULL).toString()

    companion object {
        fun decode(value: String?, sourceUrl: String, alerts: Boolean): NotificationPermissionDraft? = runCatching {
            val json = JSONObject(value ?: return null)
            if (json.getString("source") != railStorageScope(sourceUrl)) return null
            val reference = JourneyAlertLink.fromRunId(json.getString("runId")) ?: return null
            val array = json.getJSONArray("channels")
            val channels = (0 until array.length()).map { index ->
                JourneyAlertChannel.entries.firstOrNull { it.wire == array.getString(index) } ?: return null
            }.toSet()
            val quiet = json.optJSONObject("quietHours")?.let {
                JourneyAlertQuietHours(it.getString("start"), it.getString("end"), it.getString("timeZone"))
                    .takeIf(JourneyAlertQuietHours::valid) ?: return null
            }
            if (alerts && (channels.isEmpty() || json.getString("consentVersion") != JourneyAlertConsent.VERSION ||
                    json.getString("noticeHash") != JourneyAlertConsent.NOTICE_HASH)) return null
            if (!alerts && (channels.isNotEmpty() || quiet != null)) return null
            NotificationPermissionDraft(reference, channels, quiet)
        }.getOrNull()
    }
}
