package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.ui.theme.LocomateTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/** Painted pixels, rather than effect configuration, prove the dock samples the changing page. */
class DockGlassTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dockReplaysChangedContentAndRepositionsAtLargeTextWithoutDuplicatingActions() {
        var color by mutableStateOf(Color.Red)
        var height by mutableStateOf(600.dp)
        var fontScale by mutableStateOf(1f)
        var tab by mutableStateOf(Tab.Journeys)
        var search by mutableStateOf(false)
        var selected = 0
        var searched = 0
        compose.setContent { LocomateTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // A nonzero window origin detects accidental root-local backdrop alignment.
                Box(Modifier.padding(start = 11.dp, top = 37.dp, end = 7.dp).height(height)) {
                    NavigationScaffold(tab, { tab = it; search = false; selected++ },
                        { search = true; searched++ }, searchActive = search) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(color) }
                    }
                }
            }
        } }
        assertPaintedChannel(0)
        compose.runOnIdle { color = Color.Blue }
        assertPaintedChannel(2)
        compose.runOnIdle { color = Color.Green; height = 440.dp; fontScale = 2f }
        assertPaintedChannel(1)
        compose.onNodeWithContentDescription("Passport").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Search trains").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, selected); assertEquals(1, searched) }
        screenshot("changed-page-large-text")
    }

    @Test fun lightLensPreservesBackdropDetailAndSharpForeground() {
        var phase by mutableStateOf(0f)
        compose.setContent { LocomateTheme {
            NavigationScaffold(Tab.Journeys, {}, {}) {
                Canvas(Modifier.fillMaxSize()) {
                    val stripe = 10.dp.toPx()
                    for (index in -1..(size.width / stripe).toInt() + 1) {
                        drawRect(if (index % 2 == 0) Color.Red else Color.Blue,
                            topLeft = Offset(index * stripe + phase * stripe, 0f), size = Size(stripe, size.height))
                    }
                }
            }
        } }
        val before = dockImage()
        val scan = lowerScan(before)
        screenshot("light-lens-detail-before")
        assertTrue("A sheet-strength blur erased the dock's backdrop detail",
            scan.maxOf(::red) - scan.minOf(::red) > 80)
        assertTrue("The lens must soften stripe transitions",
            scan.count { red(it) > 25 && blue(it) > 25 } >= 3)
        val pixels = IntArray(before.width * before.height)
        before.getPixels(pixels, 0, before.width, 0, 0, before.width, before.height)
        assertTrue("Search foreground was blurred or lost", pixels.count {
            red(it) > 230 && green(it) > 230 && blue(it) > 230
        } > 10)
        compose.runOnIdle { phase = 0.5f }
        val after = lowerScan(dockImage())
        assertTrue("The lens kept stale page drawing commands", scan.zip(after).count {
            kotlin.math.abs(red(it.first) - red(it.second)) > 35
        } > scan.size / 4)
        screenshot("light-lens-detail")
    }

    private fun assertPaintedChannel(channel: Int) {
        compose.waitUntil(5_000) {
            val bitmap = dockImage()
            val pixel = bitmap.getPixel(bitmap.width / 2, (bitmap.height * 0.8f).roundToInt())
            val channels = listOf(red(pixel), green(pixel), blue(pixel))
            channels[channel] > 120 && channels.filterIndexed { index, _ -> index != channel }.all { it < 55 }
        }
    }

    private fun dockImage(): Bitmap {
        compose.waitForIdle()
        return compose.onNodeWithContentDescription("Search trains").captureToImage().asAndroidBitmap()
    }

    private fun lowerScan(bitmap: Bitmap): List<Int> =
        ((bitmap.width * 0.22f).roundToInt()..(bitmap.width * 0.78f).roundToInt()).map {
            bitmap.getPixel(it, (bitmap.height * 0.77f).roundToInt())
        }

    private fun red(pixel: Int) = android.graphics.Color.red(pixel)
    private fun green(pixel: Int) = android.graphics.Color.green(pixel)
    private fun blue(pixel: Int) = android.graphics.Color.blue(pixel)
    private fun screenshot(name: String) {
        val file = File(compose.activity.getExternalFilesDir(null), "dock-glass/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
