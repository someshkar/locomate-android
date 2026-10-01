package app.locomate.data

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyStatusNotificationTest {
    @Test
    fun deletionGateIgnoresLateFcmRegistration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val card = JourneyStatusNotification(context)
        val preferences = context.getSharedPreferences(StatusPushWork.preferenceName, Context.MODE_PRIVATE)
        val route = PreviewRoutes.load(context).first().copy(
            isPreview = false, runId = "12951:2026-10-01", runDate = "2026-10-01",
            statusLabel = "PREDICTED · LIVE INPUT",
        )
        card.cancel()
        preferences.edit().clear().commit()
        try {
            assertTrue(card.show(route))
            StatusPushWork.beginPrivacyDeletion(context)
            StatusPushWork.registered(context, "a".repeat(32))
            assertNull(preferences.getString("target", null))
        } finally {
            card.cancel()
            StatusPushWork.finishPrivacyDeletion(context)
        }
    }

    @Test
    fun pushUpdatesOnlyTheEnabledRunAndEndRemovesItsCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val card = JourneyStatusNotification(context)
        val route = PreviewRoutes.load(context).first().copy(
            isPreview = false, runId = "12951:2026-10-01", runDate = "2026-10-01",
            statusLabel = "PREDICTED · LIVE INPUT",
        )
        val runId = requireNotNull(route.runId)
        val originDate = requireNotNull(route.runDate)
        card.cancel()
        try {
            assertTrue(card.show(route))
            val now = System.currentTimeMillis()
            val update = mapOf(
                "event" to "update", "runId" to runId,
                "trainNumber" to route.trainNumber, "originDate" to originDate,
                "nextStation" to "Kota Junction", "eta" to "14:40",
                "delayLabel" to "+10 MIN · EST.", "observedAt" to (now - 1_000).toString(),
                "expiresAt" to (now + 9 * 60_000).toString(),
            )
            assertFalse(card.applyPush(update + ("runId" to "other:2026-10-01")))
            assertFalse(card.applyPush(update + ("observedAt" to (now - 11 * 60_000).toString())))
            assertTrue(card.applyPush(update))
            val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(active.any { it.notification.extras.getString(Notification.EXTRA_TITLE)?.contains("Kota Junction") == true })
            assertFalse(card.applyPush(update))
            assertTrue(card.applyPush(mapOf("event" to "end", "runId" to runId,
                "observedAt" to now.toString())))
            assertNull(card.activeRun())
        } finally {
            card.cancel()
        }
    }
}
