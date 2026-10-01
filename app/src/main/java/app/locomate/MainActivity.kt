// Locomate — Android (Kotlin + Jetpack Compose)
// Twin of the iOS app: same design system, same animations, same four screens.
package app.locomate

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.FrameDataApi31
import app.locomate.ui.RootView
import app.locomate.ui.theme.LocomateTheme
import app.locomate.data.CommunityLocationService
import app.locomate.data.CommunityPreferences

class MainActivity : ComponentActivity() {
    private var jankStats: JankStats? = null
    private var launchRevision by mutableIntStateOf(0)
    private var dataRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocomateTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    key(dataRevision) {
                        RootView(launchRevision, onDataReset = { dataRevision++ })
                    }
                }
            }
        }
        if (BuildConfig.DEBUG && Log.isLoggable(JANK_TAG, Log.DEBUG)) {
            jankStats = JankStats.createAndTrack(window) { frame ->
                if (frame.isJank) {
                    val frame31 = frame as? FrameDataApi31
                    Log.d(JANK_TAG,
                        "uiMs=${frame.frameDurationUiNanos / 1_000_000.0} " +
                            "totalMs=${frame31?.frameDurationTotalNanos?.div(1_000_000.0)} " +
                            "overrunMs=${frame31?.frameOverrunNanos?.div(1_000_000.0)} " +
                            "states=${frame.states}")
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRevision++
    }

    override fun onResume() {
        super.onResume()
        launchRevision++
        jankStats?.isTrackingEnabled = true
    }

    override fun onPause() {
        jankStats?.isTrackingEnabled = false
        super.onPause()
    }

    override fun onStop() {
        if (!CommunityPreferences(this).background) CommunityLocationService.stop(this)
        super.onStop()
    }

    private companion object {
        const val JANK_TAG = "LocomateJank"
    }
}
