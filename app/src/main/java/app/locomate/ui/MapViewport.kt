package app.locomate.ui

import android.graphics.PointF
import androidx.compose.ui.geometry.Rect
import app.locomate.data.NetworkBounds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/** Public camera padding keeps the map center and branding in the exposed viewport. */
internal class MapViewportSync {
    private var padding: List<Double>? = null

    fun update(view: MapView, map: MapLibreMap, viewport: Rect?) {
        if (viewport == null && padding == null) return // Journey owns its own camera padding.
        val visible = clippedViewport(view, viewport) ?: return
        val next = listOf(visible.left.toDouble(), visible.top.toDouble(),
            (view.width - visible.right).toDouble(), (view.height - visible.bottom).toDouble())
        if (next == padding) return
        padding = next
        map.moveCamera(CameraUpdateFactory.paddingTo(next.toDoubleArray()))
        val margin = (8 * view.resources.displayMetrics.density).roundToInt()
        map.uiSettings.setLogoMargins(visible.left.roundToInt() + margin, margin, margin,
            (view.height - visible.bottom).roundToInt() + margin)
        map.uiSettings.setAttributionMargins(margin, margin, margin,
            (view.height - visible.bottom).roundToInt() + margin)
    }
}

/** Query only geography a person can see, excluding the area beneath the sheet. */
internal fun visibleMapBounds(view: MapView, map: MapLibreMap, viewport: Rect?): NetworkBounds? {
    val visible = clippedViewport(view, viewport) ?: return null
    val bounds = if (viewport == null) map.projection.visibleRegion.latLngBounds else {
        val points = listOf(PointF(visible.left, visible.top), PointF(visible.right, visible.top),
            PointF(visible.left, visible.bottom), PointF(visible.right, visible.bottom))
            .map(map.projection::fromScreenLocation)
        if (points.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return null
        LatLngBounds.Builder().includes(points).build()
    }
    return if (bounds.longitudeSpan > 0 && bounds.latitudeSpan > 0)
        NetworkBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
    else null
}

private fun clippedViewport(view: MapView, viewport: Rect?): Rect? {
    if (view.width <= 0 || view.height <= 0) return null
    val frame = Rect(0f, 0f, view.width.toFloat(), view.height.toFloat())
    val visible = viewport?.intersect(frame) ?: frame
    return visible.takeIf { it.width > 0 && it.height > 0 }
}
