package app.locomate.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyAlertsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun route() = PreviewRoutes.load(context).first().copy(isPreview = false,
        runId = "12951:2026-10-01", trainNumber = "12951", runDate = "2026-10-01",
        statusLabel = "OBSERVED", departureInstantMillis = System.currentTimeMillis() - 60_000,
        arrivalInstantMillis = System.currentTimeMillis() + 3_600_000)

    @Test fun desiredMutationsSurviveRestartAndIgnoreSupersededAcknowledgements() {
        val one = "https://alert-store-test.example"
        val two = "https://alert-other-test.example"
        val store = JourneyAlertStore(context, one)
        try {
            store.clear()
            store.enable(route(), JourneyAlertChannel.entries.toSet(), null)
            val enabled = store.all().single()
            assertEquals(enabled, JourneyAlertStore(context, one).all().single())
            assertTrue(JourneyAlertStore(context, two).all().isEmpty())
            store.updateTarget("a".repeat(32))
            val registered = store.all().single()
            assertTrue(registered.revision > enabled.revision)
            store.acknowledge(enabled.runId, enabled.revision)
            assertTrue(store.all().single().pending)
            store.acknowledge(registered.runId, registered.revision, registered.expiresAt - 60_000)
            assertFalse(store.all().single().pending)
            store.disable(enabled.runId)
            val disabled = JourneyAlertStore(context, one).all().single()
            assertFalse(disabled.enabled)
            assertTrue(disabled.pending)
            store.acknowledge(registered.runId, registered.revision)
            store.resolveConflict(registered.runId, registered.revision, disabled.revision + 50)
            assertEquals(disabled, store.all().single())
            store.resolveConflict(disabled.runId, disabled.revision, disabled.revision + 50)
            assertFalse(store.all().single().enabled)
            assertTrue(store.all().single().revision > disabled.revision + 50)
        } finally { store.clear(); JourneyAlertStore(context, two).clear() }
    }

    @Test fun notificationIsOrdinaryDeduplicatedAndStopsOnWithdrawal() {
        grantNotificationPermissionIfNeeded(context)
        val origin = "https://alert-notification-test.example"
        val store = JourneyAlertStore(context, origin)
        val notifications = JourneyAlertsNotification(context, origin)
        try {
            store.clear()
            store.enable(route(), JourneyAlertChannel.entries.toSet(), null)
            val selected = store.all().single()
            val now = System.currentTimeMillis()
            val payload = mapOf("type" to "journey-alert", "version" to "1", "eventId" to "alert:test",
                "runId" to selected.runId, "revision" to selected.revision.toString(), "channel" to "delay",
                "trainNumber" to "12951", "serviceDate" to "2026-10-01", "observedAt" to now.toString(),
                "expiresAt" to (now + 60_000).toString(), "title" to "Delay changed", "body" to "Expected 5 minutes later",
                "deepLink" to "locomate://journeys/12951?date=2026-10-01")
            assertTrue(notifications.applyPush(payload))
            assertFalse(notifications.applyPush(payload))
            val visible = context.getSystemService(NotificationManager::class.java).activeNotifications
                .first { it.notification.channelId == JourneyAlertsNotification.CHANNEL_ID }
            assertNotNull(visible.notification.contentIntent)
            assertFalse(visible.isOngoing)
            store.disable(selected.runId)
            notifications.cancelDisabled()
            assertFalse(notifications.applyPush(payload + ("eventId" to "alert:after-withdrawal")))
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
                .none { it.notification.channelId == JourneyAlertsNotification.CHANNEL_ID })
        } finally { notifications.cancelAll(); store.clear() }
    }

    @Test fun permissionWithdrawalPersistsRemovalAndRestoringPermissionDoesNotReenable() {
        val store = JourneyAlertStore(context, "https://alert-permission-test.example")
        try {
            store.clear()
            store.enable(route(), JourneyAlertChannel.entries.toSet(), null)
            val enabled = store.all().single()
            JourneyAlertsWork.reconcilePermissions(store, false)
            val removed = store.all().single()
            assertFalse(removed.enabled)
            assertTrue(removed.pending)
            assertTrue(removed.revision > enabled.revision)
            JourneyAlertsWork.reconcilePermissions(store, true)
            assertEquals(removed, store.all().single())
        } finally { store.clear() }
    }

    @Test fun corruptPreferencesArePreservedInsteadOfBeingOverwritten() {
        val origin = "https://alert-corruption-test.example"
        val preferences = context.getSharedPreferences("locomate.journey-alerts.${railStorageScope(origin)}", Context.MODE_PRIVATE)
        val store = JourneyAlertStore(context, origin)
        try {
            preferences.edit().putString("subscriptions", "not-json").commit()
            assertTrue(runCatching { store.all() }.isFailure)
            assertTrue(runCatching { store.enable(route(), JourneyAlertChannel.entries.toSet(), null) }.isFailure)
            assertEquals("not-json", preferences.getString("subscriptions", null))
        } finally { store.clear() }
    }

    @Test fun alertConsumerSurvivesStatusCardRemovalAndPrivacyGateRejectsLateRegistration() {
        val store = JourneyAlertStore(context)
        val card = JourneyStatusNotification(context)
        val prefs = context.getSharedPreferences(StatusPushWork.preferenceName, Context.MODE_PRIVATE)
        try {
            store.clear()
            card.cancel()
            prefs.edit().clear().commit()
            store.enable(route(), JourneyAlertChannel.entries.toSet(), null)
            assertTrue(StatusPushWork.hasConsumers(context))
            StatusPushWork.registered(context, "a".repeat(32))
            StatusPushWork.unregister(context, "12951:2026-10-01")
            assertEquals("a".repeat(32), StatusPushWork.target(context))
            StatusPushWork.beginPrivacyDeletion(context)
            StatusPushWork.registered(context, "b".repeat(32))
            assertEquals("a".repeat(32), StatusPushWork.target(context))
            store.expire(Long.MAX_VALUE)
            assertFalse(StatusPushWork.hasConsumers(context))
            assertTrue(store.all().single().pending)
        } finally {
            store.clear()
            StatusPushWork.finishPrivacyDeletion(context)
            JourneyAlertsWork.scheduleExpiry(context)
        }
    }
}
