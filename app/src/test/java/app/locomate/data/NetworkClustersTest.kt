package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkClustersTest {
    private fun train(index: Int, lat: Double, lon: Double) = NetworkTrain(
        runId = "run-$index", trainNumber = "12137", name = "Punjab Mail",
        originDate = "2026-10-01", coordinate = RailPoint(lat, lon),
        observedAt = "2026-10-01T03:00:00Z", positionKind = "interpolated",
        source = "community", delayMinutes = null,
    )

    @Test fun nationalViewKeepsEveryTrainInBoundedGroups() {
        val trains = (0 until 1_000).map { index ->
            train(index, 8.0 + (index % 35) * 0.55, 68.0 + (index / 35) * 0.65)
        }
        val groups = NetworkClusters.forZoom(trains, 4.5)
        assertTrue(groups.size <= 300)
        assertEquals(trains.size, groups.sumOf(NetworkMarkerGroup::count))
    }

    @Test fun isolatedTrainRetainsItsExactCoordinate() {
        val one = train(1, 23.4, 77.2)
        val group = NetworkClusters.forZoom(listOf(one), 9.0).single()
        assertEquals(one, group.single)
        assertEquals(one.coordinate, group.coordinate)
    }
}
