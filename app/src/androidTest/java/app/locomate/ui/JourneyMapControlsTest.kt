package app.locomate.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGeometry
import app.locomate.data.RailPoint
import app.locomate.ui.theme.LocomateTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class JourneyMapControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun configureWindow() = configureEdgeToEdgeTestWindow(compose.activity)

    @Test fun nativeGlassSnapshotFollowsCameraAndClearsWhenTheActivityStops() {
        var route by mutableStateOf(PreviewRoutes.load(compose.activity).first())
        val glass = MapGlassController()
        compose.setContent { LocomateTheme {
            NavigationScaffold(Tab.Journeys, {}, {}, mapGlass = glass) { inset ->
                JourneyScreen(route, saved = false, onSave = {}, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot?.let { containsRoutePixels(it.image.asAndroidBitmap()) } == true }
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val fitted = requireNotNull(glass.snapshot)
        val texture = File(compose.activity.getExternalFilesDir(null), "map-controls/glass-native-texture.png")
        texture.parentFile?.mkdirs()
        texture.outputStream().use { fitted.image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(view.width.toFloat(), fitted.bounds.width)
        assertEquals(view.height.toFloat(), fitted.bounds.height)
        assertTrue(maxOf(fitted.image.width, fitted.image.height) <= 960)
        compose.onNodeWithContentDescription("Show historical sample position").performClick()
        compose.waitUntil(10_000) { glass.snapshot?.let { it.image !== fitted.image } == true }
        val focused = requireNotNull(glass.snapshot)
        assertTrue("The snapshot must belong to the new camera revision", focused.revision > fitted.revision)
        var map: MapLibreMap? = null
        compose.runOnUiThread { view.getMapAsync { map = it } }
        screenshot("glass-position-focus", view, requireNotNull(map))
        compose.onNodeWithContentDescription("Fit journey route").performClick()
        compose.waitUntil(10_000) { glass.snapshot?.let { it.image !== focused.image } == true }
        screenshot("glass-route-fit", view, requireNotNull(map))
        // Exercise the same native camera adjustment while the glass moves.
        compose.onNodeWithContentDescription("Expand journey details").performClick()
        compose.waitUntil(10_000) { glass.snapshot != null }
        screenshot("glass-expanded", view, requireNotNull(map))
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull("Backgrounded maps must release their glass snapshot", glass.snapshot)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { glass.snapshot != null }
        val resumed = requireNotNull(glass.snapshot)
        compose.runOnIdle { route = route.copy(trainNumber = "54321") }
        compose.waitUntil(15_000) {
            findMap(compose.activity.window.decorView) !== view && glass.snapshot?.let {
                it.image !== resumed.image && containsRoutePixels(it.image.asAndroidBitmap())
            } == true
        }
        val replacement = requireNotNull(findMap(compose.activity.window.decorView))
        compose.runOnUiThread { glass.detach(replacement) }
        assertNull("A detached map must not leave a decorative image behind", glass.snapshot)
    }

    @Test fun nativeCameraRepeatsActionsAndRefreshesGeometryWithoutRecreatingTheMap() {
        var route by mutableStateOf(PreviewRoutes.load(compose.activity).first())
        compose.setContent { LocomateTheme {
            NavigationScaffold(Tab.Journeys, {}, {}) { inset ->
                JourneyScreen(route, saved = false, onSave = {}, bottomInset = inset)
            }
        } }
        compose.waitUntil(15_000) {
            !compose.onNodeWithContentDescription("Fit journey route").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        val view = findMap(compose.activity.window.decorView) ?: error("Native MapLibre view missing")
        var map: MapLibreMap? = null
        compose.runOnUiThread { view.getMapAsync { map = it } }
        compose.waitUntil(5_000) { map != null }
        val nativeMap = requireNotNull(map)
        val marker = requireNotNull(RailGeometry.pointAtProgress(route.geometry, requireNotNull(route.positionProgress)))
        val focus = compose.onNodeWithContentDescription("Show historical sample position").assertIsDisplayed().assertIsEnabled()
        focus.performClick()
        compose.runOnUiThread {
            val point = nativeMap.projection.toScreenLocation(LatLng(marker.latitude, marker.longitude))
            assertTrue("Focused marker must stay above the sheet", point.y in (112 * view.resources.displayMetrics.density)..(view.height * 0.47f))
            nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 5.0))
        }
        focus.performClick()
        compose.runOnUiThread { assertTrue("Repeated tap did not focus the marker", nativeMap.cameraPosition.target!!.latitude > 10) }
        screenshot("position-focus", view, nativeMap)
        compose.onNodeWithContentDescription("Fit journey route").performClick()
        compose.runOnUiThread {
            val density = view.resources.displayMetrics.density
            for (point in route.geometry) {
                val pixel = nativeMap.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                assertTrue("Route point lies outside the map viewport: $pixel",
                    pixel.x in (30 * density)..(view.width - 70 * density)
                        && pixel.y in (110 * density)..(view.height * 0.47f - 30 * density))
            }
        }
        screenshot("route-fit", view, nativeMap)
        val replacement = listOf(RailPoint(22.0, 76.0), RailPoint(23.0, 77.0))
        compose.runOnIdle { route = route.copy(geometry = replacement) }
        compose.waitUntil(5_000) {
            var updated = false
            compose.runOnUiThread { updated = nativeMap.polylines.singleOrNull()?.points?.firstOrNull()?.latitude == 22.0 }
            updated
        }
        assertSame("Refreshing geometry recreated the map", view, findMap(compose.activity.window.decorView))
        // Hidden/expired evidence removes the marker focus action.
        compose.runOnIdle { route = route.copy(positionProgress = null, positionStatus = null) }
        compose.onNodeWithContentDescription("Train position unavailable").assertIsNotEnabled()
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun containsRoutePixels(bitmap: Bitmap): Boolean {
        // Reject an empty native texture: lifecycle state alone cannot prove
        // that the actual GL route reached the bitmap used by the glass.
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { color ->
            val red = android.graphics.Color.red(color)
            val green = android.graphics.Color.green(color)
            val blue = android.graphics.Color.blue(color)
            android.graphics.Color.alpha(color) > 200 && blue > 160 && blue - red > 70 && blue - green > 35
        } > 20
    }

    private fun screenshot(name: String, view: MapView, map: MapLibreMap) {
        // Compose can be idle while the GL surface still displays the previous
        // camera. Wait for rendered frames, including a requested repaint.
        val frames = AtomicInteger()
        val listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            if (frames.incrementAndGet() < 2) view.post { map.triggerRepaint() }
        }
        compose.runOnUiThread {
            view.addOnDidFinishRenderingFrameListener(listener)
            map.triggerRepaint()
        }
        try {
            compose.waitUntil(15_000) { frames.get() >= 2 }
            compose.waitForIdle()
            val file = File(compose.activity.getExternalFilesDir(null), "map-controls/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            compose.runOnUiThread { view.removeOnDidFinishRenderingFrameListener(listener) }
        }
    }
}
