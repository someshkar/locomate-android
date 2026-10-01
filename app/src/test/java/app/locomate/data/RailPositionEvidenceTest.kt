package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RailPositionEvidenceTest {
    private val now = 1_790_820_000_000L

    @Test fun scheduledOrPredictedProgressCannotBecomeAnObservedMarker() {
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("predicted", "live", now - 30_000, false, now))
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("official", "scheduled", now - 30_000, false, now))
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("predicted", "scheduled", now - 30_000, true, now))
    }

    @Test fun recentObservedProgressCanBeShownAndCachedProgressIsStale() {
        assertEquals(PositionDisplay.Observed,
            RailPositionEvidence.display("community", "live", now - 120_000, false, now))
        assertEquals(PositionDisplay.Stale,
            RailPositionEvidence.display("community", "live", now - 120_000, true, now))
        assertEquals(PositionDisplay.Stale,
            RailPositionEvidence.display("official", "stale", now - 3_600_000, false, now))
    }

    @Test fun implausibleOrAncientObservationsAreHidden() {
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("official", "live", now - 700_000, false, now))
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("official", "stale", now - 73 * 3_600_000, false, now))
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("device", "live", now + 120_000, false, now))
        assertEquals(PositionDisplay.Hidden,
            RailPositionEvidence.display("device", "stale", now + 30_000, false, now))
    }
}
