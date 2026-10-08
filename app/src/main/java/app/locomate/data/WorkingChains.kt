package app.locomate.data

import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate
import java.util.UUID
import java.security.MessageDigest

data class WorkingRun(val id: String, val trainNumber: String, val date: String,
                      val name: String, val route: String, val timing: String, val status: String)
data class OperationalChain(val availability: String, val mode: String, val disclaimer: String,
                            val updatedAt: String, val runs: List<Pair<String, WorkingRun>>,
                            val linkage: String?, val delay: String?, val turnaround: String?) {
    companion object {
        fun decode(value: JSONObject, train: String, date: String): OperationalChain {
            require(value.getString("trainNumber") == train && value.getString("originDate") == date) { "Different operational run" }
            val availability = value.enum("availability", "available", "partial", "unavailable")
            val mode = value.enum("mode", "live", "inferred", "preview")
            val runs = listOf("previous", "current", "next").mapNotNull { role -> value.optJSONObject(role)?.let { run ->
                val reference = canonicalReference(run.getString("id"))
                require(run.getString("trainNumber") == reference.trainNumber && run.getString("role") == role)
                if (role == "current") require(reference.trainNumber == train && reference.serviceDate == date)
                role to WorkingRun(run.getString("id"), reference.trainNumber, reference.serviceDate,
                    run.getString("trainName"), "${run.getString("originCode")} → ${run.getString("destinationCode")}",
                    "Scheduled ${run.getString("scheduledDeparture")} → ${run.getString("scheduledArrival")}", mode)
            } }
            require(availability == "unavailable" || runs.any { it.first == "current" })
            val linkage = value.optJSONObject("linkage")?.let {
                "${it.getString("claim")} · ${it.getString("confidence")} · ${it.getString("method")}" +
                    it.getJSONArray("caveats").strings().joinToString("; ", prefix = " · ")
            }
            return OperationalChain(availability, mode, value.getString("disclaimer"), value.getString("updatedAt"), runs,
                linkage, value.optJSONObject("delayAssessment")?.getString("summary"),
                value.optJSONObject("turnaroundRisk")?.let { "${it.getString("level")} risk · ${it.getString("summary")}" })
        }
    }
}

data class PhysicalEvidence(val source: String?, val freshness: String, val confidence: Double?, val ageMillis: Double?)
data class PhysicalLink(val state: String, val reason: String, val evidence: PhysicalEvidence)
data class PhysicalAssetChain(val kind: String, val state: String, val unavailableReason: String?,
                              val evidence: PhysicalEvidence, val runs: List<Pair<String, WorkingRun>>,
                              val inbound: PhysicalLink, val outbound: PhysicalLink)
data class PhysicalChain(val state: String, val asOfMillis: Double, val assets: List<PhysicalAssetChain>) {
    companion object {
        fun decode(value: JSONObject, train: String, date: String): PhysicalChain {
            val expected = "run:$train:$date"
            require(value.getString("runId") == expected) { "Different physical run" }
            val state = value.enum("state", "available", "partial", "unavailable")
            val assets = listOf("rake", "locomotive").map { kind ->
                val asset = value.getJSONObject(kind)
                require(asset.getString("kind") == kind)
                val assetState = asset.enum("state", "available", "partial", "unavailable")
                val runs = listOf("previous", "current", "next").mapNotNull { role -> asset.optJSONObject(role)?.let { run ->
                    val ref = canonicalReference(run.getString("runId"))
                    require(run.getString("trainNumber") == ref.trainNumber && run.getString("serviceDate") == ref.serviceDate)
                    if (role == "current") require(run.getString("runId") == expected)
                    role to WorkingRun(run.getString("runId"), ref.trainNumber, ref.serviceDate, "Train ${ref.trainNumber}",
                        "${run.getJSONObject("origin").getString("code")} → ${run.getJSONObject("destination").getString("code")}",
                        "Service ${ref.serviceDate}", run.enum("status", "scheduled", "running", "completed", "cancelled"))
                } }
                if (assetState == "available") require(runs.any { it.first == "current" })
                PhysicalAssetChain(kind, assetState, asset.nullableString("unavailableReason"),
                    physicalEvidence(asset.getJSONObject("assignmentEvidence")), runs,
                    physicalLink(asset.getJSONObject("inboundLink")), physicalLink(asset.getJSONObject("outboundLink")))
            }
            return PhysicalChain(state, value.finite("asOf").also { require(it >= 0) }, assets)
        }
        private fun physicalEvidence(value: JSONObject) = PhysicalEvidence(value.nullableString("source"),
            value.enum("freshness", "fresh", "stale", "unknown"),
            value.nullableFinite("confidence")?.also { require(it in 0.0..1.0) },
            value.nullableFinite("ageMs")?.also { require(it >= 0) })
        private fun physicalLink(value: JSONObject) = PhysicalLink(value.enum("state", "confirmed", "broken", "unavailable"),
            value.enum("reason", "confirmed-assignments", "cancelled-transition", "asset-swap", "no-confirmed-run"),
            physicalEvidence(value.getJSONObject("evidence")))
    }
}

enum class SightingKind(val wire: String, val method: String) {
    Locomotive("locomotive", "visual-number"), Coach("coach", "onboard-coach-plate")
}
data class PhysicalSighting(val kind: SightingKind, val identifier: String, val observedAt: Long,
                            val stationCode: String? = null) {
    val validIdentifier: Boolean get() = when (kind) {
        SightingKind.Locomotive -> identifier.matches(Regex("[1-9][0-9]{4}"))
        SightingKind.Coach -> identifier.matches(Regex("[0-9]{5,12}")) && identifier.any { it != '0' }
    }
    fun encode(): JSONObject {
        require(validIdentifier && observedAt >= 0)
        require(stationCode == null || stationCode.matches(Regex("[A-Z0-9]{2,10}")))
        return JSONObject().put("assetKind", kind.wire).put("identifier", identifier)
            .put("observedAt", observedAt).put("evidenceMethod", kind.method).apply {
                stationCode?.let { put("stationCode", it) }
            }
    }
}
/** Exactly the public-number report and explicit fresh consent; no personal journey details or GPS. */
data class PhysicalSightingBatch(val sightings: List<PhysicalSighting>, val consentedAt: Long,
                                val evidenceId: String = UUID.randomUUID().toString()) {
    fun encode(): Pair<JSONObject, String> {
        require(sightings.size in 1..8 && sightings.map { it.kind to it.identifier }.distinct().size == sightings.size)
        val body = JSONObject().put("sightings", JSONArray(sightings.map { it.encode() }))
            .put("consent", JSONObject().put("evidenceId", evidenceId).put("purpose", CommunityConsent.PURPOSE)
                .put("granted", true).put("consentVersion", CommunityConsent.VERSION.toString())
                .put("noticeHash", CommunityConsent.NOTICE_HASH).put("consentedAt", consentedAt))
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return body to "physical-$digest"
    }
}
data class SightingAcknowledgement(val acceptedIds: List<String>, val locomotiveState: String,
                                  val rakeState: String, val message: String)

private fun canonicalReference(id: String): JourneyAlertLink {
    require(id.startsWith("run:"))
    return JourneyAlertLink.fromRunId(id.removePrefix("run:")) ?: error("Invalid dated identity")
}
private fun JSONObject.enum(key: String, vararg options: String) = getString(key).also { require(it in options) }
private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.finite(key: String): Double = (get(key) as? Number)?.toDouble()
    ?.takeIf { it.isFinite() } ?: error("Invalid numeric $key")
private fun JSONObject.nullableFinite(key: String): Double? = if (isNull(key)) null else finite(key)
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
