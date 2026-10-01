package app.locomate.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import app.locomate.BuildConfig
import app.locomate.MainActivity
import app.locomate.R

/** A quiet, user-started status card that follows one dated production run. */
class JourneyStatusNotification(context: Context) {
    data class ActiveRun(val runId: String, val trainNumber: String, val originDate: String)

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val scope = railStorageScope(BuildConfig.RAIL_API_URL)
    private val preferences = appContext.getSharedPreferences("locomate.status-card.$scope", Context.MODE_PRIVATE)

    fun activeRun(): ActiveRun? {
        val active = manager.activeNotifications.firstOrNull { it.id == NOTIFICATION_ID } ?: run {
            clearStoredRun()
            return null
        }
        if (active.notification.extras.getString(KEY_SCOPE) != scope) {
            manager.cancel(NOTIFICATION_ID)
            clearStoredRun()
            return null
        }
        val runId = preferences.getString(KEY_RUN_ID, null)
        val trainNumber = preferences.getString(KEY_TRAIN, null)
        val originDate = preferences.getString(KEY_DATE, null)
        if (runId == null || trainNumber == null || originDate == null) {
            cancel()
            return null
        }
        return ActiveRun(runId, trainNumber, originDate)
    }

    fun show(route: RoutePreview): Boolean {
        if (StatusPushWork.deleting(appContext)) return false
        val runId = route.runId ?: return false
        val originDate = route.runDate ?: return false
        if (route.isPreview || route.statusLabel.startsWith("STALE")) return false
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val channel = NotificationChannel(CHANNEL_ID, "Active journey", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Quiet status updates for a journey you chose to follow."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false

        val openApp = PendingIntent.getActivity(
            appContext, NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val timeLabel = if (route.statusLabel.startsWith("PREDICTED")) "ETA" else "Scheduled arrival"
        val status = "$timeLabel ${route.arrival} · ${route.statusLabel}"
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("${route.trainNumber} toward ${route.destinationCode}")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText("${route.displayName} · $status"))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter(10 * 60_000L)
            .addExtras(Bundle().apply { putString(KEY_SCOPE, scope) })
            .build()

        return try {
            val previousRunId = preferences.getString(KEY_RUN_ID, null)
            manager.notify(NOTIFICATION_ID, notification)
            preferences.edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_TRAIN, route.trainNumber)
                .putString(KEY_DATE, originDate)
                .apply { if (previousRunId != runId) remove(KEY_LAST_PUSH_OBSERVED) }
                .apply()
            StatusPushWork.scheduleExpiryCheck(appContext)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Data-only FCM messages may refresh only the card the traveller started. */
    fun applyPush(data: Map<String, String>): Boolean {
        if (StatusPushWork.deleting(appContext)) return false
        val active = activeRun() ?: return false
        if (data["runId"] != active.runId) return false
        val now = System.currentTimeMillis()
        val observedAt = data["observedAt"]?.toLongOrNull() ?: return false
        if (observedAt < now - 10 * 60_000L || observedAt > now + 60_000L ||
            observedAt <= preferences.getLong(KEY_LAST_PUSH_OBSERVED, 0L)) return false
        if (data["event"] == "end") {
            cancel()
            return true
        }
        if (data["event"] != "update" || data["trainNumber"] != active.trainNumber ||
            data["originDate"] != active.originDate) return false
        val nextStation = data["nextStation"]?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return false
        val eta = data["eta"]?.takeIf { Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$").matches(it) } ?: return false
        val delayLabel = data["delayLabel"]?.takeIf { it.isNotBlank() && it.length <= 100 } ?: return false
        val expiresAt = data["expiresAt"]?.toLongOrNull() ?: return false
        if (expiresAt <= now || expiresAt > observedAt + 10 * 60_000L || !manager.areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("${active.trainNumber} toward $nextStation")
            .setContentText("ETA $eta · $delayLabel")
            .setStyle(Notification.BigTextStyle().bigText("Next $nextStation · ETA $eta · $delayLabel"))
            .setContentIntent(PendingIntent.getActivity(appContext, NOTIFICATION_ID,
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setTimeoutAfter((expiresAt - now).coerceAtMost(10 * 60_000L))
            .addExtras(Bundle().apply { putString(KEY_SCOPE, scope) })
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            preferences.edit().putLong(KEY_LAST_PUSH_OBSERVED, observedAt).apply()
            StatusPushWork.scheduleExpiryCheck(appContext)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
        clearStoredRun()
    }

    private fun clearStoredRun() {
        preferences.edit().remove(KEY_RUN_ID).remove(KEY_TRAIN).remove(KEY_DATE)
            .remove(KEY_LAST_PUSH_OBSERVED).apply()
    }

    private companion object {
        const val CHANNEL_ID = "active-journey"
        const val NOTIFICATION_ID = 1001
        const val KEY_RUN_ID = "runId"
        const val KEY_TRAIN = "trainNumber"
        const val KEY_DATE = "originDate"
        const val KEY_SCOPE = "gatewayScope"
        const val KEY_LAST_PUSH_OBSERVED = "lastPushObservedAt"
    }
}
