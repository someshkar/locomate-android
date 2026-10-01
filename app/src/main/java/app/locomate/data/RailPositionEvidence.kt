package app.locomate.data

/** A timetable progress value is never enough to place a live train marker. */
enum class PositionDisplay { Hidden, Observed, Stale }

object RailPositionEvidence {
    private val observedSources = setOf("official", "community", "device")
    private const val liveWindowMillis = 10 * 60_000L
    private const val lastKnownWindowMillis = 72 * 60 * 60_000L

    fun display(source: String?, freshness: String, observedAtMillis: Long?, cached: Boolean,
                nowMillis: Long = System.currentTimeMillis()): PositionDisplay {
        if (source !in observedSources || observedAtMillis == null || observedAtMillis <= 0L ||
            observedAtMillis > nowMillis + 60_000L) return PositionDisplay.Hidden
        val age = nowMillis - observedAtMillis
        return when {
            (cached || freshness == "stale") && age <= lastKnownWindowMillis -> PositionDisplay.Stale
            !cached && freshness == "live" && age in 0..liveWindowMillis -> PositionDisplay.Observed
            else -> PositionDisplay.Hidden
        }
    }
}
