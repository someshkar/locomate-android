package app.locomate.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import app.locomate.BuildConfig
import app.locomate.MainActivity
import app.locomate.R

/** A quiet, user-started status card that follows one dated production run. */
class JourneyStatusNotification(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class ActiveRun(val runId: String, val trainNumber: String, val originDate: String)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val scope = railStorageScope(BuildConfig.RAIL_API_URL)
    private val preferences = appContext.getSharedPreferences("locomate.status-card.$scope", Context.MODE_PRIVATE)

    fun activeRun(): ActiveRun? = synchronized(lock) { activeRunLocked() }

    /** Invalidates UI only. Read activeRun later, after the posting transaction releases its lock. */
    fun observe(changed: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun activeRunLocked(): ActiveRun? {
        val active = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null } ?: run {
            clearStoredRun()
            return null
        }
        if (StatusPushWork.deleting(appContext) || !notificationsAllowed() ||
            active.notification.extras.getString(KEY_SCOPE) != scope) {
            cancelLocked()
            return null
        }
        val runId = preferences.getString(KEY_RUN_ID, null)
        val trainNumber = preferences.getString(KEY_TRAIN, null)
        val originDate = preferences.getString(KEY_DATE, null)
        val expiresAt = preferences.getLong(KEY_EXPIRES_AT, 0L)
        val link = runId?.let(JourneyAlertLink::fromRunId)
        if (link == null || trainNumber != link.trainNumber || originDate != link.serviceDate ||
            expiresAt <= nowMillis() || active.notification.extras.getString(KEY_RUN_ID) != runId) {
            cancelLocked()
            return null
        }
        return ActiveRun(link.runId, link.trainNumber, link.serviceDate)
    }

    /** Only a direct user action may start or restart a status card. */
    fun enable(route: RoutePreview): Boolean = synchronized(lock) { postRouteLocked(route) }

    /** A late fetch must not recreate a card ended by a push, dismissal, or expiry. */
    fun refresh(route: RoutePreview): Boolean = synchronized(lock) {
        val active = activeRunLocked() ?: return@synchronized false
        if (route.runId != active.runId || route.trainNumber != active.trainNumber ||
            route.runDate != active.originDate) return@synchronized false
        postRouteLocked(route)
    }

    private fun postRouteLocked(route: RoutePreview): Boolean {
        if (StatusPushWork.deleting(appContext)) return false
        val runId = route.runId ?: return false
        val originDate = route.runDate ?: return false
        val link = JourneyAlertLink.fromRunId(runId) ?: return false
        if (link.trainNumber != route.trainNumber || link.serviceDate != originDate) return false
        if (route.isPreview || route.statusLabel.startsWith("STALE")) return false
        val now = nowMillis()
        val receivedAt = route.receivedAtMillis ?: return false
        if (receivedAt <= now - CARD_LIFETIME_MILLIS || receivedAt > now + 60_000L) return false
        val expiresAt = minOf(receivedAt + CARD_LIFETIME_MILLIS, now + CARD_LIFETIME_MILLIS)

        val channel = NotificationChannel(CHANNEL_ID, "Active journey", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet status updates for a journey you chose to follow."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        if (!notificationsAllowed()) return false

        val timeLabel = if (route.statusLabel.startsWith("PREDICTED")) "ETA" else "Scheduled arrival"
        val status = "$timeLabel ${route.arrival} · ${route.statusLabel}"
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("${route.trainNumber} toward ${route.destinationCode}")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText("${route.displayName} · $status"))
            .setContentIntent(openJourney(link))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter(expiresAt - now)
            .addExtras(cardExtras(runId, expiresAt))
            .build()

        return postLocked(notification, ActiveRun(runId, route.trainNumber, originDate), expiresAt)
    }

    /** Data-only FCM messages may refresh only the card the traveller started. */
    fun applyPush(data: Map<String, String>): Boolean = synchronized(lock) { applyPushLocked(data) }

    private fun applyPushLocked(data: Map<String, String>): Boolean {
        if (StatusPushWork.deleting(appContext)) return false
        val active = activeRunLocked() ?: return false
        if (data["runId"] != active.runId) return false
        val now = nowMillis()
        val observedAt = data["observedAt"]?.toLongOrNull() ?: return false
        if (observedAt < now - CARD_LIFETIME_MILLIS || observedAt > now + 60_000L ||
            observedAt <= preferences.getLong(KEY_LAST_PUSH_OBSERVED, 0L)) return false
        if (data["event"] == "end") {
            cancelLocked()
            return true
        }
        if (data["event"] != "update" || data["trainNumber"] != active.trainNumber ||
            data["originDate"] != active.originDate) return false
        val nextStation = data["nextStation"]?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return false
        val eta = data["eta"]?.takeIf { Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$").matches(it) } ?: return false
        val delayLabel = data["delayLabel"]?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return false
        val expiresAt = data["expiresAt"]?.toLongOrNull() ?: return false
        if (expiresAt <= now || expiresAt > observedAt + CARD_LIFETIME_MILLIS || !notificationsAllowed()) {
            return false
        }
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("${active.trainNumber} toward $nextStation")
            .setContentText("ETA $eta · $delayLabel")
            .setStyle(Notification.BigTextStyle().bigText("Next $nextStation · ETA $eta · $delayLabel"))
            .setContentIntent(openJourney(JourneyAlertLink(active.trainNumber, active.originDate)))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter((expiresAt - now).coerceAtMost(CARD_LIFETIME_MILLIS))
            .addExtras(cardExtras(active.runId, expiresAt))
            .build()
        return postLocked(notification, active, expiresAt, observedAt)
    }

    private fun postLocked(notification: Notification, active: ActiveRun, expiresAt: Long,
                           observedAt: Long? = null): Boolean {
        val previousRunId = preferences.getString(KEY_RUN_ID, null)
        if (!preferences.edit()
                .putString(KEY_RUN_ID, active.runId)
                .putString(KEY_TRAIN, active.trainNumber)
                .putString(KEY_DATE, active.originDate)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply {
                    if (previousRunId != active.runId) remove(KEY_LAST_PUSH_OBSERVED)
                    if (observedAt != null) putLong(KEY_LAST_PUSH_OBSERVED, observedAt)
                }.commit()) return false
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            StatusPushWork.scheduleExpiryCheck(appContext)
            true
        } catch (_: SecurityException) {
            cancelLocked()
            false
        }
    }

    private fun cardExtras(runId: String, expiresAt: Long) = Bundle().apply {
        putString(KEY_SCOPE, scope)
        putString(KEY_RUN_ID, runId)
        putLong(KEY_EXPIRES_AT, expiresAt)
    }

    private fun openJourney(link: JourneyAlertLink): PendingIntent = PendingIntent.getActivity(
        appContext, NOTIFICATION_ID,
        Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(link.url)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationsAllowed(): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun cancel(): Unit = synchronized(lock) { cancelLocked() }

    private fun cancelLocked() {
        manager.cancel(NOTIFICATION_ID)
        clearStoredRun()
    }

    private fun clearStoredRun() {
        preferences.edit().remove(KEY_RUN_ID).remove(KEY_TRAIN).remove(KEY_DATE)
            .remove(KEY_LAST_PUSH_OBSERVED).remove(KEY_EXPIRES_AT).commit()
    }

    private companion object {
        // UI, workers, and Firebase service use separate controller instances in the same process.
        val lock = Any()
        const val CHANNEL_ID = "active-journey"
        const val NOTIFICATION_ID = 1001
        const val CARD_LIFETIME_MILLIS = 10 * 60_000L
        const val KEY_RUN_ID = "runId"
        const val KEY_TRAIN = "trainNumber"
        const val KEY_DATE = "originDate"
        const val KEY_SCOPE = "gatewayScope"
        const val KEY_EXPIRES_AT = "expiresAt"
        const val KEY_LAST_PUSH_OBSERVED = "lastPushObservedAt"
    }
}
