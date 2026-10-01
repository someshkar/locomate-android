package app.locomate.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AtomicFile
import app.locomate.BuildConfig
import app.locomate.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ActiveCommunityRun(val runId: String, val route: List<RailPoint>, val stopAt: Long)

/** The route is stored privately because passing a full polyline in an Intent can exceed Binder limits. */
object CommunitySession {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "community/active.json"))

    @Synchronized fun save(context: Context, route: RoutePreview) {
        val stopAt = CommunityLocationFilter.windowEnd(route) ?: error("Run has no scheduled end")
        val runId = route.runId ?: error("Run has no ID")
        val payload = JSONObject().put("scope", railStorageScope(BuildConfig.RAIL_API_URL))
            .put("runId", runId).put("stopAt", stopAt)
            .put("route", JSONArray().also { array ->
                route.geometry.forEach { point ->
                    array.put(JSONArray().put(point.latitude).put(point.longitude))
                }
            })
        val file = file(context)
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(payload.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    @Synchronized fun read(context: Context): ActiveCommunityRun? = runCatching {
        val saved = JSONObject(file(context).openRead().bufferedReader().use { it.readText() })
        if (saved.getString("scope") != railStorageScope(BuildConfig.RAIL_API_URL)) return null
        val route = saved.getJSONArray("route")
        ActiveCommunityRun(saved.getString("runId"),
            (0 until route.length()).map { index ->
                val pair = route.getJSONArray(index)
                RailPoint(pair.getDouble(0), pair.getDouble(1))
            }, saved.getLong("stopAt"))
    }.getOrNull()

    @Synchronized fun clear(context: Context) { file(context).delete() }
}

/** User-initiated location foreground service; no location starts from a background launch. */
class CommunityLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private lateinit var preferences: CommunityPreferences
    private lateinit var queue: CommunityQueue
    private lateinit var gateway: RailGateway
    private lateinit var sync: CommunitySync
    private lateinit var uploads: CommunityUploadRetry
    @Volatile private var active: ActiveCommunityRun? = null
    private var expiry: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        preferences = CommunityPreferences(this)
        queue = CommunityQueue(this)
        gateway = RailGateway(this)
        sync = CommunitySync(queue, gateway, preferences)
        uploads = CommunityUploadRetry(scope, canUpload = {
            val run = active
            run != null && preferences.enabled && gateway.configured &&
                run.stopAt > System.currentTimeMillis() &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                CommunitySession.read(this)?.runId == run.runId
        }, upload = { sync.flushObservations() }, onInactive = { stopSelf() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val run = CommunitySession.read(this)
        if (!preferences.enabled || !gateway.configured || run == null ||
            run.route.size < 2 || run.stopAt <= System.currentTimeMillis() ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        active = run
        createChannel()
        try {
            startForeground(NOTIFICATION_ID, notification(run.runId), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { locationManager.removeUpdates(this) }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 50f,
                this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalArgumentException) {
            stopSelf()
            return START_NOT_STICKY
        }
        expiry?.let(handler::removeCallbacks)
        expiry = Runnable { stopSelf() }.also { handler.postDelayed(it,
            (run.stopAt - System.currentTimeMillis()).coerceAtLeast(0)) }
        scope.launch { runCatching { sync.flushWithdrawals() } }
        uploads.start()
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val run = active ?: return
        if (!preferences.enabled || run.stopAt <= System.currentTimeMillis()) {
            stopSelf()
            return
        }
        val observation = CommunityLocationFilter.compact(run.runId, run.route, location) ?: return
        scope.launch {
            try {
                if (CommunitySession.read(this@CommunityLocationService)?.runId != run.runId) return@launch
                if (queue.appendIfAuthorized(observation, preferences)) {
                    uploads.request()
                }
            } catch (_: Exception) {
                // A failed private write never sends this fix. Later fixes can retry.
            }
        }
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        expiry?.let(handler::removeCallbacks)
        scope.cancel()
        active = null
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Community location contribution",
                NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(runId: String): Notification {
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Contributing train position")
            .setContentText("$runId · stop any time in Settings")
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "community-location"
        private const val NOTIFICATION_ID = 82
        private const val ACTION_STOP = "app.locomate.STOP_COMMUNITY_LOCATION"

        fun start(context: Context, route: RoutePreview) {
            require(CommunityLocationFilter.inRunWindow(route))
            CommunitySession.save(context, route)
            context.startForegroundService(Intent(context, CommunityLocationService::class.java))
        }

        fun stop(context: Context) {
            CommunitySession.clear(context)
            context.stopService(Intent(context, CommunityLocationService::class.java))
        }
    }
}
