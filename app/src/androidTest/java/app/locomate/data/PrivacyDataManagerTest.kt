package app.locomate.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivacyDataManagerTest {
    @Test fun deletionLatchSurvivesPreferenceCleanupAndBlocksNewNetworkSessions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            StatusPushWork.beginPrivacyDeletion(context)
            context.deleteSharedPreferences(StatusPushWork.preferenceName)
            assertTrue(StatusPushWork.deleting(context))
            val gateway = RailGateway(context, "https://must-not-contact.example")
            val failure = runCatching { gateway.search("12951") }.exceptionOrNull()
            assertTrue(failure is GatewayError)
            assertEquals("deletion_pending", (failure as GatewayError).code)
            assertTrue(PrivacyDeletionState.pending(context))
            assertTrue(StatusPushWork.finishPrivacyDeletion(context))
            assertFalse(StatusPushWork.deleting(context))
        } finally { StatusPushWork.finishPrivacyDeletion(context) }
    }

    @Test
    fun previewExportIncludesPrivateDataAndCanBeSharedThroughFileProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "locomate.privacy-export-test"
        val cache = File(context.filesDir, "rail-run-cache/privacy-export-test/journey.json")
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("saved", "journey").commit()
        cache.parentFile!!.mkdirs()
        cache.writeText("cached route")
        val alertOrigin = "https://privacy-alert-test.example"
        val alertStore = JourneyAlertStore(context, alertOrigin)
        alertStore.clear()
        alertStore.enable(PreviewRoutes.load(context).first().copy(isPreview = false,
            runId = "12951:2026-10-01", runDate = "2026-10-01", statusLabel = "OBSERVED",
            departureInstantMillis = System.currentTimeMillis() - 60_000,
            arrivalInstantMillis = System.currentTimeMillis() + 3_600_000),
            JourneyAlertChannel.entries.toSet(), null)
        try {
            val export = PrivacyDataManager(context, RailGateway(context, baseUrl = "")).prepareExport()
            val json = JSONObject(export.readText())
            assertEquals(JSONObject.NULL, json.get("gateway"))
            assertEquals("journey", json.getJSONObject("local").getJSONObject("sharedPreferences")
                .getJSONObject(name).getString("saved"))
            assertTrue(json.getJSONObject("local").getJSONObject("cachedDocumentsBase64")
                .has("privacy-export-test/journey.json"))
            assertTrue(json.getJSONObject("local").getJSONObject("sharedPreferences")
                .getJSONObject("locomate.journey-alerts.${railStorageScope(alertOrigin)}")
                .getString("subscriptions").contains("12951:2026-10-01"))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", export)
            assertEquals("content", uri.scheme)
            export.delete()
            Unit
        } finally {
            context.deleteSharedPreferences(name)
            cache.delete()
            cache.parentFile?.delete()
            alertStore.clear()
        }
    }
}
