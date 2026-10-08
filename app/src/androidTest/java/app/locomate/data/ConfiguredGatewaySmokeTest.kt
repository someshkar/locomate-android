package app.locomate.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/** Opt-in real native transport smoke. Writes are limited to an explicitly named loopback fixture. */
class ConfiguredGatewaySmokeTest {
    @Test fun configuredGatewayUsesNativeAuthAndDatedContracts() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("gatewaySmokeUrl").orEmpty()
        assumeTrue("Set gatewaySmokeUrl to run the external native HTTP smoke", url.isNotBlank())
        val writes = arguments.getString("gatewaySmokeWrites") == "true"
        val authOnly = arguments.getString("gatewaySmokeAuthOnly") == "true"
        require(!authOnly || !writes)
        require(!writes || java.net.URI(url).host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("locomate.installation", Context.MODE_PRIVATE)
        val original = prefs.all
        var gateway: RailGateway? = null
        try {
            check(prefs.edit().clear().putString("id", UUID.randomUUID().toString()).commit())
            val nativeGateway = RailGateway(context, url).also { gateway = it }
            if (authOnly) {
                nativeGateway.exportPrivacyData()
                return@runBlocking
            }
            val gateway = nativeGateway
            val date = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
            assertTrue(gateway.search("12137").any { it.number == "12137" })
            val route = gateway.journey("12137", date)
            assertEquals("12137", route.trainNumber)
            assertEquals(date, route.runDate)
            assertFalse(route.isPreview)
            assertTrue(route.geometry.size >= 2)
            gateway.operationalChain("12137", date)
            gateway.physicalChain("12137", date)
            assertTrue(gateway.searchStations("NDLS").any { it.code == "NDLS" })
            gateway.stationTrains("NDLS")
            gateway.trainsBetween("NDLS", "MMCT", date)
            if (writes) {
                val run = "12345:$date"
                val revision = System.currentTimeMillis() * 1_000
                gateway.registerAndroidStatus(run, "local-smoke-fid-no-delivery-123456789", revision)
                gateway.unregisterAndroidStatus(run, revision + 1)
                val stale = runCatching { gateway.registerAndroidStatus(run, "local-smoke-fid-no-delivery-123456789", revision) }.exceptionOrNull()
                assertEquals(409, (stale as? GatewayError)?.status)
                val now = System.currentTimeMillis()
                gateway.recordCommunityConsent(CommunityConsent.evidence(true))
                val point = route.geometry.first()
                val observations = listOf(now - 2_000, now - 1_000).map { timestamp -> CommunityObservation(
                    "12137:$date", timestamp, (point.latitude * 100_000).roundToInt(),
                    (point.longitude * 100_000).roundToInt(), 80.0, 30.0, 0.0, 0.0) }
                val (positionBatch, positionKey) = CommunityBatch.encode(observations)
                assertEquals("run:12137:$date", positionBatch.getJSONArray("observations").getJSONArray(0).getString(1))
                assertTrue(observations.all { it.runId == "12137:$date" })
                val acknowledged = gateway.uploadObservations(positionBatch, positionKey)
                assertEquals(observations.map { it.localId }.toSet(), acknowledged)
                assertEquals(acknowledged, gateway.uploadObservations(positionBatch, positionKey))
                val queue = PhysicalReportQueue(context, url)
                val report = queue.stage("12345", date, PhysicalSightingBatch(
                    listOf(PhysicalSighting(SightingKind.Coach, "123456", now, "AAA")), now), gateway.reportInstallationGeneration())
                val accepted = gateway.submitQueuedPhysicalReport(report)
                assertEquals(1, accepted.acceptedIds.size)
                assertEquals(accepted, gateway.submitQueuedPhysicalReport(report))
                queue.remove(report.key, report.installation)
                gateway.physicalChain("12345", date)
                val pending = queue.stage("12345", date, PhysicalSightingBatch(
                    listOf(PhysicalSighting(SightingKind.Locomotive, "30201", now, "AAA")), now), report.installation)
                gateway.recordCommunityConsent(CommunityConsent.evidence(false))
                val (withdrawnBatch, withdrawnKey) = CommunityBatch.encode(listOf(observations.first().copy(timestamp = now - 500)))
                val withdrawnPosition = runCatching { gateway.uploadObservations(withdrawnBatch, withdrawnKey) }.exceptionOrNull()
                assertEquals(403, (withdrawnPosition as? GatewayError)?.status)
                val rejected = runCatching { gateway.submitQueuedPhysicalReport(pending) }.exceptionOrNull()
                assertEquals(403, (rejected as? GatewayError)?.status)
                queue.clear()
                val exported = gateway.exportPrivacyData()
                assertTrue(exported.toString().contains("123456"))
            }
        } finally {
            // Cleanup must also run after a failed provider read; never leave a test installation behind.
            val cleanup = runCatching { gateway?.deletePrivacyData() }
            val editor = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            } }
            check(editor.commit())
            File(context.filesDir, "rail-run-cache/${railStorageScope(url)}").deleteRecursively()
            File(context.filesDir, "community/${railStorageScope(url)}").deleteRecursively()
            cleanup.getOrThrow()
        }
    }
}
