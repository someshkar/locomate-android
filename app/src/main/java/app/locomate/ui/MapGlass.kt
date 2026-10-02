package app.locomate.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

internal val LocalMapGlass = staticCompositionLocalOf<MapGlassController?> { null }

/** One in-memory native snapshot per mounted map. No disk cache or UI screenshot. */
internal class MapGlassController {
    internal data class Snapshot(val image: ImageBitmap, val bounds: Rect, val revision: Long)
    var snapshot by mutableStateOf<Snapshot?>(null)
        private set
    private var owner: MapView? = null
    private var map: MapLibreMap? = null
    private var active = false
    private var generation = 0L
    private var revision = 0L
    private var inFlight = false
    private var dirty = false
    private var scheduled = false
    private var lastCapture = 0L
    private var contentKey: Any? = null
    private var fullyRendered = false
    private var cameraMoving = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { scheduled = false; capture() }
    private val idle = MapLibreMap.OnCameraIdleListener { cameraMoving = false; invalidate() }
    private val moving = MapLibreMap.OnCameraMoveStartedListener {
        revision++
        cameraMoving = true
        dirty = true
        // Keep the last settled decorative texture through a gesture; replacing
        // it after idle avoids a dark/transparent tint flash while dragging.
    }
    private val rendered = MapView.OnDidFinishRenderingMapListener { complete ->
        if (complete && !fullyRendered) invalidate()
        fullyRendered = complete
    }
    private val resized = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (view === owner && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
            revision++
            snapshot = null
            invalidate()
        }
    }

    fun attach(view: MapView, nativeMap: MapLibreMap, resumed: Boolean) {
        owner?.let(::detach)
        owner = view
        map = nativeMap
        active = resumed
        nativeMap.addOnCameraIdleListener(idle)
        nativeMap.addOnCameraMoveStartedListener(moving)
        view.addOnDidFinishRenderingMapListener(rendered)
        view.addOnLayoutChangeListener(resized)
        invalidate()
    }

    fun updateContent(view: MapView, key: Any?) {
        if (owner === view && contentKey != key) {
            contentKey = key
            revision++
            snapshot = null
            invalidate()
        }
    }

    fun resume(view: MapView) {
        if (owner === view) { active = true; invalidate() }
    }

    fun pause(view: MapView) {
        if (owner !== view) return
        active = false
        generation++
        inFlight = false
        view.removeCallbacks(refresh)
        scheduled = false
        snapshot = null
    }

    fun detach(view: MapView) {
        if (owner !== view) return
        pause(view)
        map?.removeOnCameraIdleListener(idle)
        map?.removeOnCameraMoveStartedListener(moving)
        view.removeOnDidFinishRenderingMapListener(rendered)
        view.removeOnLayoutChangeListener(resized)
        owner = null
        map = null
        inFlight = false
        contentKey = null
        fullyRendered = false
        cameraMoving = false
        lastCapture = 0L
    }

    private fun invalidate() {
        dirty = true
        val view = owner ?: return
        if (!active || inFlight || scheduled) return
        scheduled = true
        view.postDelayed(refresh, (lastCapture + 300 - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun capture() {
        val view = owner ?: return
        if (map == null) return
        if (!active || !dirty || cameraMoving || inFlight || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
        dirty = false
        inFlight = true
        lastCapture = SystemClock.uptimeMillis()
        val capturedGeneration = generation
        val capturedRevision = revision
        val surface = surfaceIn(view) ?: run { inFlight = false; return }
        val location = IntArray(2).also(surface::getLocationInWindow)
        val bounds = Rect(location[0].toFloat(), location[1].toFloat(),
            (location[0] + surface.width).toFloat(), (location[1] + surface.height).toFloat())
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) {
            inFlight = false
            invalidate()
            return
        }
        // Copy just the native GL surface, excluding the sheet and foreground UI.
        // PixelCopy scales into one bounded texture instead of a full-size bitmap.
        val scale = minOf(1f, 960f / maxOf(surface.width, surface.height))
        val image = Bitmap.createBitmap((surface.width * scale).roundToInt().coerceAtLeast(1),
            (surface.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, image, { result ->
            if (owner !== view || capturedGeneration != generation) return@request
            inFlight = false
            if (!active) return@request
            if (result == PixelCopy.SUCCESS && !cameraMoving && capturedRevision == revision
                && bounds.width == surface.width.toFloat() && bounds.height == surface.height.toFloat()) {
                snapshot = Snapshot(image.asImageBitmap(), bounds, capturedRevision)
            } else if (result != PixelCopy.SUCCESS) {
                dirty = true
            }
            if (dirty) invalidate()
        }, handler)
    }

    private fun surfaceIn(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            surfaceIn(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}

/** Crop the last settled native map in window coordinates; blur only this decorative layer. */
@Composable
internal fun MapGlassSurface(modifier: Modifier = Modifier, content: @Composable BoxScope.(Boolean) -> Unit) {
    val snapshot = LocalMapGlass.current?.snapshot
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val overlaps = snapshot != null && bounds.width > 0 && bounds.height > 0
        && snapshot.bounds.overlaps(bounds)
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }) {
        if (overlaps) {
            val source = requireNotNull(snapshot)
            Canvas(Modifier.matchParentSize().blur(30.dp, BlurredEdgeTreatment.Rectangle)) {
                drawRect(Color(0xFF060708))
                drawImage(source.image,
                    dstOffset = IntOffset((source.bounds.left - bounds.left).roundToInt(), (source.bounds.top - bounds.top).roundToInt()),
                    dstSize = IntSize(source.bounds.width.roundToInt(), source.bounds.height.roundToInt()))
            }
        }
        content(overlaps)
    }
}
