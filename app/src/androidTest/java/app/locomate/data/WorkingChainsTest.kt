package app.locomate.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.MessageDigest
import app.locomate.BuildConfig

class WorkingChainsTest {
    @Test fun physicalUnavailableFixtureNeverInventsIdentityAndRejectsAnotherDatedRun() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = instrumentation.context.assets.open("contracts/rail-api/v1/physical-chain-unavailable.json")
            .bufferedReader().use { it.readText() }
        val chain = PhysicalChain.decode(JSONObject(text), "12345", "2026-08-24")
        assertEquals("unavailable", chain.state)
        chain.assets.forEach {
            assertEquals("no-evidence", it.unavailableReason)
            assertTrue(it.runs.isEmpty())
            assertNull(it.evidence.source)
            assertNull(it.evidence.confidence)
            assertEquals("unavailable", it.inbound.state)
            assertEquals("unknown", it.inbound.evidence.freshness)
        }
        assertTrue(runCatching { PhysicalChain.decode(JSONObject(text), "54321", "2026-08-24") }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(JSONObject(text), "12345", "2026-08-25") }.isFailure)
    }

    @Test fun publicNumberSubmissionHasExactConsentAndStableRetryIdentity() {
        val report = PhysicalSighting(SightingKind.Coach, "123456", 1_000, "NDLS")
        val batch = PhysicalSightingBatch(listOf(report), 2_000, "contract-report-id")
        val (body, key) = batch.encode()
        assertEquals(key, batch.encode().second)
        assertEquals(setOf("sightings", "consent"), body.keys().asSequence().toSet())
        val wire = body.getJSONArray("sightings").getJSONObject(0)
        assertEquals(setOf("assetKind", "identifier", "observedAt", "evidenceMethod", "stationCode"), wire.keys().asSequence().toSet())
        assertEquals("onboard-coach-plate", wire.getString("evidenceMethod"))
        assertEquals("123456", wire.getString("identifier"))
        assertEquals("2", body.getJSONObject("consent").getString("consentVersion"))
        val hash = MessageDigest.getInstance("SHA-256").digest(CommunityConsent.NOTICE.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(CommunityConsent.NOTICE_HASH, hash)
        for (private in listOf("latitude", "longitude", "gps", "photo", "pnr", "seat", "notes"))
            assertFalse(body.toString().contains(private))
        assertTrue(runCatching { PhysicalSightingBatch(listOf(report, report), 2_000).encode() }.isFailure)
    }

    @Test fun statusRevisionsRemainGloballyMonotonicAcrossRunSwitchesRestartsAndClockRollback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "locomate.status-subscriptions.${railStorageScope(BuildConfig.RAIL_API_URL)}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val old = prefs.all
        try {
            prefs.edit().clear().commit()
            val first = StatusSubscriptionStore(context) { 1_000 }.stage("12345:2026-08-24", true, "target-one")
            val same = StatusSubscriptionStore(context) { 1_000 }.stage(first.runId, true, "target-one")
            assertEquals(first.revision, same.revision)
            val stop = StatusSubscriptionStore(context) { 900 }.stage(first.runId, false)
            val second = StatusSubscriptionStore(context) { 800 }.stage("54321:2026-08-24", true, "target-two")
            assertTrue(stop.revision > first.revision)
            assertTrue(second.revision > stop.revision)
            assertFalse(StatusSubscriptionStore(context).load(first.runId)!!.enabled)
            val reenabled = StatusSubscriptionStore(context) { 700 }.stage(first.runId, true, "target-one")
            assertTrue(reenabled.revision > second.revision)
            val reconciled = StatusSubscriptionStore(context) { 600 }.stage(first.runId, true, "target-one", reenabled.revision + 50)
            assertEquals(reenabled.revision + 51, reconciled.revision)
            PrivacyDeletionState.begin(context)
            // No registration or cancellation staging may occur through the durable deletion gate.
            assertTrue(StatusPushWork.deleting(context))
            assertTrue(runCatching { StatusSubscriptionStore(context).stage(first.runId, true, "late") }.isFailure)
        } finally {
            PrivacyDeletionState.finish(context)
            val editor = prefs.edit().clear()
            old.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
            } }
            editor.commit()
        }
    }
}
