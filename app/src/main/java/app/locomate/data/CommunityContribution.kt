package app.locomate.data

import android.content.Context
import android.location.Location
import android.util.AtomicFile
import app.locomate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object CommunityConsent {
    const val VERSION = 2
    const val PURPOSE = "community_observations"
    const val NOTICE_HASH = "85e622e058a604e15d9dd794416785a09245e085f69c97381e7cc47a600f4f25"
    const val NOTICE = "SmartRail privacy notice v2: optional community observations include onboard positions and user-entered physical locomotive or coach-plate numbers; no photos, GPS, PNR, coach/seat, or notes are collected with physical ID reports, and consent can be withdrawn at any time."

    fun evidence(granted: Boolean): JSONObject = JSONObject()
        .put("evidenceId", UUID.randomUUID().toString())
        .put("purpose", PURPOSE)
        .put("decision", if (granted) "granted" else "withdrawn")
        .put("consentVersion", VERSION.toString())
        .put("noticeHash", NOTICE_HASH)
        .put("recordedAt", System.currentTimeMillis())
}

/** Consent is bound to one gateway origin and never migrates from an older notice. */
class CommunityPreferences(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "locomate.community.${railStorageScope(baseUrl)}", Context.MODE_PRIVATE)

    init {
        if (prefs.getInt("version", 0) != CommunityConsent.VERSION) {
            check(prefs.edit().putInt("version", CommunityConsent.VERSION)
                .putBoolean("enabled", false).putBoolean("background", false).commit())
        }
    }

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val background: Boolean get() = enabled && prefs.getBoolean("background", false)

    fun grant() {
        check(prefs.edit().putBoolean("enabled", true).commit())
    }

    fun setBackground(value: Boolean) {
        check(prefs.edit().putBoolean("background", enabled && value).commit())
    }

    fun revoke() {
        check(prefs.edit().putBoolean("enabled", false).putBoolean("background", false).commit())
    }
}

data class CommunityObservation(
    val runId: String, val timestamp: Long, val latE5: Int, val lonE5: Int,
    val speedKph: Double, val accuracyM: Double, val routeProgress: Double,
    val matchDistanceM: Double, val consentVersion: Int = CommunityConsent.VERSION,
) {
    val localId: Long get() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$runId:$timestamp".toByteArray(Charsets.UTF_8))
        return digest.take(6).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    fun toJson(): JSONObject = JSONObject().put("runId", runId).put("timestamp", timestamp)
        .put("latE5", latE5).put("lonE5", lonE5).put("speedKph", speedKph)
        .put("accuracyM", accuracyM).put("routeProgress", routeProgress)
        .put("matchDistanceM", matchDistanceM).put("consentVersion", consentVersion)

    companion object {
        fun fromJson(value: JSONObject): CommunityObservation = CommunityObservation(
            value.getString("runId"), value.getLong("timestamp"), value.getInt("latE5"),
            value.getInt("lonE5"), value.getDouble("speedKph"), value.getDouble("accuracyM"),
            value.getDouble("routeProgress"), value.getDouble("matchDistanceM"),
            value.getInt("consentVersion"))
    }
}

/** Matches the SmartRail filter and route projection before any fix enters the queue. */
object CommunityLocationFilter {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun inRunWindow(route: RoutePreview, now: Long = System.currentTimeMillis()): Boolean {
        if (route.isPreview || route.statusLabel.startsWith("STALE") || route.geometry.size < 2) return false
        val departure = route.departureInstantMillis ?: return false
        val arrival = route.arrivalInstantMillis
            ?: route.scheduledDurationMillis?.let { departure + it }
            ?: return false
        return route.runId != null && route.runDate != null && arrival > departure &&
            now >= departure - 6 * 3_600_000L && now <= arrival + 24 * 3_600_000L
    }

    fun windowEnd(route: RoutePreview): Long? {
        val departure = route.departureInstantMillis ?: return null
        val arrival = route.arrivalInstantMillis
            ?: route.scheduledDurationMillis?.let { departure + it }
            ?: return null
        return (arrival + 24 * 3_600_000L).takeIf { arrival > departure }
    }

    fun compact(runId: String, route: List<RailPoint>, latitude: Double, longitude: Double,
                timestamp: Long, accuracyM: Double, speedMps: Double, mocked: Boolean,
                now: Long = System.currentTimeMillis()): CommunityObservation? {
        if (runId.isBlank() || Regex("^(preview|demo)(?:$|[-:/_])", RegexOption.IGNORE_CASE).containsMatchIn(runId) ||
            mocked || route.size < 2 || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            timestamp > now + 5_000 || now - timestamp > 30_000 ||
            !accuracyM.isFinite() || accuracyM !in 0.0..100.0) return null
        val speedKph = speedMps * 3.6
        if (!speedKph.isFinite() || speedKph !in 3.0..350.0) return null
        val lengths = route.zipWithNext { a, b -> distance(a.latitude, a.longitude, b.latitude, b.longitude) }
        val total = lengths.sum()
        if (total <= 0) return null
        var traversed = 0.0
        var bestDistance = Double.POSITIVE_INFINITY
        var bestPoint: RailPoint? = null
        var bestProgress = 0.0
        for (index in lengths.indices) {
            val start = route[index]
            val end = route[index + 1]
            val scale = cos(Math.toRadians((start.latitude + end.latitude + latitude) / 3))
            val dx = (end.longitude - start.longitude) * scale
            val dy = end.latitude - start.latitude
            val fraction = if (dx * dx + dy * dy == 0.0) 0.0 else
                (((longitude - start.longitude) * scale * dx + (latitude - start.latitude) * dy) /
                    (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val projected = RailPoint(start.latitude + dy * fraction,
                start.longitude + (end.longitude - start.longitude) * fraction)
            val meters = distance(latitude, longitude, projected.latitude, projected.longitude)
            if (meters < bestDistance) {
                bestDistance = meters
                bestPoint = projected
                bestProgress = (traversed + lengths[index] * fraction) / total
            }
            traversed += lengths[index]
        }
        if (bestDistance >= max(25.0, accuracyM) * 3) return null
        val point = bestPoint ?: return null
        return CommunityObservation(runId, timestamp,
            (point.latitude * 100_000).roundToInt(), (point.longitude * 100_000).roundToInt(),
            (speedKph * 10).roundToInt() / 10.0, (accuracyM * 10).roundToInt() / 10.0,
            (bestProgress * 1_000_000).roundToInt() / 1_000_000.0,
            (bestDistance * 10).roundToInt() / 10.0)
    }

    fun compact(runId: String, route: List<RailPoint>, fix: Location): CommunityObservation? =
        compact(runId, route, fix.latitude, fix.longitude, fix.time,
            if (fix.hasAccuracy()) fix.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            if (fix.hasSpeed()) fix.speed.toDouble() else -1.0, fix.isMock)

    private fun distance(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val latDelta = Math.toRadians(latB - latA)
        val lonDelta = Math.toRadians(lonB - lonA)
        val arc = sin(latDelta / 2) * sin(latDelta / 2) +
            cos(Math.toRadians(latA)) * cos(Math.toRadians(latB)) * sin(lonDelta / 2) * sin(lonDelta / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(arc)))
    }
}

/** Atomic, private, source-scoped files are excluded from Android backup by the app manifest. */
class CommunityQueue(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val root = File(context.applicationContext.filesDir, "community/${railStorageScope(baseUrl)}")
    private val observations = AtomicFile(File(root, "observations.json"))
    private val withdrawals = AtomicFile(File(root, "withdrawals.json"))

    @Synchronized fun pendingObservations(): List<CommunityObservation> = read(observations).let { array ->
        (0 until array.length()).map { CommunityObservation.fromJson(array.getJSONObject(it)) }
    }

    @Synchronized fun append(observation: CommunityObservation) {
        val retained = pendingObservations()
        if (retained.any { it.runId == observation.runId && it.timestamp == observation.timestamp }) return
        write(observations, JSONArray().also { array ->
            (retained + observation).forEach { array.put(it.toJson()) }
        })
    }

    @Synchronized fun appendIfAuthorized(observation: CommunityObservation,
                                          preferences: CommunityPreferences): Boolean {
        if (!preferences.enabled) return false
        append(observation)
        return true
    }

    @Synchronized fun removeObservations(ids: Set<Long>) {
        write(observations, JSONArray().also { array ->
            pendingObservations().filterNot { it.localId in ids }.forEach { array.put(it.toJson()) }
        })
    }

    @Synchronized fun clearObservations() { write(observations, JSONArray()) }

    @Synchronized fun pendingWithdrawals(): List<JSONObject> = read(withdrawals).let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Synchronized fun addWithdrawal(evidence: JSONObject) {
        write(withdrawals, JSONArray().also { array ->
            pendingWithdrawals().forEach(array::put)
            array.put(evidence)
        })
    }

    @Synchronized fun removeWithdrawal(evidenceId: String) {
        write(withdrawals, JSONArray().also { array ->
            pendingWithdrawals().filterNot { it.getString("evidenceId") == evidenceId }.forEach(array::put)
        })
    }

    private fun read(file: AtomicFile): JSONArray {
        if (!file.baseFile.exists()) return JSONArray()
        return JSONArray(file.openRead().bufferedReader().use { it.readText() })
    }

    private fun write(file: AtomicFile, array: JSONArray) {
        root.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

/** Exact v1 tuple payload; only acknowledged IDs leave the local queue. */
object CommunityBatch {
    fun encode(items: List<CommunityObservation>): Pair<JSONObject, String> {
        require(items.isNotEmpty() && items.size <= 100)
        val sorted = items.sortedWith(compareBy(CommunityObservation::timestamp, CommunityObservation::runId))
        require(sorted.map { it.localId }.distinct().size == sorted.size)
        val first = sorted.first()
        var previous = first
        val tuples = JSONArray()
        sorted.forEachIndexed { index, item ->
            require(item.consentVersion == CommunityConsent.VERSION)
            val tuple = JSONArray().put(item.localId).put(canonicalObservationRunId(item.runId)).put(item.consentVersion)
                .put(if (index == 0) 0 else item.timestamp - previous.timestamp)
                .put(if (index == 0) 0 else item.latE5 - previous.latE5)
                .put(if (index == 0) 0 else item.lonE5 - previous.lonE5)
                .put((item.speedKph * 10).roundToInt())
                .put((item.accuracyM * 10).roundToInt())
                .put((item.routeProgress * 1_000_000).roundToInt())
                .put((item.matchDistanceM * 10).roundToInt())
            tuples.put(tuple)
            previous = item
        }
        val payload = JSONObject().put("version", 1)
            .put("base", JSONArray().put(first.timestamp).put(first.latE5).put(first.lonE5))
            .put("observations", tuples)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sorted.joinToString(",") { it.localId.toString() }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return payload to "observations-$digest"
    }
}

class CommunitySync(private val queue: CommunityQueue, private val gateway: RailGateway,
                    private val preferences: CommunityPreferences) {
    private val withdrawalLock = Mutex()
    suspend fun flushObservations(): Int = withContext(Dispatchers.IO) {
        if (!preferences.enabled || !gateway.configured) return@withContext 0
        val now = System.currentTimeMillis()
        val pending = queue.pendingObservations()
        val discarded = pending.filter { it.timestamp < now - 9 * 60_000 ||
            it.timestamp > now + 60_000 || it.consentVersion != CommunityConsent.VERSION ||
            it.runId.startsWith("preview", true) || it.runId.startsWith("demo", true) }
        if (discarded.isNotEmpty()) queue.removeObservations(discarded.map { it.localId }.toSet())
        val batch = pending.filterNot { it in discarded }.take(100)
        if (batch.isEmpty()) return@withContext 0
        uploadEligible(batch)
    }

    private suspend fun uploadEligible(batch: List<CommunityObservation>): Int {
        if (batch.isEmpty() || !preferences.enabled) return 0
        val (payload, key) = CommunityBatch.encode(batch)
        return try {
            val accepted = gateway.uploadObservations(payload, key)
            if (preferences.enabled) queue.removeObservations(accepted)
            accepted.size
        } catch (error: GatewayError) {
            val invalid = error.code.startsWith("invalid_") ||
                error.code == "impossible_observation_speed"
            if (!invalid) throw error
            if (batch.size == 1) {
                queue.removeObservations(setOf(batch.first().localId))
                return 0
            }
            val middle = (batch.size + 1) / 2
            uploadEligible(batch.take(middle)) + uploadEligible(batch.drop(middle))
        }
    }

    suspend fun flushWithdrawals() = withdrawalLock.withLock { withContext(Dispatchers.IO) {
        if (!gateway.configured) return@withContext
        for (evidence in queue.pendingWithdrawals()) {
            gateway.recordCommunityConsent(evidence)
            queue.removeWithdrawal(evidence.getString("evidenceId"))
        }
    } }
}
