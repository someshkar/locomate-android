package app.locomate.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.locomate.BuildConfig
import app.locomate.MainActivity
import app.locomate.R

/** Ordinary alerts have their own channel and never change the quiet status card. */
class JourneyAlertsNotification(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val store = JourneyAlertStore(appContext, baseUrl)
    private val scope = railStorageScope(baseUrl)

    fun available(): Boolean {
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Journey alerts",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Station, delay, platform, departure and arrival alerts for journeys you choose."
        })
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun applyPush(data: Map<String, String>): Boolean {
        if (StatusPushWork.deleting(appContext) || !available()) return false
        return runCatching { store.deliver(data) { payload ->
            if (StatusPushWork.deleting(appContext)) return@deliver false
            val intent = Intent(appContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                this.data = Uri.parse(payload.link.url)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val open = PendingIntent.getActivity(appContext, payload.eventId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(payload.title).setContentText(payload.body)
                .setStyle(Notification.BigTextStyle().bigText(payload.body))
                .setContentIntent(open).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_EVENT)
                .setTimeoutAfter((payload.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
                .build()
            manager.notify(tag(payload.link.runId), payload.eventId.hashCode(), notification)
            true
        } }.getOrDefault(false)
    }

    fun cancel(runId: String) {
        manager.activeNotifications.filter { it.tag == tag(runId) }.forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelDisabled() {
        val active = store.all().filter { it.active() }.map { tag(it.runId) }.toSet()
        manager.activeNotifications.filter { it.tag?.startsWith("journey-alert:") == true && it.tag !in active }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun cancelAll() {
        manager.activeNotifications.filter { it.tag?.startsWith("journey-alert:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun tag(runId: String) = "journey-alert:$scope:$runId"
    companion object { const val CHANNEL_ID = "journey-alerts" }
}
