package app.locomate.data

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Installation-scoped export and deletion for the native Android app. */
class PrivacyDataManager(context: Context, private val gateway: RailGateway) {
    private val appContext = context.applicationContext

    suspend fun prepareExport(): File = withContext(Dispatchers.IO) {
        val remote: Any = if (gateway.configured) gateway.exportPrivacyData() else JSONObject.NULL
        val preferences = JSONObject()
        for (name in preferenceNames()) {
            val values = JSONObject()
            for ((key, value) in appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                values.put(key, if (value is Set<*>) JSONArray(value.toList()) else JSONObject.wrap(value))
            }
            preferences.put(name, values)
        }
        val cache = filesBase64(File(appContext.filesDir, "rail-run-cache"))
        val community = filesBase64(File(appContext.filesDir, "community"))
        val archive = JSONObject()
            .put("schemaVersion", 1)
            .put("exportedAt", Instant.now().toString())
            .put("gateway", remote)
            .put("local", JSONObject()
                .put("sharedPreferences", preferences)
                .put("cachedDocumentsBase64", cache)
                .put("communityFilesBase64", community))
        val folder = File(appContext.cacheDir, "exports").apply { mkdirs() }
        File(folder, "locomate-data-${System.currentTimeMillis()}.json").apply {
            writeText(archive.toString(2), Charsets.UTF_8)
        }
    }

    /** A failed or partial deletion stays paused until the user retries and cleanup completes. */
    suspend fun deleteAll(): Boolean {
        CommunityLocationService.stop(appContext)
        withContext(Dispatchers.IO) { StatusPushWork.beginPrivacyDeletion(appContext) }
        if (gateway.configured) gateway.deletePrivacyData()
        return withContext(Dispatchers.IO) {
            JourneyStatusNotification(appContext).cancel()
            JourneyAlertsNotification(appContext).cancelAll()
            var complete = true
            if (!JourneyAlertStore(appContext).clear()) complete = false
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                complete = try {
                    Tasks.await(FirebaseInstallations.getInstance().delete(), 10, TimeUnit.SECONDS)
                    complete
                } catch (_: Exception) { false }
            }
            for (name in preferenceNames()) {
                if (!appContext.deleteSharedPreferences(name)) complete = false
            }
            for (folder in listOf(File(appContext.filesDir, "rail-run-cache"),
                File(appContext.filesDir, "community"),
                File(appContext.cacheDir, "exports"))) {
                if (folder.exists() && !folder.deleteRecursively()) complete = false
            }
            if (complete && !StatusPushWork.finishPrivacyDeletion(appContext)) complete = false
            complete
        }
    }

    private fun preferenceNames(): List<String> {
        val directory = File(appContext.applicationInfo.dataDir, "shared_prefs")
        return directory.listFiles().orEmpty().asSequence()
            .filter { it.name.startsWith("locomate.") && it.extension == "xml" }
            .map { it.name.removeSuffix(".xml") }.sorted().toList()
    }

    private fun filesBase64(root: File): JSONObject = JSONObject().also { result ->
        if (root.exists()) for (file in root.walkTopDown().filter(File::isFile)) {
            result.put(file.relativeTo(root).invariantSeparatorsPath,
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
    }
}
