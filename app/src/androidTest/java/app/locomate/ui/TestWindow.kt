package app.locomate.ui

import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import android.view.WindowManager

/** Match the edge-to-edge window used by MainActivity on Android 12 and newer. */
internal fun configureEdgeToEdgeTestWindow(activity: ComponentActivity) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
