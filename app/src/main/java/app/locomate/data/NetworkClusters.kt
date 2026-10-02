package app.locomate.data

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

data class NetworkMarkerGroup(val coordinate: RailPoint, val trains: List<NetworkTrain>) {
    val count: Int get() = trains.size
    val single: NetworkTrain? get() = trains.singleOrNull()
}

/** Groups dense network snapshots for the current camera zoom before creating map annotations. */
object NetworkClusters {
    fun forZoom(trains: List<NetworkTrain>, zoom: Double, maxMarkers: Int = 300): List<NetworkMarkerGroup> {
        if (trains.isEmpty()) return emptyList()
        require(maxMarkers > 0)
        var cell = max(0.025, 12.0 / 2.0.pow((zoom - 2.0).coerceAtLeast(0.0)))
        var buckets: Map<Pair<Int, Int>, List<NetworkTrain>>
        do {
            buckets = trains.groupBy { train ->
                floor(train.coordinate.latitude / cell).toInt() to
                    floor(train.coordinate.longitude / cell).toInt()
            }
            if (buckets.size <= maxMarkers) break
            cell *= 1.5
        } while (cell < 180.0)
        return buckets.toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
            .values.map { members ->
                NetworkMarkerGroup(
                    RailPoint(members.map { it.coordinate.latitude }.average(),
                        members.map { it.coordinate.longitude }.average()),
                    members,
                )
            }
    }
}
