package app.locomate.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentJourneyStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val origin = "https://current-journey-test.example"
    private val reference = JourneyAlertLink("12951", "2026-10-01")

    @Test fun persistedReferenceRestartsPrivatelyAndSourceChangeClearsIt() {
        val store = CurrentJourneyStore(context, origin)
        try {
            store.select(reference)
            assertEquals(reference, CurrentJourneyStore(context, origin).read())
            assertNull(CurrentJourneyStore(context, "https://other-source.example").read())
            assertNull(CurrentJourneyStore(context, origin).read())
            store.select(reference)
            assertNull(CurrentJourneyStore(context, "").read())
            assertNull(CurrentJourneyStore(context, origin).read())
        } finally { store.clear() }
    }

    @Test fun referenceIsExportedAndDeletionClearsItWithoutRestoringConsent() = runBlocking {
        val store = CurrentJourneyStore(context, origin)
        try {
            store.select(reference)
            val privacy = PrivacyDataManager(context, RailGateway(context, ""))
            val export = privacy.prepareExport()
            val local = JSONObject(export.readText()).getJSONObject("local").getJSONObject("sharedPreferences")
                .getJSONObject(CurrentJourneyStore.PREFERENCE_NAME)
            assertEquals(reference.runId, local.getString("runId"))
            assertEquals(setOf("source", "runId"), local.keys().asSequence().toSet())
            assertTrue(privacy.deleteAll())
            assertNull(CurrentJourneyStore(context, origin).read())
            assertFalse(CommunityPreferences(context).enabled)
            assertNull(JourneyStatusNotification(context).activeRun())
        } finally { store.clear(); StatusPushWork.finishPrivacyDeletion(context) }
    }

    @Test fun permissionDraftKeepsChosenEventsButRejectsAnotherSourceOrNotice() {
        val draft = NotificationPermissionDraft(reference, setOf(JourneyAlertChannel.Delay, JourneyAlertChannel.Arrival),
            JourneyAlertQuietHours("22:00", "07:00"))
        val encoded = draft.encode(origin)
        assertEquals(draft, NotificationPermissionDraft.decode(encoded, origin, alerts = true))
        assertNull(NotificationPermissionDraft.decode(encoded, "https://other-source.example", alerts = true))
        assertNull(NotificationPermissionDraft.decode(encoded, origin, alerts = false))
        assertNull(NotificationPermissionDraft.decode(JSONObject(encoded).put("noticeHash", "old").toString(), origin, true))
        val status = NotificationPermissionDraft(reference)
        assertEquals(status, NotificationPermissionDraft.decode(status.encode(origin), origin, alerts = false))
    }
}
