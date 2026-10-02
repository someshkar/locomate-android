package app.locomate.ui

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.NetworkBounds
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGateway
import app.locomate.data.SavedJourney
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
class OverviewMapGlassTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun configureWindow() = configureEdgeToEdgeTestWindow(compose.activity)

    @Test fun nativeQueriesExcludeCoveredGeographyAndResizePreservesThePannedCamera() {
        val route = PreviewRoutes.load(compose.activity).first()
        val glass = MapGlassController()
        var bottomInset by mutableStateOf(115.dp)
        var viewport = Rect.Zero
        var published: NetworkBounds? = null
        compose.setContent { LocomateTheme {
            NavigationScaffold(Tab.Explore, {}, {}, mapGlass = glass) {
                OverviewMapLayout(bottomInset, map = { modifier, visible ->
                    viewport = visible
                    RailMap(route, modifier = modifier, visibleViewport = visible,
                        onVisibleBounds = { published = it })
                }) { Text("Viewport fixture") }
            }
        } }
        compose.waitUntil(15_000) { published != null && glass.snapshot?.let { hasRoute(it.image.asAndroidBitmap()) } == true }
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val map = nativeMap(view)
        val texture = requireNotNull(glass.snapshot)
        assertEquals(view.height.toFloat(), texture.bounds.height)
        assertTrue("The native backdrop must extend below the exposed viewport", view.height > viewport.bottom * 2)
        compose.runOnUiThread {
            assertBounds(map, viewport, requireNotNull(published))
            val center = map.projection.toScreenLocation(requireNotNull(map.cameraPosition.target))
            assertEquals(viewport.center.x, center.x, 2f)
            assertEquals(viewport.center.y, center.y, 2f)
            val full = map.projection.getVisibleRegion(true).latLngBounds
            assertTrue("Covered geography leaked into the network query",
                full.latitudeSpan > requireNotNull(published).north - requireNotNull(published).south + 1)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(26.0, 80.0), 5.75))
        }
        compose.waitUntil(10_000) { glass.snapshot?.let { it.image !== texture.image } == true }
        val oldBottom = viewport.bottom
        compose.runOnIdle { bottomInset += 60.dp }
        compose.waitUntil(5_000) { viewport.bottom < oldBottom }
        compose.runOnUiThread {
            assertSame(view, findMap(compose.activity.window.decorView))
            assertEquals(26.0, requireNotNull(map.cameraPosition.target).latitude, 0.00002)
            assertEquals(80.0, requireNotNull(map.cameraPosition.target).longitude, 0.00002)
            assertEquals(5.75, map.cameraPosition.zoom, 0.00002)
            assertBounds(map, viewport, requireNotNull(published))
            // SDK 13.5.2 assigns this observed view tag; no private field access.
            val logo = requireNotNull(view.findViewWithTag<View>("logoView"))
            val nativeLocation = IntArray(2).also(view::getLocationInWindow)
            val logoRect = android.graphics.Rect()
            assertTrue("Native MapLibre branding is hidden", logo.getGlobalVisibleRect(logoRect))
            assertTrue("Native branding must stay above the sheet", logoRect.bottom <= nativeLocation[1] + viewport.bottom)
            assertTrue("Native branding must stay below the status bar", logoRect.top >= nativeLocation[1] + viewport.top)
        }
    }

    @Test fun actualOverviewPagesReplaceGlassAndSearchKeyboardRetainsItsNativeCamera() {
        val route = PreviewRoutes.load(compose.activity).first()
        val saved = SavedJourney.from(route.copy(isPreview = false, runDate = "2026-10-01"))
        val gateway = RailGateway(compose.activity, "")
        val glass = MapGlassController()
        var tab by mutableStateOf(Tab.Explore)
        var search by mutableStateOf(false)
        compose.setContent { LocomateTheme {
            NavigationScaffold(tab, { tab = it; search = false }, { search = true },
                searchActive = search, mapGlass = glass) { inset ->
                if (search) SearchScreen(listOf(route), gateway, {}, { _, _ -> }, bottomInset = inset)
                else when (tab) {
                    Tab.Explore -> ExploreScreen(route, gateway, bottomInset = inset)
                    Tab.Passport -> PassportScreen(listOf(saved), onOpen = {}, onRemove = {}, onSettings = {}, bottomInset = inset)
                    Tab.Journeys -> JourneyScreen(route, saved = false, onSave = {}, bottomInset = inset)
                }
            }
        } }
        compose.waitUntil(15_000) { glass.snapshot?.let { hasRoute(it.image.asAndroidBitmap()) } == true }
        val explore = requireNotNull(glass.snapshot)
        val exploreView = requireNotNull(findMap(compose.activity.window.decorView))
        assertFullBackdrop(explore)
        screenshot("explore-glass-normal")
        compose.onNodeWithContentDescription("Passport").performClick()
        compose.waitUntil(15_000) {
            findMap(compose.activity.window.decorView)?.let { it !== exploreView } == true
                && glass.snapshot?.let { it.image !== explore.image } == true
        }
        val passport = requireNotNull(glass.snapshot)
        val passportView = requireNotNull(findMap(compose.activity.window.decorView))
        assertFullBackdrop(passport)
        compose.onNode(hasText("Passport") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        screenshot("passport-glass-normal")
        compose.onNodeWithContentDescription("Search trains").performClick()
        compose.waitUntil(15_000) {
            findMap(compose.activity.window.decorView)?.let { it !== passportView } == true
                && glass.snapshot?.let { it.image !== passport.image } == true
        }
        val searchTexture = requireNotNull(glass.snapshot)
        assertFullBackdrop(searchTexture)
        val searchView = requireNotNull(findMap(compose.activity.window.decorView))
        val map = nativeMap(searchView)
        compose.runOnUiThread { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(26.0, 80.0), 5.75)) }
        compose.waitUntil(10_000) { glass.snapshot?.let { it.image !== searchTexture.image } == true }
        screenshot("search-glass-normal")
        val field = compose.onNodeWithText("Train no. or station").performScrollTo()
        field.performClick().performTextInput(route.trainNumber)
        compose.waitUntil(10_000) { keyboardHeight() > 0 }
        compose.runOnUiThread {
            assertSame("Keyboard entry replaced the native map", searchView, findMap(compose.activity.window.decorView))
            assertTrue("Retained map is detached", searchView.isAttachedToWindow)
        }
        try {
            compose.waitUntil(10_000) {
                keyboardHeight() > 0 && glass.snapshot == null
                    && !searchView.getGlobalVisibleRect(android.graphics.Rect())
            }
        } catch (failure: Throwable) {
            val file = File(compose.activity.getExternalFilesDir(null), "overview-glass/search-keyboard-failure.png")
            file.parentFile?.mkdirs()
            file.outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw AssertionError("IME height ${keyboardHeight()}, native map height ${searchView.height}, glass present ${glass.snapshot != null}", failure)
        }
        assertSame(searchView, findMap(compose.activity.window.decorView))
        field.performImeAction()
        compose.waitUntil(15_000) { keyboardHeight() == 0 && glass.snapshot != null && searchView.height > 0 }
        compose.runOnUiThread {
            assertSame(searchView, findMap(compose.activity.window.decorView))
            assertEquals(26.0, requireNotNull(map.cameraPosition.target).latitude, 0.00002)
            assertEquals(80.0, requireNotNull(map.cameraPosition.target).longitude, 0.00002)
            assertEquals(5.75, map.cameraPosition.zoom, 0.00002)
        }
    }

    private fun assertBounds(map: MapLibreMap, viewport: Rect, bounds: NetworkBounds) {
        val corners = listOf(PointF(viewport.left, viewport.top), PointF(viewport.right, viewport.top),
            PointF(viewport.left, viewport.bottom), PointF(viewport.right, viewport.bottom)).map(map.projection::fromScreenLocation)
        assertEquals(corners.minOf { it.longitude }, bounds.west, 0.00002)
        assertEquals(corners.maxOf { it.longitude }, bounds.east, 0.00002)
        assertEquals(corners.minOf { it.latitude }, bounds.south, 0.00002)
        assertEquals(corners.maxOf { it.latitude }, bounds.north, 0.00002)
    }

    private fun assertFullBackdrop(snapshot: MapGlassController.Snapshot) {
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        assertEquals(view.height.toFloat(), snapshot.bounds.height)
        val content = compose.activity.findViewById<View>(android.R.id.content)
        val location = IntArray(2).also(content::getLocationInWindow)
        assertEquals((location[1] + content.height).toFloat(), snapshot.bounds.bottom, 2f)
        assertTrue(maxOf(snapshot.image.width, snapshot.image.height) <= 960)
    }

    private fun nativeMap(view: MapView): MapLibreMap {
        var map: MapLibreMap? = null
        compose.runOnUiThread { view.getMapAsync { map = it } }
        compose.waitUntil(5_000) { map != null }
        return requireNotNull(map)
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView && !view.isDestroyed) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun hasRoute(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count {
            android.graphics.Color.alpha(it) > 200 && android.graphics.Color.blue(it) > 160
                && android.graphics.Color.blue(it) - android.graphics.Color.red(it) > 70
                && android.graphics.Color.blue(it) - android.graphics.Color.green(it) > 35
        } > 20
    }

    private fun keyboardHeight() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun screenshot(name: String) {
        val view = requireNotNull(findMap(compose.activity.window.decorView))
        val map = nativeMap(view)
        val frames = AtomicInteger()
        val listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
            if (frames.incrementAndGet() < 2) view.post { map.triggerRepaint() }
        }
        compose.runOnUiThread { view.addOnDidFinishRenderingFrameListener(listener); map.triggerRepaint() }
        try {
            compose.waitUntil(15_000) { frames.get() >= 2 }
            compose.waitForIdle()
            val file = File(compose.activity.getExternalFilesDir(null), "overview-glass/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            compose.runOnUiThread { view.removeOnDidFinishRenderingFrameListener(listener) }
        }
    }
}
