package app.locomate.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketException

/** Real HTTP regressions for native response identity and cache replacement. */
class RailGatewayContractTest {
    @Test fun journeyRejectsResponseForDifferentTrain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            server.mode = "wrong"
            val gateway = RailGateway(context, server.url)
            val result = runCatching { gateway.journey("12951", "2026-10-08") }
            assertTrue("Expected wrong-train payload rejection; got train=${result.getOrNull()?.trainNumber}, runId=${result.getOrNull()?.runId}, date=${result.getOrNull()?.runDate}", result.isFailure)
        }
    }
    @Test fun malformedRefreshDoesNotDestroyPreviouslyValidOfflineCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            val gateway = RailGateway(context, server.url)
            assertEquals("12951", gateway.journey("12951", "2026-10-08").trainNumber)
            server.mode = "malformed"
            val stale = gateway.journey("12951", "2026-10-08")
            assertTrue(stale.statusLabel.startsWith("STALE"))
            server.mode = "offline"
            val restored = gateway.journey("12951", "2026-10-08")
            assertEquals("12951", restored.trainNumber)
            assertTrue(restored.statusLabel.startsWith("STALE"))
        }
    }
    @Test fun versionedBackendContractCorpusHasMatchingHashesAndOutcomes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val folder = "contracts/rail-api/v1/"
        val manifest = JSONObject(assets.open(folder + "manifest.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        val gateway = RailGateway(instrumentation.targetContext)
        assertEquals(1, manifest.getInt("schemaVersion"))
        assertTrue("At least the seven identity/numeric cases must be present", cases.length() >= 7)
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val bytes = assets.open(folder + case.getString("file")).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(case.getString("file"), case.getString("sha256"), digest)
            val request = case.getJSONObject("request")
            val result = runCatching { gateway.decodeJourney(JSONObject(String(bytes)),
                request.getString("trainNumber"), request.getString("originDate")) }
            assertEquals(case.getString("file"), case.getBoolean("accepted"), result.isSuccess)
            if (case.getString("file") == "journey-fractional.json") {
                val route = result.getOrThrow()
                assertEquals(10.5, route.calls[1].delayMinutes!!, 0.0)
                assertEquals(10.5, route.forecastDelayMinutes!!, 0.0)
                assertEquals(60.25, route.forecastLeadMinutes!!, 0.0)
                assertEquals(30.75, route.forecastAgeSeconds!!, 0.0)
                assertEquals(4_830_000L, route.scheduledDurationMillis)
                assertEquals(4_830_000L, SavedJourney.from(route).scheduledDurationMillis)
                assertEquals("1h 20m 30s", PassportMetrics.from(listOf(SavedJourney.from(route))).scheduledDurationLabel)
            }
            if (case.getString("file") == "journey-unknown-delay.json")
                assertNull(result.getOrThrow().forecastDelayMinutes)
        }
    }

    @Test fun nativeStatusTransportKeepsShortRunIdentityAndExactMutationRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            val gateway = RailGateway(context, server.url)
            server.responseBody = """{"stored":true,"runId":"12951:2026-10-08","revision":987654}"""
            gateway.registerAndroidStatus("12951:2026-10-08", "a".repeat(32), 987654)
            assertEquals("/v1/android-status/subscription", server.lastPath)
            assertEquals("12951:2026-10-08", JSONObject(server.lastBody).getString("runId"))
            assertEquals(987654L, JSONObject(server.lastBody).getLong("revision"))
            server.responseBody = "{}"
            gateway.unregisterAndroidStatus("12951:2026-10-08", 987655)
            assertEquals("/v1/android-status/subscription/12951:2026-10-08?revision=987655", server.lastPath)
        }
    }

    @Test fun physicalReadsAndProposedSubmissionUseTheDatedGatewayContract() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        AuditGateway().use { server ->
            val gateway = RailGateway(instrumentation.targetContext, server.url)
            server.responseBody = instrumentation.context.assets.open("contracts/rail-api/v1/physical-chain-unavailable.json")
                .bufferedReader().use { it.readText() }
            assertEquals("unavailable", gateway.physicalChain("12345", "2026-08-24").state)
            assertEquals("/v1/runs/12345/2026-08-24/physical-chain", server.lastPath)
            assertTrue(runCatching { gateway.physicalChain("54321", "2026-08-24") }.isFailure)
            val now = System.currentTimeMillis()
            val batch = PhysicalSightingBatch(listOf(PhysicalSighting(SightingKind.Locomotive, "30201", now)), now)
            server.responseBody = """{"acceptedIds":["physical-sighting:fixture"],"evidenceState":{"locomotive":"proposed","rake":"proposed"},"message":"Proposed evidence recorded"}"""
            val result = gateway.submitPhysicalSightings("12345", "2026-08-24", batch)
            assertEquals("proposed", result.locomotiveState)
            assertEquals("/v1/runs/12345/2026-08-24/physical-sightings", server.lastPath)
            assertEquals(batch.encode().second, server.lastHeaders["idempotency-key"])
            assertEquals(batch.encode().first.toString(), server.lastBody)
        }
    }

    @Test fun oldCacheWithWrongIdentityCannotBeDisplayedOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            val cache = File(context.filesDir, "rail-run-cache/${railStorageScope(server.url)}/12951-2026-10-08.json")
            try {
                cache.parentFile!!.mkdirs()
                cache.writeText(JSONObject().put("storedAt", System.currentTimeMillis())
                    .put("payload", JSONObject("""{"journey":{"id":"run:12137:2026-10-08","trainNumber":"12137","travelDate":"2026-10-08"}}""")).toString())
                server.mode = "offline"
                assertTrue(runCatching { RailGateway(context, server.url).journey("12951", "2026-10-08") }.isFailure)
            } finally { cache.parentFile!!.deleteRecursively() }
        }
    }

    @Test fun canceledLateReplyCannotCreateCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            server.releaseRun = CountDownLatch(1)
            val gateway = RailGateway(context, server.url)
            val job = launch(Dispatchers.Default) { gateway.journey("12951", "2026-10-08") }
            assertTrue(server.runRequested.await(3, TimeUnit.SECONDS))
            job.cancel()
            server.releaseRun!!.countDown()
            job.join()
            assertTrue(job.isCancelled)
            val cache = File(context.filesDir, "rail-run-cache/${railStorageScope(server.url)}")
            assertFalse("Cancellation must not persist a response", cache.exists())
        }
    }

    @Test fun deletionMarkerRejectsLateReplyAndCacheWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AuditGateway().use { server ->
            server.releaseRun = CountDownLatch(1)
            val gateway = RailGateway(context, server.url)
            var failure: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try { gateway.journey("12951", "2026-10-08") } catch (error: Throwable) { failure = error }
            }
            try {
                assertTrue(server.runRequested.await(3, TimeUnit.SECONDS))
                PrivacyDeletionState.begin(context)
                server.releaseRun!!.countDown()
                job.join()
                assertEquals("deletion_pending", (failure as GatewayError).code)
                assertFalse(File(context.filesDir, "rail-run-cache/${railStorageScope(server.url)}").exists())
            } finally {
                server.releaseRun!!.countDown()
                job.join()
                PrivacyDeletionState.finish(context)
            }
        }
    }

    @Test fun installationGenerationRejectsReplyAfterCompletedDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("locomate.installation", android.content.Context.MODE_PRIVATE)
        AuditGateway().use { server ->
            server.releaseRun = CountDownLatch(1)
            val gateway = RailGateway(context, server.url)
            var failure: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try { gateway.journey("12951", "2026-10-08") } catch (error: Throwable) { failure = error }
            }
            assertTrue(server.runRequested.await(3, TimeUnit.SECONDS))
            val oldId = prefs.getString("id", null)
            try {
                context.deleteSharedPreferences("locomate.installation")
                context.getSharedPreferences("locomate.installation", android.content.Context.MODE_PRIVATE)
                    .edit().putString("id", "contract-test-new-installation").commit()
                server.releaseRun!!.countDown()
                job.join()
                assertEquals("installation_changed", (failure as GatewayError).code)
                assertFalse(File(context.filesDir, "rail-run-cache/${railStorageScope(server.url)}").exists())
            } finally {
                context.getSharedPreferences("locomate.installation", android.content.Context.MODE_PRIVATE)
                    .edit().putString("id", oldId).commit()
            }
        }
    }

}

private class AuditGateway : AutoCloseable {
    private val socket = ServerSocket(0)
    val url = "http://127.0.0.1:${socket.localPort}"
    @Volatile var mode = "good"
    @Volatile var responseBody: String? = null
    @Volatile var lastPath = ""
    @Volatile var lastBody = ""
    @Volatile var lastHeaders: Map<String, String> = emptyMap()
    val runRequested = CountDownLatch(1)
    @Volatile var releaseRun: CountDownLatch? = null
    private val worker = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 3000
                    val input = connection.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    var length = 0
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(body, offset, length - offset)
                        if (read < 0) break
                        offset += read
                    }
                    lastPath = path; lastBody = String(body); lastHeaders = headers
                    if (path.startsWith("/v1/runs/")) {
                        runRequested.countDown()
                        releaseRun?.await(5, TimeUnit.SECONDS)
                    }
                    val status = if (mode == "offline" && path.startsWith("/v1/runs/")) "503 Unavailable" else "200 OK"
                    val payload = when {
                        path == "/v1/auth/device-session" -> """{"accessToken":"audit-only","expiresIn":3600}"""
                        responseBody != null -> requireNotNull(responseBody)
                        mode == "offline" -> """{"error":{"message":"offline audit fixture"}}"""
                        mode == "malformed" -> """{"journey":{"trainNumber":"12951"}}"""
                        else -> """{"journey":{"id":"run:12951:2026-10-08","travelDate":"2026-10-08","trainNumber":"${if (mode == "wrong") "12137" else "12951"}","trainName":"Audit Express","originCode":"AAA","originName":"Origin","destinationCode":"BBB","destinationName":"Destination","departureTime":"12:00","scheduledArrival":"15:00","scheduledDurationMinutes":180,"distanceKm":120,"routeCoordinates":[{"latitude":19,"longitude":73},{"latitude":28,"longitude":77}],"stops":[{"code":"AAA","name":"Origin","scheduledDeparture":"12:00"},{"code":"BBB","name":"Destination","scheduledArrival":"15:00"}],"prediction":{"source":"scheduled"},"provenance":{"freshness":"scheduled","providerLabel":"Audit fixture"}}}"""
                    }.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload); flush()
                    }
                }
            } catch (_: SocketException) { }
        }
    }, "audit-gateway-fixture").apply { isDaemon = true; start() }
    override fun close() {
        releaseRun?.countDown()
        socket.close(); worker.join(1000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "rail-run-cache/${railStorageScope(url)}").deleteRecursively()
    }
}
