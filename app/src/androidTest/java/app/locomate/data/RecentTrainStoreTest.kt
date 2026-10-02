package app.locomate.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecentTrainStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun train(number: String, name: String = "Complete Train Name") = TrainSearchResult(number, name,
        "NDLS", "New Delhi", "MMCT", "Mumbai Central", live = true,
        sourceLabel = "Historical railway snapshot", distanceKm = 1384.0)
    private fun origin() = "https://${UUID.randomUUID()}.example"
    private fun cleanup(origin: String) = context.deleteSharedPreferences("locomate.recent-trains.${railStorageScope(origin)}")

    @Test fun selectionIsDurableBoundedDeduplicatedAndContainsNoDatedStatus() {
        val source = origin()
        try {
            val store = RecentTrainStore(context, source)
            for (number in 12000..12011) assertTrue(store.record(train(number.toString())))
            assertTrue(store.record(train("12003", "Updated full name")))
            val reopened = RecentTrainStore(context, source).load()
            assertEquals(10, reopened.size)
            assertEquals("12003", reopened.first().number)
            assertEquals("Updated full name", reopened.first().name)
            assertEquals("New Delhi", reopened.first().originName)
            assertEquals("Historical railway snapshot", reopened.first().sourceLabel)
            assertTrue(reopened.all { !it.live })
            val encoded = context.getSharedPreferences("locomate.recent-trains.${railStorageScope(source)}", Context.MODE_PRIVATE)
                .getString("trains", "")!!
            assertFalse(encoded.contains("originDate"))
            assertFalse(encoded.contains("\"live\""))
            assertFalse(store.record(train("bad")))
            assertTrue(store.clear())
            assertTrue(RecentTrainStore(context, source).load().isEmpty())
        } finally { cleanup(source) }
    }

    @Test fun gatewayScopesStaySeparateAndParticipateInPrivateExport() = runBlocking {
        val source = origin()
        val other = origin()
        try {
            assertTrue(RecentTrainStore(context, source).record(train("01234")))
            assertTrue(RecentTrainStore(context, other).load().isEmpty())
            val export = PrivacyDataManager(context, RailGateway(context, "")).prepareExport()
            try {
                val values = JSONObject(export.readText()).getJSONObject("local").getJSONObject("sharedPreferences")
                    .getJSONObject("locomate.recent-trains.${railStorageScope(source)}")
                assertTrue(values.getString("trains").contains("01234"))
            } finally { export.delete() }
        } finally { cleanup(source); cleanup(other) }
    }

    @Test fun privacyErasureRemovesHistoryAndAnOldStoreCannotRecreateIt() = runBlocking {
        val source = origin()
        try {
            val store = RecentTrainStore(context, source)
            assertTrue(store.record(train("01234")))
            StatusPushWork.beginPrivacyDeletion(context)
            assertTrue(store.load().isEmpty())
            assertFalse(store.record(train("12951")))
            assertTrue(PrivacyDataManager(context, RailGateway(context, "")).deleteAll())
            assertFalse(PrivacyDeletionState.pending(context))
            assertTrue(store.load().isEmpty())
            assertFalse(store.record(train("12951")))
            assertTrue(RecentTrainStore(context, source).load().isEmpty())
        } finally { StatusPushWork.finishPrivacyDeletion(context); cleanup(source) }
    }
}
