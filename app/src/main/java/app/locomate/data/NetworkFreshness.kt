package app.locomate.data

import java.time.Instant

/** A response's TTL and each marker's timestamp must both support showing a current position. */
object NetworkFreshness {
    private const val MAX_AGE = 10 * 60_000L
    private const val FUTURE_TOLERANCE = 60_000L
    private val kinds = setOf("observed", "map-matched", "interpolated", "predicted")
    private val observedSources = setOf("official", "community", "device")

    data class View(val trains: List<NetworkTrain>, val expired: Boolean, val nextChangeAtMillis: Long?)
    private data class TimedTrain(val train: NetworkTrain, val observedAt: Long)

    /** Parse a large gateway response once, off the UI thread; clocks then compare numeric deadlines. */
    fun prepare(snapshot: NetworkSnapshot): Prepared = Prepared(snapshot)

    class Prepared internal constructor(val snapshot: NetworkSnapshot) {
        private val generated = millis(snapshot.generatedAt)
        private val expires = millis(snapshot.freshUntil)
        private val candidates = snapshot.trains.mapNotNull { train ->
            val observed = millis(train.observedAt) ?: return@mapNotNull null
            val coordinate = train.coordinate
            if (!coordinate.latitude.isFinite() || coordinate.latitude !in -90.0..90.0 ||
                !coordinate.longitude.isFinite() || coordinate.longitude !in -180.0..180.0 ||
                train.positionKind !in kinds ||
                (train.positionKind == "observed" && train.source !in observedSources)) return@mapNotNull null
            TimedTrain(train, observed)
        }

        fun at(now: Long): View {
            if (generated == null || expires == null || expires < generated || expires <= now) {
                return View(emptyList(), expired = true, nextChangeAtMillis = null)
            }
            if (generated > now + FUTURE_TOLERANCE) {
                return View(emptyList(), expired = true, nextChangeAtMillis = generated - FUTURE_TOLERANCE)
            }
            var nextChange: Long = expires
            val visible = candidates.mapNotNull { candidate ->
                when {
                    candidate.observedAt > now + FUTURE_TOLERANCE -> {
                        nextChange = minOf(nextChange, candidate.observedAt - FUTURE_TOLERANCE)
                        null
                    }
                    candidate.observedAt < now - MAX_AGE -> null
                    else -> {
                        nextChange = minOf(nextChange, candidate.observedAt + MAX_AGE + 1)
                        candidate.train
                    }
                }
            }
            return View(visible, expired = false, nextChangeAtMillis = nextChange)
        }
    }

    fun isFresh(snapshot: NetworkSnapshot, now: Long): Boolean = !prepare(snapshot).at(now).expired
    fun visibleTrains(snapshot: NetworkSnapshot?, now: Long): List<NetworkTrain> =
        snapshot?.let { prepare(it).at(now).trains }.orEmpty()

    private fun millis(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}
