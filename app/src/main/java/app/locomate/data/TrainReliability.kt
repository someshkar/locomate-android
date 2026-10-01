package app.locomate.data

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

data class ReliabilityCounts(val early: Long, val onTime: Long, val late: Long,
                             val cancelled: Long, val unknown: Long, val total: Long)
data class ReliabilityPercentages(val early: Double?, val onTime: Double?, val late: Double?)
data class ReliabilityCoverage(val from: String, val to: String)
data class ReliabilityPolicy(val version: String, val earlyBelowMinutes: Double,
                             val onTimeFromMinutes: Double, val onTimeThroughMinutes: Double,
                             val lateAboveMinutes: Double)

/** A summary over the gateway's retained runs, independent of the requested page size. */
data class TrainReliabilitySummary(
    val trainNumber: String,
    val counts: ReliabilityCounts,
    val denominator: Long,
    val percentages: ReliabilityPercentages,
    val coverage: ReliabilityCoverage?,
    val lowSample: Boolean,
    val generatedAt: String,
    val policy: ReliabilityPolicy,
    val source: String,
    val comprehensiveCoverage: Boolean,
    val disclosure: String,
) {
    fun validatedFor(expectedTrain: String, now: Instant = Instant.now()): TrainReliabilitySummary {
        require(Regex("^[0-9]{4,6}$").matches(expectedTrain) && trainNumber == expectedTrain)
        val allCounts = listOf(counts.early, counts.onTime, counts.late, counts.cancelled, counts.unknown, counts.total, denominator)
        require(allCounts.all { it in 0..MAX_SAFE_COUNT })
        require(counts.total == counts.early + counts.onTime + counts.late + counts.cancelled + counts.unknown)
        require(denominator == counts.early + counts.onTime + counts.late)
        require(lowSample == (denominator < 10))
        listOf(counts.early to percentages.early, counts.onTime to percentages.onTime,
            counts.late to percentages.late).forEach { (count, percentage) ->
            if (denominator == 0L) require(percentage == null)
            else require(percentage != null && percentage.isFinite() && percentage in 0.0..100.0 &&
                abs(percentage - count.toDouble() * 100 / denominator) < 0.000001)
        }
        require((counts.total == 0L) == (coverage == null))
        coverage?.let {
            require(validDate(it.from) && validDate(it.to) && LocalDate.parse(it.from) <= LocalDate.parse(it.to))
        }
        val generated = Instant.parse(generatedAt)
        require(generated >= Instant.EPOCH && generated <= now.plusSeconds(60))
        require(policy == SUPPORTED_POLICY)
        require(source == "canonical-intelligence-tables" && !comprehensiveCoverage && disclosure.isNotBlank())
        return this
    }

    companion object {
        private const val MAX_SAFE_COUNT = 9_007_199_254_740_991L
        val SUPPORTED_POLICY = ReliabilityPolicy("destination-arrival-delay-v1", -5.0, -5.0, 5.0, 5.0)

        /** This view intentionally ignores the runs page, including fractional run delays. */
        fun decode(payload: JSONObject, expectedTrain: String): TrainReliabilitySummary {
            val summary = payload.getJSONObject("summary")
            val counts = summary.getJSONObject("counts")
            val percentages = summary.getJSONObject("percentages")
            val policy = payload.getJSONObject("policy")
            val provenance = payload.getJSONObject("provenance")
            val coverage = if (summary.get("coverage") === JSONObject.NULL) null
                else summary.getJSONObject("coverage").let { ReliabilityCoverage(it.text("from"), it.text("to")) }
            return TrainReliabilitySummary(payload.text("trainNumber"),
                ReliabilityCounts(counts.count("early"), counts.count("onTime"), counts.count("late"),
                    counts.count("cancelled"), counts.count("unknown"), counts.count("total")),
                summary.count("denominator"),
                ReliabilityPercentages(percentages.nullableNumber("early"), percentages.nullableNumber("onTime"),
                    percentages.nullableNumber("late")),
                coverage, summary.boolean("lowSample"), payload.text("generatedAt"),
                ReliabilityPolicy(policy.text("version"), policy.number("earlyBelowMinutes"),
                    policy.number("onTimeFromMinutes"), policy.number("onTimeThroughMinutes"), policy.number("lateAboveMinutes")),
                provenance.text("source"), provenance.boolean("comprehensiveCoverage"), provenance.text("disclosure"))
                .validatedFor(expectedTrain)
        }

        private fun validDate(value: String) = runCatching {
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value) && LocalDate.parse(value).toString() == value
        }.getOrDefault(false)
        private fun JSONObject.text(key: String) = get(key) as? String ?: error("Invalid history text")
        private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid history flag")
        private fun JSONObject.number(key: String): Double = (get(key) as? Number)?.toDouble()
            ?.takeIf { it.isFinite() } ?: error("Invalid history number")
        private fun JSONObject.nullableNumber(key: String): Double? =
            if (get(key) === JSONObject.NULL) null else number(key)
        private fun JSONObject.count(key: String): Long {
            val value = number(key)
            require(value >= 0 && value <= MAX_SAFE_COUNT.toDouble() && value == value.toLong().toDouble())
            return value.toLong()
        }
    }
}
