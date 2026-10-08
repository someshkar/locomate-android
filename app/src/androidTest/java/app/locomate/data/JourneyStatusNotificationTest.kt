package app.locomate.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyStatusNotificationTest {
    @Test
    fun deletionGateIgnoresLateFcmRegistration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        val card = JourneyStatusNotification(context)
        val preferences = context.getSharedPreferences(StatusPushWork.preferenceName, Context.MODE_PRIVATE)
        val route = productionRoute(context)
        card.cancel()
        preferences.edit().clear().commit()
        try {
            assertTrue(card.enable(route))
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
        grantNotificationPermissionIfNeeded(context)
        val card = JourneyStatusNotification(context)
        val route = productionRoute(context)
        val runId = requireNotNull(route.runId)
        val originDate = requireNotNull(route.runDate)
        card.cancel()
        try {
            assertTrue(card.enable(route))
            assertNotificationLink(context, route)
            // Android may rate-limit an update posted within a second of the initial card.
            Thread.sleep(1_200)
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
            assertNotificationLink(context, route)
            val active = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertTrue(active.any { it.notification.extras.getString(Notification.EXTRA_TITLE)?.contains("Kota Junction") == true })
            assertFalse(card.applyPush(update))
            assertTrue(card.applyPush(mapOf("event" to "end", "runId" to runId,
                "observedAt" to now.toString())))
            assertNull(card.activeRun())
            val restored = JourneyStatusNotification(context)
            assertFalse(restored.refresh(route))
            assertFalse(restored.applyPush(update + ("observedAt" to (now + 1).toString())))
            assertNull(restored.activeRun())
        } finally {
            card.cancel()
        }
    }

    @Test
    fun refreshCannotChangeRunsOrRestartACanceledCard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        val route = productionRoute(context)
        val card = JourneyStatusNotification(context)
        card.cancel()
        try {
            assertFalse(card.refresh(route))
            assertTrue(card.enable(route))
            val otherInstance = JourneyStatusNotification(context)
            assertEquals(route.runId, otherInstance.activeRun()?.runId)
            assertTrue(otherInstance.refresh(route.copy(arrival = "14:45")))
            assertFalse(otherInstance.refresh(route.copy(runId = "12951:2026-10-02", runDate = "2026-10-02")))
            assertEquals(route.runId, card.activeRun()?.runId)

            otherInstance.cancel()
            assertFalse(card.refresh(route))
            assertNull(card.activeRun())
            // The user can still explicitly opt in again after stopping the card.
            assertTrue(card.enable(route))
            assertEquals(route.runId, card.activeRun()?.runId)
        } finally {
            card.cancel()
        }
    }

    @Test
    fun expiredStoredRunCannotBeRefreshedEvenBeforeAndroidRemovesItsNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        var now = System.currentTimeMillis()
        val route = productionRoute(context)
        val card = JourneyStatusNotification(context) { now }
        val manager = context.getSystemService(NotificationManager::class.java)
        card.cancel()
        try {
            assertTrue(card.enable(route))
            awaitNotification(manager)
            now += 10 * 60_000L + 1
            val restored = JourneyStatusNotification(context) { now }
            assertFalse(restored.refresh(route))
            assertNull(restored.activeRun())
            awaitNoNotification(manager)
            assertFalse(restored.applyPush(mapOf(
                "event" to "update", "runId" to requireNotNull(route.runId),
                "trainNumber" to route.trainNumber, "originDate" to requireNotNull(route.runDate),
                "nextStation" to "Kota Junction", "eta" to "14:40", "delayLabel" to "+10 MIN",
                "observedAt" to now.toString(), "expiresAt" to (now + 60_000).toString(),
            )))
        } finally {
            card.cancel()
        }
    }

    @Test
    fun refreshRejectsANotificationFromAnotherGatewayScopeOrRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        val route = productionRoute(context)
        val card = JourneyStatusNotification(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        card.cancel()
        try {
            listOf("gatewayScope" to "other-gateway", "runId" to "12951:2026-10-02").forEach { (key, value) ->
                assertTrue(card.enable(route))
                val active = awaitNotification(manager)
                val foreign = Notification.Builder(context, active.notification.channelId)
                    .setSmallIcon(active.notification.smallIcon)
                    .setContentTitle("Foreign journey")
                    .setExtras(android.os.Bundle(active.notification.extras).apply { putString(key, value) })
                    .build()
                manager.notify(1001, foreign)
                assertEquals(value, awaitNotification(manager) {
                    it.notification.extras.getString(key) == value
                }.notification.extras.getString(key))
                assertFalse(JourneyStatusNotification(context).refresh(route))
                assertNull(card.activeRun())
                awaitNoNotification(manager)
            }
        } finally {
            card.cancel()
        }
    }

    @Test
    fun retainedRouteAgeLimitsEnablementAndRefreshTimeout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        var now = System.currentTimeMillis()
        val route = productionRoute(context).copy(receivedAtMillis = now)
        val card = JourneyStatusNotification(context) { now }
        val manager = context.getSystemService(NotificationManager::class.java)
        card.cancel()
        try {
            assertFalse(card.enable(route.copy(receivedAtMillis = null)))
            assertFalse(card.enable(route.copy(receivedAtMillis = now + 60_001)))
            assertTrue(card.enable(route))
            // The fake clock advances nine minutes instantly, but Android may drop notification
            // updates posted within one real second of the initial card.
            Thread.sleep(1_200)
            now += 9 * 60_000L
            assertTrue(card.refresh(route))
            assertEquals(60_000L, awaitNotification(manager) {
                it.notification.timeoutAfter == 60_000L
            }.notification.timeoutAfter)

            now += 60_000L
            assertFalse(card.enable(route))
            assertFalse(card.refresh(route))
            assertNull(card.activeRun())
            assertTrue(card.enable(route.copy(receivedAtMillis = now)))
            // Even if another fresh snapshot has kept the card active, this retained one is too old.
            assertFalse(card.refresh(route))
        } finally {
            card.cancel()
        }
    }

    @Test
    fun statusCardTapKeepsTheExactServiceDateWhenAnotherRunIsEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        grantNotificationPermissionIfNeeded(context)
        val route = productionRoute(context)
        val card = JourneyStatusNotification(context)
        card.cancel()
        try {
            assertTrue(card.enable(route))
            assertNotificationLink(context, route)
            val nextDay = route.copy(runId = "12951:2026-10-02", runDate = "2026-10-02")
            assertTrue(card.enable(nextDay))
            assertNotificationLink(context, nextDay)
        } finally {
            card.cancel()
        }
    }

    private fun assertNotificationLink(context: Context, route: RoutePreview) {
        val expectedIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("locomate://journeys/${route.trainNumber}?date=${route.runDate}")
        }
        // PendingIntent identity includes the action, component, and complete dated data URI.
        val expected = PendingIntent.getActivity(context, 1001, expectedIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        assertNotNull(expected)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(expected, awaitNotification(manager) {
            it.notification.contentIntent == expected
        }.notification.contentIntent)
    }

    private fun awaitNotification(manager: NotificationManager,
                                  matches: (StatusBarNotification) -> Boolean = { true }): StatusBarNotification {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        do {
            manager.activeNotifications.firstOrNull { it.id == 1001 && it.tag == null && matches(it) }
                ?.let { return it }
            Thread.sleep(20)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Expected status notification was not published within 10 seconds")
    }

    private fun awaitNoNotification(manager: NotificationManager) {
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        while (manager.activeNotifications.any { it.id == 1001 && it.tag == null } &&
            android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
        assertTrue(manager.activeNotifications.none { it.id == 1001 && it.tag == null })
    }

    private fun productionRoute(context: Context) = PreviewRoutes.load(context).first().copy(
        trainNumber = "12951", isPreview = false, runId = "12951:2026-10-01", runDate = "2026-10-01",
        statusLabel = "PREDICTED · LIVE INPUT",
        receivedAtMillis = System.currentTimeMillis(),
    )
}
