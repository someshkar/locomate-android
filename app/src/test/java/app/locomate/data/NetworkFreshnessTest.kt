package app.locomate.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFreshnessTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
    private fun time(value: Long) = Instant.ofEpochMilli(value).toString()
    private val train = NetworkTrain("12951:2026-10-01", "12951", "Rajdhani", "2026-10-01",
        RailPoint(19.0, 72.8), time(now), "observed", "official", 0)
    private fun snapshot(trains: List<NetworkTrain> = listOf(train)) =
        NetworkSnapshot(trains, time(now), time(now + 60_000))

    @Test fun responseExpiryHidesMarkersWithoutAnotherFetch() {
        assertEquals(1, NetworkFreshness.visibleTrains(snapshot(), now + 59_999).size)
        assertTrue(NetworkFreshness.visibleTrains(snapshot(), now + 60_000).isEmpty())
        assertFalse(NetworkFreshness.isFresh(snapshot(), now + 60_000))
    }

    @Test fun markerEvidenceExpiresEvenWhenResponseHasLongerTtl() {
        val longer = snapshot().copy(freshUntil = time(now + 3_600_000))
        assertEquals(1, NetworkFreshness.visibleTrains(longer, now + 600_000).size)
        assertTrue(NetworkFreshness.visibleTrains(longer, now + 600_001).isEmpty())
    }

    @Test fun rejectsBrokenClockBoundsCoordinatesAndObservedSource() {
        val invalid = listOf(train.copy(observedAt = "unknown"),
            train.copy(observedAt = time(now + 60_001)),
            train.copy(observedAt = time(now - 600_001)),
            train.copy(coordinate = RailPoint(Double.NaN, 72.0)),
            train.copy(coordinate = RailPoint(19.0, 181.0)),
            train.copy(source = "scheduled"), train.copy(positionKind = "live"))
        assertEquals(listOf(train), NetworkFreshness.visibleTrains(snapshot(invalid + train), now))
        assertFalse(NetworkFreshness.isFresh(snapshot().copy(generatedAt = "bad"), now))
        assertFalse(NetworkFreshness.isFresh(snapshot().copy(generatedAt = time(now + 60_001)), now))
        assertFalse(NetworkFreshness.isFresh(snapshot().copy(freshUntil = time(now - 1)), now))
    }

    @Test fun preparedSnapshotSchedulesOnlyTheNextFreshnessBoundary() {
        val old = train.copy(runId = "other-provider-run", observedAt = time(now - 590_000))
        val prepared = NetworkFreshness.prepare(snapshot(listOf(old, train)))
        assertEquals(now + 10_001, prepared.at(now).nextChangeAtMillis)
        assertEquals(listOf(train), prepared.at(now + 10_001).trains)
        assertEquals(now + 60_000, prepared.at(now + 10_001).nextChangeAtMillis)
        assertTrue(prepared.at(now + 60_000).expired)
        assertEquals(null, prepared.at(now + 60_000).nextChangeAtMillis)
    }

    @Test fun estimatedPositionsKeepTheirExplicitKind() {
        val estimates = listOf("map-matched", "interpolated", "predicted").map {
            train.copy(runId = "$it-sample", positionKind = it, source = "predicted")
        }
        assertEquals(estimates, NetworkFreshness.visibleTrains(snapshot(estimates), now))
    }
}
