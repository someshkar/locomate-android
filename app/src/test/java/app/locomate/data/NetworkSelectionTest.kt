package app.locomate.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NetworkSelectionTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z").toEpochMilli()
    private fun time(value: Long) = Instant.ofEpochMilli(value).toString()
    private val train = NetworkTrain("opaque-shared-id", "12137", "99999 · Misleading display name", "2026-10-02",
        RailPoint(23.7, 76.0), time(now), "observed", "official", 0.0)

    @Test fun datedSelectionUsesStructuredFieldsAndRejectsInvalidDates() {
        val selection = NetworkSelection.resolve(listOf(train), listOf(train)) as NetworkSelection.Journey
        assertEquals(JourneyAlertLink("12137", "2026-10-02"), selection.reference)
        val invalid = train.copy(originDate = "2026-02-30")
        assertNull(NetworkSelection.resolve(listOf(invalid), listOf(invalid)))
    }

    @Test fun removedOrReplacedAnnotationCannotOpenItsOldSnapshot() {
        assertNull(NetworkSelection.resolve(listOf(train), emptyList()))
        assertNull(NetworkSelection.resolve(listOf(train), listOf(train.copy(observedAt = time(now + 1)))))
    }

    @Test fun clusterRemainsInspectionEvenWithOnlyOneSurvivingMember() {
        val other = train.copy(originDate = "2026-10-01")
        val action = NetworkSelection.resolve(listOf(train, other), listOf(train)) as NetworkSelection.Inspect
        assertEquals(listOf(train), action.trains)
    }

    @Test fun sharedOpaqueIdsKeepDistinctDatesAndDeduplicateOnlyEligibleObservations() {
        val old = train.copy(observedAt = time(now - 600_001))
        val olderEligible = train.copy(observedAt = time(now - 5_000))
        val tomorrow = train.copy(originDate = "2026-10-03")
        val snapshot = NetworkSnapshot(listOf(old, olderEligible, train, train, tomorrow), time(now), time(now + 60_000))
        assertEquals(listOf(train, tomorrow), NetworkFreshness.prepare(snapshot).at(now).trains)
        assertNotEquals(train.datedIdentity(), tomorrow.datedIdentity())
    }
}
