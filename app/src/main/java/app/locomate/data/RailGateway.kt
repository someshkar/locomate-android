package app.locomate.data

import android.content.Context
import android.util.AtomicFile
import app.locomate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class TrainSearchResult(
    val number: String,
    val name: String,
    val originCode: String,
    val originName: String,
    val destinationCode: String,
    val destinationName: String,
    val live: Boolean,
)

data class NetworkBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun normalized(): NetworkBounds {
        val result = NetworkBounds(west.coerceIn(-180.0, 180.0), south.coerceIn(-90.0, 90.0),
            east.coerceIn(-180.0, 180.0), north.coerceIn(-90.0, 90.0))
        require(result.west < result.east && result.south < result.north) { "Invalid map bounds" }
        return result
    }
}

data class NetworkTrain(
    val runId: String,
    val trainNumber: String,
    val name: String,
    val originDate: String,
    val coordinate: RailPoint,
    val observedAt: String,
    val positionKind: String,
    val source: String,
    val delayMinutes: Int?,
)

data class NetworkSnapshot(val trains: List<NetworkTrain>, val generatedAt: String, val freshUntil: String)

class GatewayError(message: String, val status: Int = 0, val code: String = "",
                   val currentRevision: Long? = null) : Exception(message)

data class JourneyAlertAcknowledgement(val revision: Long, val expiresAt: Long)

/** Gateway-only rail client. Provider credentials never enter the Android app. */
class RailGateway(context: Context, baseUrl: String = BuildConfig.RAIL_API_URL) {
    private val appContext = context.applicationContext
    internal val sourceUrl = baseUrl
    private val configuredValue = baseUrl.isNotBlank()
    private val base: String? = validateBase(baseUrl)
    private val prefs = appContext.getSharedPreferences("locomate.installation", Context.MODE_PRIVATE)
    private val cacheFolder = File(appContext.filesDir, "rail-run-cache/${railStorageScope(baseUrl)}").apply { mkdirs() }
    private val installationId: String by lazy {
        prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
    }
    private val sessionLock = Mutex()
    private var accessToken: String? = null
    private var expiresAt: Long = 0

    // A bad production URL remains an error; it must never activate preview.
    val configured: Boolean get() = configuredValue

    /** The gateway export is kept intact so new server fields are not dropped. */
    suspend fun exportPrivacyData(): JSONObject = authenticatedRequest("/v1/privacy/export", "GET", null)

    /** Returns only after the authenticated installation was deleted on the gateway. */
    suspend fun deletePrivacyData() {
        authenticatedRequest("/v1/privacy/installation", "DELETE", null)
    }

    suspend fun recordCommunityConsent(evidence: JSONObject) {
        val response = authenticatedRequest("/v1/privacy/consent", "POST", evidence)
        if (!response.optBoolean("recorded", false)) throw GatewayError("Community consent was not recorded.")
    }

    suspend fun uploadObservations(batch: JSONObject, idempotencyKey: String): Set<Long> {
        val response = authenticatedRequest("/v1/observations/batch", "POST", batch, idempotencyKey)
        val accepted = response.getJSONArray("acceptedRecordIds")
        return (0 until accepted.length()).map { accepted.getLong(it) }.toSet()
    }

    /** The FCM target is a Firebase Installation ID from the native SDK. */
    suspend fun registerAndroidStatus(runId: String, fcmTarget: String) {
        require(Regex("^[0-9]{4,6}:[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(runId)) { "Invalid run ID" }
        require(fcmTarget.length in 20..4096 && fcmTarget.all { it.code in 0x21..0x7e }) {
            "Invalid FCM target"
        }
        val response = authenticatedRequest("/v1/android-status/subscription", "POST",
            JSONObject().put("runId", runId).put("fcmTarget", fcmTarget))
        if (!response.optBoolean("stored", false)) throw GatewayError("Status delivery was not accepted.")
    }

    suspend fun unregisterAndroidStatus(runId: String) {
        require(Regex("^[0-9]{4,6}:[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(runId)) { "Invalid run ID" }
        authenticatedRequest("/v1/android-status/subscription/$runId", "DELETE", null)
    }

    suspend fun registerJourneyAlerts(subscription: JourneyAlertSubscription): JourneyAlertAcknowledgement {
        require(subscription.enabled && subscription.hasCurrentConsent && subscription.target != null)
        val quiet = subscription.quietHours?.let { JSONObject().put("start", it.start)
            .put("end", it.end).put("timeZone", it.timeZone) } ?: JSONObject.NULL
        val response = authenticatedRequest("/v1/journey-alerts/subscriptions", "POST",
            JSONObject().put("runId", subscription.runId).put("revision", subscription.revision)
                .put("consentVersion", subscription.consentVersion).put("noticeHash", subscription.noticeHash)
                .put("target", JSONObject().put("kind", "fcm").put("fid", subscription.target))
                .put("channels", org.json.JSONArray(subscription.channels.map { it.wire }))
                .put("quietHours", quiet))
        val revision = response.getLong("revision")
        val expires = response.getLong("expiresAt")
        if (!response.optBoolean("stored") || response.optString("runId") != subscription.runId ||
            revision != subscription.revision || expires <= System.currentTimeMillis()) {
            throw GatewayError("Journey alerts were not confirmed by the gateway.")
        }
        return JourneyAlertAcknowledgement(revision, expires)
    }

    suspend fun unregisterJourneyAlerts(runId: String, revision: Long) {
        require(JourneyAlertLink.fromRunId(runId) != null && revision > 0)
        authenticatedRequest("/v1/journey-alerts/subscriptions/$runId?revision=$revision", "DELETE", null)
    }

    suspend fun search(query: String): List<TrainSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val data = get("/v1/trains/search?q=$encoded")
        val trains = data.getJSONArray("trains")
        return (0 until trains.length()).map { i ->
            val train = trains.getJSONObject(i)
            TrainSearchResult(
                train.getString("number"), train.getString("name"),
                train.getString("originCode"), train.getString("originName"),
                train.getString("destinationCode"), train.getString("destinationName"),
                train.optBoolean("live", false),
            )
        }
    }

    suspend fun network(bounds: NetworkBounds): NetworkSnapshot {
        val b = bounds.normalized()
        val serialized = "${b.west},${b.south},${b.east},${b.north}"
        val data = get("/v1/network/trains?bounds=${URLEncoder.encode(serialized, "UTF-8")}")
        val trains = data.getJSONArray("trains")
        return NetworkSnapshot(
            trains = (0 until trains.length()).map { i ->
                val train = trains.getJSONObject(i)
                val point = train.getJSONObject("coordinate")
                NetworkTrain(
                    runId = train.getString("runId"),
                    trainNumber = train.getString("trainNumber"),
                    name = train.getString("name"),
                    originDate = train.getString("originDate"),
                    coordinate = RailPoint(point.getDouble("latitude"), point.getDouble("longitude")),
                    observedAt = train.getString("observedAt"),
                    positionKind = train.getString("positionKind"),
                    source = train.getString("source"),
                    delayMinutes = if (train.isNull("delayMinutes")) null else train.optInt("delayMinutes"),
                )
            },
            generatedAt = data.optString("generatedAt", ""),
            freshUntil = data.optString("freshUntil", ""),
        )
    }

    suspend fun trainReliability(number: String): TrainReliabilitySummary {
        require(Regex("^[0-9]{4,6}$").matches(number)) { "Invalid train number" }
        requireRequestAllowed(false)
        val requestedInstallation = installationId
        val payload = get("/v1/trains/$number/history?limit=1")
        currentCoroutineContext().ensureActive()
        requireRequestAllowed(false)
        // A reply from before a completed deletion cannot repopulate the new installation's card.
        if (appContext.getSharedPreferences("locomate.installation", Context.MODE_PRIVATE)
                .getString("id", null) != requestedInstallation) throw GatewayError("Installation changed while loading history.")
        return TrainReliabilitySummary.decode(payload, number)
    }

    suspend fun journey(number: String, originDate: String): RoutePreview {
        require(Regex("^[0-9]{4,6}$").matches(number)) { "Invalid train number" }
        LocalDate.parse(originDate)
        var cachedAt: Long? = null
        val root = try {
            get("/v1/runs/$number/$originDate").also { saveRun(number, originDate, it) }
        } catch (failure: Exception) {
            val cached = readRun(number, originDate) ?: throw failure
            cachedAt = cached.first
            cached.second
        }
        val journey = root.getJSONObject("journey")
        val stops = journey.getJSONArray("stops")
        val stopObjects = (0 until stops.length()).map(stops::getJSONObject)
        val scheduledTimes = RailClockSequence.resolve(originDate, stopObjects.map { stop ->
            stop.stringOrNull("scheduledArrival") to stop.stringOrNull("scheduledDeparture")
        })
        val routeArray = journey.optJSONArray("routeCoordinates")
        val geometry = if (routeArray == null) emptyList() else (0 until routeArray.length()).map { i ->
            val point = routeArray.getJSONObject(i)
            RailPoint(point.getDouble("latitude"), point.getDouble("longitude"))
        }
        val prediction = journey.getJSONObject("prediction")
        val provenance = journey.optJSONObject("provenance")
        val expected = prediction.stringOrNull("expectedTime")
        val lower = prediction.stringOrNull("lowerBound")
        val upper = prediction.stringOrNull("upperBound")
        val freshness = provenance?.stringOrNull("freshness") ?: "scheduled"
        val hasForecast = expected != null && prediction.stringOrNull("source") != "scheduled" && freshness != "scheduled"
        val scheduledEnd = scheduledTimes.lastOrNull()?.arrivalMillis ?: instantMillis(journey.getString("scheduledArrival"))
        val forecastEnd = if (hasForecast && !prediction.isNull("delayMinutes") && scheduledEnd != null)
            scheduledEnd + prediction.optInt("delayMinutes").toLong() * 60_000 else scheduledEnd
        val position = journey.optJSONObject("position")
        val positionDisplay = RailPositionEvidence.display(
            source = position?.stringOrNull("source"),
            freshness = freshness,
            observedAtMillis = position?.optLong("observedAt")?.takeIf { it > 0L },
            cached = cachedAt != null,
        )
        val freshObservation = positionDisplay == PositionDisplay.Observed
        val status = when {
            cachedAt != null -> "STALE · LAST KNOWN"
            freshness == "stale" -> "STALE · LAST KNOWN"
            hasForecast && freshObservation -> "PREDICTED · LIVE INPUT"
            hasForecast -> "PREDICTED · ${freshness.uppercase()} INPUT"
            freshObservation -> "OBSERVED · ETA UNAVAILABLE"
            else -> "SCHEDULED · NO LIVE ETA"
        }
        val provider = provenance?.stringOrNull("providerLabel") ?: "Rail gateway"
        val source = when {
            cachedAt != null -> {
                val minutes = ((System.currentTimeMillis() - cachedAt) / 60_000).coerceAtLeast(0)
                "Last saved $minutes min ago from $provider. Live refresh unavailable."
            }
            hasForecast -> "$provider · forecast updated from ${prediction.optString("source", "unknown")} data"
            freshObservation -> "$provider · position observed; ETA unavailable"
            else -> "$provider · timetable only; no current ETA"
        }
        return RoutePreview(
            trainNumber = journey.getString("trainNumber"),
            name = journey.getString("trainName"),
            originCode = journey.getString("originCode"),
            originName = journey.getString("originName"),
            destinationCode = journey.getString("destinationCode"),
            destinationName = journey.getString("destinationName"),
            departure = railTime(journey.getString("departureTime")),
            arrival = railTime(if (hasForecast) expected else journey.getString("scheduledArrival")),
            arrivalDay = if (forecastEnd != null) RailClockSequence.dayNumber(originDate, forecastEnd)
                else dayOffset(journey.getString("departureTime"), journey.getString("scheduledArrival")),
            geometry = geometry,
            calls = stopObjects.mapIndexed { i, stop ->
                val forecast = stop.optJSONObject("forecast")
                RouteStop(
                    code = stop.getString("code"),
                    name = stop.getString("name"),
                    scheduledArrival = stop.stringOrNull("scheduledArrival")?.let(::railTime),
                    scheduledDeparture = stop.stringOrNull("scheduledDeparture")?.let(::railTime),
                    state = stop.optString("state", "upcoming"),
                    actualArrival = stop.stringOrNull("actualArrival")?.let(::railTime),
                    actualDeparture = stop.stringOrNull("actualDeparture")?.let(::railTime),
                    delayMinutes = if (stop.isNull("delayMinutes")) null else stop.optInt("delayMinutes"),
                    forecastP10 = forecast?.stringOrNull("p10")?.let(::railTime),
                    forecastP50 = forecast?.stringOrNull("p50")?.let(::railTime),
                    forecastP90 = forecast?.stringOrNull("p90")?.let(::railTime),
                    forecastSource = forecast?.stringOrNull("source"),
                    fallbackReason = forecast?.stringOrNull("fallbackReason"),
                    platform = stop.stringOrNull("platform"),
                    distanceKm = if (stop.isNull("distanceKm")) null else stop.optDouble("distanceKm"),
                    scheduledArrivalMillis = scheduledTimes[i].arrivalMillis,
                    scheduledDepartureMillis = scheduledTimes[i].departureMillis,
                )
            },
            distanceKm = journey.optDouble("distanceKm", 0.0),
            durationMinutes = journey.optInt("scheduledDurationMinutes", 0),
            isPreview = false,
            receivedAtMillis = cachedAt ?: System.currentTimeMillis(),
            // The enriched response prefixes its ID with `run:` while the collector
            // and push subscriptions identify the same dated run without that prefix.
            runId = "$number:$originDate",
            runDate = originDate,
            statusLabel = status,
            sourceDetail = source,
            etaBand = if (hasForecast && lower != null && upper != null)
                "${if (cachedAt != null) "Last forecast · " else ""}P10 ${railTime(lower)} · P50 ${railTime(expected)} · P90 ${railTime(upper)}" else null,
            departureInstantMillis = scheduledTimes.firstOrNull()?.departureMillis ?: instantMillis(journey.getString("departureTime")),
            arrivalInstantMillis = scheduledEnd,
            positionProgress = if (positionDisplay != PositionDisplay.Hidden)
                position?.optDouble("progress")?.takeIf { it.isFinite() && it in 0.0..1.0 } else null,
            positionStatus = when (positionDisplay) {
                PositionDisplay.Stale -> "Last known position · stale"
                PositionDisplay.Observed -> "Observed position · $provider"
                PositionDisplay.Hidden -> null
            },
        )
    }

    private suspend fun get(path: String): JSONObject = authenticatedRequest(path, "GET", null)

    private suspend fun authenticatedRequest(path: String, method: String, body: JSONObject?,
                                             idempotencyKey: String? = null): JSONObject {
        val deletionRequest = path == "/v1/privacy/installation" && method == "DELETE"
        repeat(2) { attempt ->
            requireRequestAllowed(deletionRequest)
            val token = sessionLock.withLock {
                requireRequestAllowed(deletionRequest)
                if (accessToken == null || System.currentTimeMillis() + 30_000 >= expiresAt) {
                    val auth = rawRequest("/v1/auth/device-session", "POST", null,
                        JSONObject().put("installationId", installationId), deletionRequest = deletionRequest)
                    accessToken = auth.getString("accessToken")
                    expiresAt = System.currentTimeMillis() + (auth.optLong("expiresIn", 3600) * 1000)
                }
                accessToken ?: throw GatewayError("The rail service could not start a device session.")
            }
            try {
                return rawRequest(path, method, token, body, idempotencyKey, deletionRequest)
            } catch (error: GatewayError) {
                if (error.status != 401 || attempt == 1) throw error
                sessionLock.withLock { accessToken = null; expiresAt = 0 }
            }
        }
        throw GatewayError("The rail service could not authenticate this device.")
    }

    private fun requireRequestAllowed(deletionRequest: Boolean) {
        if (!deletionRequest && StatusPushWork.deleting(appContext)) {
            throw GatewayError("Data deletion is pending. Retry Delete my data in Settings to finish cleanup.",
                code = "deletion_pending")
        }
    }

    private suspend fun saveRun(number: String, date: String, payload: JSONObject) = withContext(Dispatchers.IO) {
        runCatching {
            val file = AtomicFile(File(cacheFolder, "$number-$date.json"))
            val output = file.startWrite()
            try {
                val data = JSONObject().put("storedAt", System.currentTimeMillis())
                    .put("payload", payload).toString().toByteArray(Charsets.UTF_8)
                output.write(data)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    }

    private suspend fun readRun(number: String, date: String): Pair<Long, JSONObject>? = withContext(Dispatchers.IO) {
        runCatching {
            val file = AtomicFile(File(cacheFolder, "$number-$date.json"))
            val saved = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            saved.getLong("storedAt") to saved.getJSONObject("payload")
        }.getOrNull()
    }

    private suspend fun rawRequest(path: String, method: String, token: String?, body: JSONObject?,
                                   idempotencyKey: String? = null, deletionRequest: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            requireRequestAllowed(deletionRequest)
            val endpoint = base ?: throw GatewayError("A rail gateway has not been configured.")
            val connection = (URI.create(endpoint + path).toURL().openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = 12_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "LocomateNative/1.0")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", idempotencyKey)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val error = runCatching { JSONObject(responseText).optJSONObject("error") }.getOrNull()
                    val code = error?.optString("code").orEmpty()
                    val message = if (code.startsWith("invalid_provider") || code.startsWith("provider_"))
                        "The rail feed is temporarily unavailable. Try again shortly."
                    else error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Rail service unavailable ($status)."
                    throw GatewayError(message, status, code,
                        error?.optLong("currentRevision")?.takeIf { it in 1..9_007_199_254_740_990L })
                }
                if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        fun indiaToday(): String = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()

        private fun validateBase(value: String): String? {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI.create(trimmed) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            val localDebug = BuildConfig.DEBUG && uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            return trimmed.takeIf { uri.scheme == "https" || localDebug }
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun railTime(value: String): String {
            if (!value.contains('T')) return value.take(5)
            return runCatching {
                DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"))
                    .format(Instant.parse(value))
            }.getOrDefault(value)
        }

        private fun instantMillis(value: String): Long? = runCatching {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }.getOrNull()

        private fun dayOffset(departure: String, arrival: String): Int = runCatching {
            val zone = ZoneId.of("Asia/Kolkata")
            val start = Instant.parse(departure).atZone(zone).toLocalDate()
            val end = Instant.parse(arrival).atZone(zone).toLocalDate()
            (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(1)
        }.getOrDefault(1)
    }
}
