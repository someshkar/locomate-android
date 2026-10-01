package app.locomate.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object RailGeometry {
    fun pointAtProgress(points: List<RailPoint>, progress: Double): RailPoint? {
        if (points.isEmpty()) return null
        if (points.size == 1) return points.first()
        val lengths = points.zipWithNext(::distanceKm)
        val total = lengths.sum()
        if (total <= 0.0) return points.first()
        val target = total * progress.coerceIn(0.0, 1.0)
        var passed = 0.0
        lengths.forEachIndexed { index, segment ->
            if (target <= passed + segment || index == lengths.lastIndex) {
                val fraction = if (segment > 0.0) ((target - passed) / segment).coerceIn(0.0, 1.0) else 0.0
                val start = points[index]
                val end = points[index + 1]
                return RailPoint(start.latitude + (end.latitude - start.latitude) * fraction,
                    start.longitude + (end.longitude - start.longitude) * fraction)
            }
            passed += segment
        }
        return points.last()
    }

    private fun distanceKm(a: RailPoint, b: RailPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(b.longitude - a.longitude)
        val arc = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 12742.0 * asin(min(1.0, sqrt(arc)))
    }
}
