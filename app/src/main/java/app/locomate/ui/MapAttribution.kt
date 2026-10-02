package app.locomate.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/** Uses MapLibre's real source credits, with a reachable Compose target instead of its 21dp icon. */
class MapAttributionController {
    var available by mutableStateOf(false)
        private set
    private var owner: MapView? = null
    private var manager: AttributionDialogManager? = null

    internal fun attach(view: MapView, map: MapLibreMap) {
        manager?.onStop()
        owner = view
        manager = AttributionDialogManager(view.context, map)
        available = true
    }

    fun show() { owner?.let { manager?.onClick(it) } }

    internal fun stop(view: MapView) { if (owner === view) manager?.onStop() }

    internal fun detach(view: MapView) {
        if (owner !== view) return
        manager?.onStop()
        manager = null
        owner = null
        available = false
    }
}

@Composable
fun MapAttributionButton(controller: MapAttributionController) {
    TextButton(onClick = controller::show, enabled = controller.available,
        modifier = Modifier.heightIn(min = 48.dp)) { Text("Map attribution") }
}
