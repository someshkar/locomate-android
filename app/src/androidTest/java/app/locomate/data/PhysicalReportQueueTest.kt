package app.locomate.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONObject
import java.security.MessageDigest

class PhysicalReportQueueTest {
    @Test fun exactOutboxSurvivesRecreationExpiresWithoutRenewalAndSeparatesOrigins() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "https://outbox-contract-one.example"
        val other = "https://outbox-contract-two.example"
        val generation = RailGateway(context, source).reportInstallationGeneration()
        val now = System.currentTimeMillis()
        val queue = PhysicalReportQueue(context, source)
        try {
            queue.clear()
            val record = queue.stage("12345", "2026-08-24", PhysicalSightingBatch(
                listOf(PhysicalSighting(SightingKind.Coach, "123456", now, "AAA")), now), generation)
            val restored = PhysicalReportQueue(context, source).pending().single()
            assertEquals(record, restored)
            assertEquals(record.bodyText, restored.bodyText)
            assertTrue(restored.hasCurrentConsent(now + 14 * 60_000))
            assertFalse(restored.hasCurrentConsent(now + 15 * 60_000 + 1))
            assertTrue(restored.canRenew(now + 16 * 60_000))
            assertFalse(restored.canRenew(now + 24 * 3_600_000 + 1))
            assertTrue(PhysicalReportQueue(context, other).pending().isEmpty())
            assertTrue(runCatching { queue.remove(record.key, "different-installation") }.isFailure)
            assertEquals(record, queue.pending().single())
            val renewed = queue.stage(record.trainNumber, record.date,
                PhysicalSightingBatch(record.sightings(), now + 16 * 60_000), generation, replacing = record.key)
            assertNotEquals(record.key, renewed.key)
            assertEquals(record.sightings(), renewed.sightings())
            assertEquals(renewed, queue.pending().single())
        } finally { queue.clear(); File(context.filesDir, "community/${railStorageScope(source)}").deleteRecursively() }
    }

    @Test fun persistedReportRejectsFractionalConsentTimesUnknownFieldsAndDuplicateNumbers() {
        val batch = PhysicalSightingBatch(listOf(PhysicalSighting(SightingKind.Coach, "123456", 1_000)), 2_000)
        fun record(mutation: (JSONObject) -> Unit): JSONObject {
            val body = batch.encode().first.also(mutation).toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return QueuedPhysicalReport("12345", "2026-08-24", "fixture-installation", body, "physical-$digest").encode()
        }
        assertTrue(runCatching { QueuedPhysicalReport.decode(record { it.getJSONObject("consent").put("consentedAt", 2_000.5) }) }.isFailure)
        assertTrue(runCatching { QueuedPhysicalReport.decode(record { it.getJSONArray("sightings").getJSONObject(0).put("observedAt", 1_000.5) }) }.isFailure)
        assertTrue(runCatching { QueuedPhysicalReport.decode(record { it.put("seat", "12A") }) }.isFailure)
        assertTrue(runCatching { QueuedPhysicalReport.decode(record { it.getJSONArray("sightings").put(it.getJSONArray("sightings").getJSONObject(0)) }) }.isFailure)
    }

    @Test fun withdrawingPendingReportsCannotBeUndoneByLateReceiptAndDeletionBlocksStaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = "https://outbox-contract-withdraw.example"
        val gateway = RailGateway(context, source)
        val queue = PhysicalReportQueue(context, source)
        val generation = gateway.reportInstallationGeneration()
        val now = System.currentTimeMillis()
        val batch = PhysicalSightingBatch(listOf(PhysicalSighting(SightingKind.Locomotive, "30201", now)), now)
        try {
            val record = queue.stage("12345", "2026-08-24", batch, generation)
            queue.clear()
            queue.remove(record.key, generation)
            assertFalse(queue.contains(record))
            assertTrue(queue.pending().isEmpty())
            assertFalse(File(context.filesDir, "community/${railStorageScope(source)}/physical-sightings.json").exists())
            PrivacyDeletionState.begin(context)
            assertTrue(runCatching { queue.stage("12345", "2026-08-24", batch, generation) }.isFailure)
            queue.clear()
            assertTrue(queue.pending().isEmpty())
        } finally {
            PrivacyDeletionState.finish(context)
            queue.clear(); File(context.filesDir, "community/${railStorageScope(source)}").deleteRecursively()
        }
    }
}
