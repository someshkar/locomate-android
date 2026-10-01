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
            .addExtras(Bundle().apply { putString(KEY_SCOPE, scope) })
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            preferences.edit()
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_TRAIN, route.trainNumber)
                .putString(KEY_DATE, originDate)
                .apply()
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
        preferences.edit().remove(KEY_RUN_ID).remove(KEY_TRAIN).remove(KEY_DATE).apply()
    }

    private companion object {
        const val CHANNEL_ID = "active-journey"
        const val NOTIFICATION_ID = 1001
        const val KEY_RUN_ID = "runId"
        const val KEY_TRAIN = "trainNumber"
        const val KEY_DATE = "originDate"
        const val KEY_SCOPE = "gatewayScope"
    }
}
