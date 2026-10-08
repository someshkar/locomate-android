package app.locomate.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.MessageDigest
import app.locomate.BuildConfig

class WorkingChainsTest {
    @Test fun sharedEnrichedFixturesKeepDatedLinksAndSeparatePhysicalEvidenceFromInference() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun fixture(name: String) = JSONObject(assets.open("contracts/rail-api/v1/$name").bufferedReader().use { it.readText() })
        val operational = OperationalChain.decode(fixture("operational-working-enriched.json"), "12345", "2026-08-24")
        assertEquals("inferred", operational.mode)
        assertTrue(operational.disclaimer.contains("does not verify physical identity"))
        assertTrue(operational.linkage!!.contains("possible-same-rake"))
        assertEquals(listOf("2026-08-23", "2026-08-24", "2026-08-25"), operational.runs.map { it.second.date })
        val physical = PhysicalChain.decode(fixture("physical-chain-enriched.json"), "12345", "2026-08-24")
        val rake = physical.assets.first { it.kind == "rake" }
        assertEquals("available", rake.state)
        assertEquals("synthetic-test-operator", rake.evidence.source)
        assertEquals(0.94, rake.evidence.confidence!!, 0.0)
        assertEquals(listOf("2026-08-23", "2026-08-24", "2026-08-25"), rake.runs.map { it.second.date })
        assertEquals("confirmed", rake.inbound.state)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("rake").getJSONObject("current").put("runId", "run:54321:2026-08-24") },
            { it.getJSONObject("rake").getJSONObject("current").put("serviceDate", "2026-08-25") },
            { it.getJSONObject("rake").getJSONObject("current").put("status", "live") },
            { it.getJSONObject("rake").getJSONObject("current").put("scheduledEndAt", 1) },
            { it.getJSONObject("rake").getJSONObject("current").put("scheduledOutboundDepartureAt", -1) },
            { it.getJSONObject("rake").getJSONObject("current").getJSONObject("inboundTerminalArrival").put("predictedAt", -1) }
        )
        mutations.forEachIndexed { index, mutation -> assertTrue("Physical mutation $index must reject", runCatching {
            PhysicalChain.decode(fixture("physical-chain-enriched.json").also(mutation), "12345", "2026-08-24")
        }.isFailure) }
    }
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

    @Test fun physicalDecoderRejectsInvalidStatesConfidenceAndNoncanonicalCurrentIdentity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun fixture() = JSONObject(instrumentation.context.assets.open("contracts/rail-api/v1/physical-chain-unavailable.json")
            .bufferedReader().use { it.readText() })
        for (bad in listOf("live", "confirmed", "invented")) {
            assertTrue(runCatching { PhysicalChain.decode(fixture().put("state", bad), "12345", "2026-08-24") }.isFailure)
            assertTrue(runCatching { PhysicalChain.decode(fixture().apply { getJSONObject("rake").put("state", bad) }, "12345", "2026-08-24") }.isFailure)
        }
        for (confidence in listOf(-0.01, 1.01)) assertTrue(runCatching {
            PhysicalChain.decode(fixture().apply { getJSONObject("rake").getJSONObject("assignmentEvidence").put("confidence", confidence) }, "12345", "2026-08-24")
        }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(fixture().put("asOf", -1), "12345", "2026-08-24") }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(fixture().apply {
            getJSONObject("locomotive").getJSONObject("inboundLink").put("reason", "schedule-continuity")
        }, "12345", "2026-08-24") }.isFailure)
    }

    @Test fun operationalDecoderRejectsIdentityEnumsAndMissingCurrentRun() {
        fun fixture() = JSONObject("""{"trainNumber":"12345","originDate":"2026-08-24","availability":"unavailable","mode":"inferred","disclaimer":"Schedule continuity does not prove physical identity","updatedAt":"2026-08-24T00:00:00Z","previous":null,"current":null,"next":null,"linkage":null,"delayAssessment":null,"propagatedDelay":null,"turnaroundRisk":null}""")
        assertEquals("unavailable", OperationalChain.decode(fixture(), "12345", "2026-08-24").availability)
        assertTrue(runCatching { OperationalChain.decode(fixture(), "54321", "2026-08-24") }.isFailure)
        assertTrue(runCatching { OperationalChain.decode(fixture(), "12345", "2026-08-25") }.isFailure)
        assertTrue(runCatching { OperationalChain.decode(fixture().put("availability", "available"), "12345", "2026-08-24") }.isFailure)
        assertTrue(runCatching { OperationalChain.decode(fixture().put("mode", "official-physical"), "12345", "2026-08-24") }.isFailure)
    }

    @Test fun operationalDecoderValidatesEvidenceEnumsGeometryFractionsAndPropagatedIdentity() {
        fun fixture() = JSONObject("""{"trainNumber":"12345","originDate":"2026-08-24","availability":"available","mode":"inferred","disclaimer":"Schedule continuity is inference","updatedAt":"2026-08-24T00:00:00Z","previous":null,"next":null,
          "current":{"id":"run:12345:2026-08-24","role":"current","trainNumber":"12345","trainName":"Fixture","originCode":"AAA","destinationCode":"BBB","scheduledDeparture":"12:00","scheduledArrival":"14:00","geometry":{"coordinates":[{"latitude":19,"longitude":73}],"source":"inferred"},"position":{"coordinate":{"latitude":19,"longitude":73},"progress":0.5,"observedAt":"2026-08-24T12:00:00Z","source":"scheduled"}},
          "linkage":{"claim":"possible-same-rake","confidence":"low","method":"schedule-continuity","caveats":["No confirmed asset"]},
          "delayAssessment":{"incomingDelayMinutes":10.5,"confidence":"low","summary":"Estimate","evidence":[{"id":"event","kind":"arrival","summary":"Estimate","delayMinutes":10.5,"source":"predicted"}]},
          "propagatedDelay":{"fromRunId":"run:54321:2026-08-23","toRunId":"run:12345:2026-08-24","minutes":2.5,"explanation":"Estimate","evidenceIds":["event"]},
          "turnaroundRisk":{"level":"low","scheduledMinutes":60.25,"availableMinutes":49.75,"minimumMinutes":30.5,"summary":"Estimate"}}""")
        assertEquals("12345", OperationalChain.decode(fixture(), "12345", "2026-08-24").runs.single().second.trainNumber)
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("current").put("id", "12345:2026-08-24") },
            { it.getJSONObject("current").getJSONObject("position").put("progress", 1.1) },
            { it.getJSONObject("current").getJSONObject("geometry").getJSONArray("coordinates").getJSONObject(0).put("latitude", 90.1) },
            { it.getJSONObject("current").getJSONObject("geometry").put("source", "community") },
            { it.getJSONObject("linkage").put("confidence", "confirmed") },
            { it.getJSONObject("linkage").put("method", "physical-number") },
            { it.getJSONObject("delayAssessment").put("incomingDelayMinutes", "10.5") },
            { it.getJSONObject("delayAssessment").getJSONArray("evidence").getJSONObject(0).put("source", "inferred") },
            { it.getJSONObject("propagatedDelay").put("fromRunId", "54321:2026-08-23") },
            { it.getJSONObject("turnaroundRisk").put("level", "unknown") },
            { it.put("current", "malformed") }
        )
        mutations.forEachIndexed { index, mutation -> assertTrue("Mutation $index must be rejected", runCatching {
            OperationalChain.decode(fixture().also(mutation), "12345", "2026-08-24")
        }.isFailure) }
    }

    @Test fun physicalDecoderValidatesAllNullableTimesAndAssetReasons() {
        fun fixture() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("contracts/rail-api/v1/physical-chain-unavailable.json")
            .bufferedReader().use { it.readText() })
        for (key in listOf("recordedAt", "ageMs", "expiresAt")) assertTrue(runCatching {
            PhysicalChain.decode(fixture().apply { getJSONObject("rake").getJSONObject("assignmentEvidence").put(key, -0.5) }, "12345", "2026-08-24")
        }.isFailure)
        for (key in listOf("minimumServiceDurationMs", "serviceReadyAt")) assertTrue(runCatching {
            PhysicalChain.decode(fixture().apply { getJSONObject("rake").getJSONObject("inboundLink").put(key, -0.5) }, "12345", "2026-08-24")
        }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(fixture().apply { getJSONObject("rake").put("assetType", "locomotive") }, "12345", "2026-08-24") }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(fixture().apply { getJSONObject("rake").put("unavailableReason", "scheduled") }, "12345", "2026-08-24") }.isFailure)
        assertTrue(runCatching { PhysicalChain.decode(fixture().apply { getJSONObject("rake").remove("current") }, "12345", "2026-08-24") }.isFailure)
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
