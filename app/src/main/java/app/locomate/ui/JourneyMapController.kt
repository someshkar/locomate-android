package app.locomate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.locomate.data.RailGeometry
import app.locomate.data.RoutePreview
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Camera actions always resolve the current dated route and displayed marker. */
class JourneyMapController {
    var available by mutableStateOf(false)
        private set
    private var owner: MapView? = null
    private var map: MapLibreMap? = null
    private var route: RoutePreview? = null
    private var scope: String? = null
    private var sheetHeight = 0f
    private var lastAppliedHeight = Float.NaN
    private var positionFocus = false
    private var fitted = false

    internal fun attach(view: MapView, nativeMap: MapLibreMap) {
        owner = view
        map = nativeMap
        fitted = false
        apply()
        view.doOnNextLayout { if (owner === view) apply() }
    }

    internal fun update(snapshot: RoutePreview?, visibleSheetHeight: Float) {
        if (snapshot?.geometry != route?.geometry) fitted = false
        val identity = snapshot?.let { "${it.trainNumber}|${it.runDate}|${it.isPreview}" }
        if (identity != scope) {
            scope = identity
            positionFocus = false
            fitted = false
        }
        route = snapshot
        sheetHeight = visibleSheetHeight
        if (positionFocus && marker() == null) {
            positionFocus = false
            fitted = false
        }
        val density = owner?.resources?.displayMetrics?.density ?: 1f
        if (!fitted || abs(sheetHeight - lastAppliedHeight) > 48 * density) apply()
    }

    fun fitRoute() {
        positionFocus = false
        fitted = false
        apply()
    }

    fun showPosition() {
        if (marker() == null) return
        positionFocus = true
        fitted = false
        apply()
    }

    internal fun detach(view: MapView) {
        if (owner !== view) return
        owner = null
        map = null
        available = false
    }

    private fun marker() = route?.let { snapshot ->
        snapshot.positionProgress?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?.let { RailGeometry.pointAtProgress(snapshot.geometry, it) }
    }

    private fun apply() {
        val view = owner ?: return
        val nativeMap = map ?: return
        val snapshot = route
        val points = snapshot?.geometry.orEmpty().filter {
            it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
        available = points.size >= 2 && points.size == snapshot?.geometry?.size && view.height > 100
        if (!available) return
        val density = view.resources.displayMetrics.density
        val top = (112 * density).roundToInt()
        val bottom = (sheetHeight + 32 * density).roundToInt()
            .coerceIn(0, maxOf(0, view.height - top - (100 * density).roundToInt()))
        val bounds = if (positionFocus) {
            val point = marker() ?: return
            LatLngBounds.Builder()
                .include(LatLng((point.latitude - 0.4).coerceAtLeast(-89.0), (point.longitude - 0.5).coerceAtLeast(-180.0)))
                .include(LatLng((point.latitude + 0.4).coerceAtMost(89.0), (point.longitude + 0.5).coerceAtMost(180.0)))
                .build()
        } else LatLngBounds.Builder().includes(points.map { LatLng(it.latitude, it.longitude) }).build()
        nativeMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds,
            (32 * density).roundToInt(), top, (72 * density).roundToInt(), bottom))
        fitted = true
        lastAppliedHeight = sheetHeight
    }
}

private fun MapView.doOnNextLayout(action: () -> Unit) {
    if (width > 0 && height > 0) post { action() }
    else addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
        override fun onLayoutChange(v: android.view.View, left: Int, top: Int, right: Int, bottom: Int,
                                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            removeOnLayoutChangeListener(this)
            action()
        }
    })
}
