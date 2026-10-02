package app.locomate.data

/** Rail identity comes from dated provider fields, never a marker title or display name. */
fun NetworkTrain.journeyReference(): JourneyAlertLink? =
    JourneyAlertLink.fromRunId("$trainNumber:$originDate")

/** Opaque provider IDs may be reused for multiple dated services. */
fun NetworkTrain.datedIdentity(): String? = journeyReference()?.let { "$runId|${it.runId}" }

sealed interface NetworkSelection {
    data class Journey(val train: NetworkTrain, val reference: JourneyAlertLink) : NetworkSelection
    data class Inspect(val trains: List<NetworkTrain>) : NetworkSelection

    companion object {
        /** An info window may outlive its annotation's snapshot; reject removed/replaced members. */
        fun resolve(bound: List<NetworkTrain>, visible: List<NetworkTrain>): NetworkSelection? {
            val eligible = visible.toHashSet()
            val current = bound.filter { it in eligible }
            if (bound.size == 1) {
                val train = current.singleOrNull() ?: return null
                return Journey(train, train.journeyReference() ?: return null)
            }
            // A former cluster remains an inspection action, even when just one member survives.
            return current.takeIf { it.isNotEmpty() }?.let(::Inspect)
        }
    }
}
