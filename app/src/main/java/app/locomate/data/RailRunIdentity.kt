package app.locomate.data

/** Canonicalize only at the observation wire boundary; local queue and notification IDs stay stable. */
internal fun canonicalObservationRunId(localRunId: String): String {
    val short = localRunId.removePrefix("run:")
    require(JourneyAlertLink.fromRunId(short) != null) { "Invalid observation run ID" }
    return "run:$short"
}
