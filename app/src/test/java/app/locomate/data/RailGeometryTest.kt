package app.locomate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailGeometryTest {
    @Test fun markerProgressFollowsDistanceRatherThanPointIndex() {
        val route = listOf(RailPoint(0.0, 0.0), RailPoint(0.0, 1.0), RailPoint(0.0, 3.0))
        val middle = RailGeometry.pointAtProgress(route, 0.5)!!
        assertEquals(0.0, middle.latitude, 0.001)
        assertEquals(1.5, middle.longitude, 0.01)
        assertEquals(0.0, RailGeometry.pointAtProgress(route, -1.0)!!.longitude, 0.001)
        assertEquals(3.0, RailGeometry.pointAtProgress(route, 2.0)!!.longitude, 0.001)
    }

    @Test fun emptyRouteHasNoMarker() {
        assertNull(RailGeometry.pointAtProgress(emptyList(), 0.5))
    }
}
