package app.locomate.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivacyDataManagerTest {
    @Test
    fun previewExportIncludesPrivateDataAndCanBeSharedThroughFileProvider() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "locomate.privacy-export-test"
        val cache = File(context.filesDir, "rail-run-cache/privacy-export-test/journey.json")
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("saved", "journey").commit()
        cache.parentFile!!.mkdirs()
        cache.writeText("cached route")
        try {
            val export = PrivacyDataManager(context, RailGateway(context, baseUrl = "")).prepareExport()
            val json = JSONObject(export.readText())
            assertEquals(JSONObject.NULL, json.get("gateway"))
            assertEquals("journey", json.getJSONObject("local").getJSONObject("sharedPreferences")
                .getJSONObject(name).getString("saved"))
            assertTrue(json.getJSONObject("local").getJSONObject("cachedDocumentsBase64")
                .has("privacy-export-test/journey.json"))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", export)
            assertEquals("content", uri.scheme)
            export.delete()
            Unit
        } finally {
            context.deleteSharedPreferences(name)
            cache.delete()
            cache.parentFile?.delete()
        }
    }
}
