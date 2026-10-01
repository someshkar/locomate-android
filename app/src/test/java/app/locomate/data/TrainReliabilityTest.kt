package app.locomate.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TrainReliabilityTest {
    @Test fun summaryUsesAllRecordedArrivalsAndExcludesCancelledAndUnknown() {
        val summary = valid().validatedFor("12951")
        assertEquals(15L, summary.counts.total)
        assertEquals(10L, summary.denominator)
        assertEquals(70.0, summary.percentages.onTime!!, 0.0)
        assertFalse(summary.lowSample)
    }

    @Test fun noClassifiableArrivalsKeepsPercentagesUnavailableEvenWithOtherRecords() {
        val summary = valid().copy(counts = ReliabilityCounts(0, 0, 0, 2, 3, 5), denominator = 0,
            percentages = ReliabilityPercentages(null, null, null), lowSample = true).validatedFor("12951")
        assertNull(summary.percentages.onTime)
        assertNotNull(summary.coverage)
        rejected(summary.copy(percentages = ReliabilityPercentages(0.0, 0.0, 0.0)))
        valid().copy(counts = ReliabilityCounts(0, 0, 0, 0, 0, 0), denominator = 0,
            percentages = ReliabilityPercentages(null, null, null), lowSample = true, coverage = null).validatedFor("12951")
    }

    @Test fun inconsistentNegativeAndUnsafeCountsCannotBecomeAStatistic() {
        rejected(valid().copy(counts = valid().counts.copy(total = 14)))
        rejected(valid().copy(counts = valid().counts.copy(early = -1)))
        rejected(valid().copy(counts = valid().counts.copy(early = Long.MAX_VALUE)))
        rejected(valid().copy(denominator = 15))
        rejected(valid().copy(lowSample = true))
    }

    @Test fun percentageMustBeFiniteAndMatchItsRecordedCount() {
        for (percentage in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 101.0, 90.0)) {
            rejected(valid().copy(percentages = valid().percentages.copy(onTime = percentage)))
        }
        // Rounded server percentages cannot silently overstate a small sample.
        val third = valid().copy(counts = ReliabilityCounts(1, 1, 1, 0, 0, 3), denominator = 3,
            percentages = ReliabilityPercentages(100.0 / 3, 100.0 / 3, 100.0 / 3), lowSample = true)
        third.validatedFor("12951")
        rejected(third.copy(percentages = ReliabilityPercentages(33.0, 33.0, 34.0)))
    }

    @Test fun identityPolicyAndProvenanceMustDescribeThisSupportedSummary() {
        rejected(valid().copy(trainNumber = "12137"))
        rejected(valid().copy(policy = valid().policy.copy(version = "other-policy")))
        rejected(valid().copy(policy = valid().policy.copy(onTimeThroughMinutes = 10.0)))
        rejected(valid().copy(policy = valid().policy.copy(earlyBelowMinutes = Double.NaN)))
        rejected(valid().copy(source = "preview"))
        rejected(valid().copy(comprehensiveCoverage = true))
        rejected(valid().copy(disclosure = " "))
    }

    @Test fun coverageRequiresRealOrderedDatesButCanContainFutureCancellations() {
        for (coverage in listOf(null, ReliabilityCoverage("2026-02-30", "2026-03-01"),
            ReliabilityCoverage("2026-10-01", "2026-09-01"))) {
            rejected(valid().copy(coverage = coverage))
        }
        valid().copy(coverage = ReliabilityCoverage("2026-09-01", "2050-01-01")).validatedFor("12951")
        rejected(valid().copy(generatedAt = "unknown"))
    }

    @Test fun generationTimeMustBeRepresentableAndCannotClaimAReportFromTheFuture() {
        val now = Instant.parse("2026-10-01T10:00:00Z")
        valid().copy(generatedAt = now.plusSeconds(60).toString()).validatedFor("12951", now)
        for (timestamp in listOf(now.plusSeconds(61), Instant.MIN, Instant.MAX, Instant.ofEpochSecond(-1))) {
            assertTrue(runCatching { valid().copy(generatedAt = timestamp.toString()).validatedFor("12951", now) }.isFailure)
        }
    }

    private fun rejected(summary: TrainReliabilitySummary) {
        assertTrue(runCatching { summary.validatedFor("12951") }.isFailure)
    }

    private fun valid() = TrainReliabilitySummary("12951", ReliabilityCounts(2, 7, 1, 2, 3, 15), 10,
        ReliabilityPercentages(20.0, 70.0, 10.0), ReliabilityCoverage("2026-09-01", "2026-09-30"), false,
        "2026-10-01T10:00:00Z", TrainReliabilitySummary.SUPPORTED_POLICY, "canonical-intelligence-tables", false,
        "Coverage is partial.")
}
