package app.locomate.macrobenchmark

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class LocomateBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test
    fun coldStart() = benchmark.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun searchSheetOverMap() = benchmark.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = null,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            assertNotNull("Search control was not visible",
                device.wait(Until.findObject(By.desc("Search trains")), 5_000))
            // Let the native map finish its startup work outside the measured interaction.
            Thread.sleep(2_000)
        },
    ) {
        val search = device.wait(Until.findObject(By.desc("Search trains")), 5_000)
        assertNotNull("Search control was not visible", search)
        search.click()
        val close = device.wait(Until.findObject(By.desc("Close search")), 5_000)
        assertNotNull("Search sheet did not open", close)
        Thread.sleep(600) // Include the complete opening transition in the frame trace.
        close.click()
        Thread.sleep(600) // Include the complete closing transition.
        assertNotNull("Search sheet did not close", device.wait(Until.findObject(By.desc("Search trains")), 5_000))
    }

    /** Network loading is setup; the trace covers actual sheet drags and content scrolling. */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun journeySheetOverMap() {
        val arguments = InstrumentationRegistry.getArguments()
        val dryRun = arguments.getString("androidx.benchmark.dryRunMode.enable") == "true"
        val journeyUrl = arguments.getString("locomate.journeyUrl")
        if (!dryRun) {
            require(Build.HARDWARE !in setOf("ranchu", "goldfish") &&
                !Build.FINGERPRINT.contains("generic")) { "Measure performance on a physical device; use dryRunMode only to verify interaction." }
            require(journeyUrl != null && validJourneyUrl(journeyUrl)) {
                "Supply locomate.journeyUrl=locomate://journeys/TRAIN?date=YYYY-MM-DD for an authorized current gateway run."
            }
        }
        benchmark.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.DEFAULT,
            startupMode = null,
            iterations = 10,
            setupBlock = {
                pressHome()
                if (journeyUrl != null) {
                    require(validJourneyUrl(journeyUrl)) { "Invalid dated journey URL" }
                    startActivityAndWait(Intent(Intent.ACTION_VIEW, Uri.parse(journeyUrl)).setPackage(PACKAGE_NAME))
                } else startActivityAndWait()
                // A loaded journey enables save; an empty/error page must not produce a benchmark.
                assertNotNull("A loaded journey is required",
                    device.wait(Until.findObject(By.desc("Save journey").enabled(true)
                        .pkg(PACKAGE_NAME)), 20_000)
                        ?: device.findObject(By.desc("Remove saved journey").enabled(true).pkg(PACKAGE_NAME)))
                if (!dryRun) {
                    assertTrue("Preview data is only permitted in an interaction dry run",
                        !device.hasObject(By.textContains("ROUTE PREVIEW")))
                    assertTrue("A cached run cannot establish the production map workload",
                        !device.hasObject(By.textContains("STALE")))
                    assertNotNull("Native map style must load before measuring the map workload",
                        device.wait(Until.findObject(By.text("Map attribution").enabled(true)), 20_000))
                }
                device.findObject(By.desc("Collapse journey details"))?.click()
                assertNotNull("Journey sheet must start collapsed",
                    device.wait(Until.findObject(By.desc("Expand journey details")), 5_000))
                // Settle initial map work outside the measured region. Tile caches are retained.
                Thread.sleep(2_000)
            },
        ) {
            val handle = device.wait(Until.findObject(By.desc("Expand journey details")), 5_000)!!
            val bounds = handle.visibleBounds
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(),
                (device.displayHeight * 0.15).toInt(), 45)
            assertNotNull("Drag did not expand the sheet",
                device.wait(Until.findObject(By.desc("Collapse journey details")), 5_000))
            val content = device.wait(Until.findObject(By.scrollable(true).pkg(PACKAGE_NAME)), 5_000)
            assertNotNull("Journey content must be scrollable", content)
            val dock = device.findObject(By.desc("Passport"))
            assertNotNull("Navigation dock must be visible", dock)
            // The list extends behind the floating dock. Start gestures in its
            // unobscured region instead of dragging a navigation button.
            content.setGestureMargins(24, 24, 24,
                maxOf(24, content.visibleBounds.bottom - dock.visibleBounds.top + 24))
            val beforeScroll = content.findObjects(By.text(Pattern.compile(".+")))
                .map { it.text to it.visibleBounds }
            // scroll() reports whether more scrolling remains, not whether it
            // moved. Verify the visible content instead, including at the end.
            content.scroll(Direction.DOWN, 0.7f)
            val afterScroll = content.findObjects(By.text(Pattern.compile(".+")))
                .map { it.text to it.visibleBounds }
            assertTrue("Loaded journey did not scroll", beforeScroll != afterScroll)
            content.scroll(Direction.DOWN, 0.7f)
            content.scroll(Direction.UP, 0.7f)
            content.scroll(Direction.UP, 0.7f)
            val expandedHandle = device.findObject(By.desc("Collapse journey details")).visibleBounds
            device.swipe(expandedHandle.centerX(), expandedHandle.centerY(), expandedHandle.centerX(),
                (device.displayHeight * 0.65).toInt(), 45)
            assertNotNull("Drag did not collapse the sheet",
                device.wait(Until.findObject(By.desc("Expand journey details")), 5_000))
        }
    }

    private fun validJourneyUrl(value: String): Boolean = runCatching {
        val match = Regex("locomate://journeys/([0-9]{4,6})\\?date=([0-9]{4}-[0-9]{2}-[0-9]{2})").matchEntire(value)
            ?: return false
        java.time.LocalDate.parse(match.groupValues[2]).toString() == match.groupValues[2]
    }.getOrDefault(false)

    private companion object {
        const val PACKAGE_NAME = "app.locomate"
    }
}
