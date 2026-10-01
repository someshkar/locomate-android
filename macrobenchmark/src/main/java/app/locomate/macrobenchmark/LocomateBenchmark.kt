package app.locomate.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private companion object {
        const val PACKAGE_NAME = "app.locomate"
    }
}
