package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.RouteStop
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.LocomateTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TimelinePlatformTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun platformStaysWithItsStationAndQualifiesSavedValuesAtBothTextSizes() {
        var scale by mutableStateOf(1f)
        var preview by mutableStateOf(false)
        var platform by mutableStateOf<String?>("4A")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                LocomateTheme {
                    Column(Modifier.fillMaxSize().background(LM.Ground).statusBarsPadding().padding(24.dp)) {
                        TimelineStop(RouteStop("KOTA", "Kota Junction", "21:48", "21:53", platform = platform),
                            preview = preview, stale = true, first = true)
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { scale = fontScale }
            compose.onNodeWithText("Kota Junction", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Platform", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("4A", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Last known", useUnmergedTree = true).assertIsDisplayed()
            screenshot("platform-${(fontScale * 100).toInt()}")
        }
        compose.runOnIdle { preview = true }
        compose.onNodeWithText("Platform", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { preview = false; platform = " " }
        compose.onNodeWithText("Platform", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { platform = null }
        compose.onNodeWithText("Platform", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "platform/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
