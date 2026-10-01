package app.locomate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.doOnLayout
import app.locomate.data.RoutePreview
import app.locomate.data.NetworkBounds
import app.locomate.data.NetworkTrain
import app.locomate.data.NetworkClusters
import app.locomate.data.NetworkSelection
import app.locomate.data.journeyReference
import app.locomate.data.RailGeometry
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs

/** Native map surface. The sample corridor is shown only in explicit preview mode. */
@Composable
fun RailMap(
    route: RoutePreview?,
    modifier: Modifier = Modifier,
    networkTrains: List<NetworkTrain> = emptyList(),
    onVisibleBounds: ((NetworkBounds) -> Unit)? = null,
    attribution: MapAttributionController? = null,
    onNetworkTrainSelected: ((NetworkTrain) -> Unit)? = null,
    onNetworkClusterSelected: ((List<NetworkTrain>) -> Unit)? = null,
) {
    // MapLibre initialization can block the UI thread. Draw the sheet and dark
    // map placeholder first, then create the native map on the following frame.
    var initializeMap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        initializeMap = true
    }
    if (!initializeMap) {
        Box(modifier.fillMaxSize().background(Color(0xFF060708)))
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBoundsCallback = rememberUpdatedState(onVisibleBounds)
    val currentRoute = rememberUpdatedState(route)
    val currentNetworkTrains = rememberUpdatedState(networkTrains)
    val currentTrainSelection = rememberUpdatedState(onNetworkTrainSelected)
    val currentClusterSelection = rememberUpdatedState(onNetworkClusterSelected)
    val observedIcon = remember(context) { markerIcon(context, 0xFF37C982.toInt()) }
    val estimatedIcon = remember(context) { markerIcon(context, 0xFFFFB84D.toInt()) }
    val previewIcon = remember(context) { markerIcon(context, 0xFFBCA7FF.toInt()) }
    val clusterIcons = remember(context) { mutableMapOf<Int, org.maplibre.android.annotations.Icon>() }
    var styleReady by remember(route?.trainNumber, route?.runDate) { mutableStateOf(false) }
    val markerRefs = remember(route?.trainNumber, route?.runDate) { mutableListOf<Marker>() }
    val markerSync = remember(route?.trainNumber, route?.runDate) { MarkerSync() }
    val markerSelections = remember(route?.trainNumber, route?.runDate) { mutableMapOf<Long, List<NetworkTrain>>() }
    val mapActive = remember(route?.trainNumber, route?.runDate) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val mapView = remember(route?.trainNumber, route?.runDate) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(6, 7, 8))
            onCreate(null)
            getMapAsync { map ->
                fun publishBounds() {
                    if (!mapActive.get()) return
                    val bounds = map.projection.visibleRegion.latLngBounds
                    if (bounds.longitudeSpan > 0 && bounds.latitudeSpan > 0) {
                        currentBoundsCallback.value?.invoke(NetworkBounds(
                            bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth))
                    }
                }
                map.setOnInfoWindowClickListener { marker ->
                    if (mapActive.get()) {
                        when (val selection = NetworkSelection.resolve(markerSelections[marker.id].orEmpty(), currentNetworkTrains.value)) {
                            is NetworkSelection.Journey -> currentTrainSelection.value?.invoke(selection.train)
                            is NetworkSelection.Inspect -> currentClusterSelection.value?.invoke(selection.trains)
                            null -> Unit
                        }
                    }
                    false // Close the native info window after its explicit action.
                }
                map.addOnCameraIdleListener {
                    publishBounds()
                    if (map.style != null && markerSync.needsUpdate(
                            currentRoute.value, currentNetworkTrains.value, map.cameraPosition.zoom)) {
                        syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                            observedIcon, estimatedIcon, previewIcon, markerRefs, clusterIcons, context, markerSelections,
                            currentTrainSelection.value != null, currentClusterSelection.value != null)
                    }
                }
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isAttributionEnabled = attribution == null
                val points = route?.geometry.orEmpty()
                val center = if (points.isEmpty()) LatLng(23.7, 76.0) else {
                    val south = points.minOf { it.latitude }
                    val north = points.maxOf { it.latitude }
                    LatLng(south - (north - south) * 0.15 - 1.0,
                        (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2)
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(center)
                    .zoom(if (points.isEmpty()) 4.55 else if (points.maxOf { it.latitude } - points.minOf { it.latitude } > 11) 4.0 else 4.3)
                    .build()
                // Rail data and the accessible list must not depend on a basemap download.
                this@apply.doOnLayout { publishBounds() }
                map.setStyle("https://tiles.openfreemap.org/styles/dark") {
                    if (!mapActive.get()) return@setStyle
                    styleReady = true
                    attribution?.attach(this@apply, map)
                    if (points.size >= 2) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(points.map { LatLng(it.latitude, it.longitude) })
                                .color(android.graphics.Color.rgb(95, 174, 245))
                                .width(5f)
                        )
                    }
                    if (markerSync.needsUpdate(currentRoute.value, currentNetworkTrains.value,
                            map.cameraPosition.zoom)) {
                        syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                            observedIcon, estimatedIcon, previewIcon, markerRefs, clusterIcons, context, markerSelections,
                            currentTrainSelection.value != null, currentClusterSelection.value != null)
                    }
                    this@apply.post { publishBounds() }
                }
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> { attribution?.stop(mapView); mapView.onStop() }
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapActive.set(false)
            attribution?.detach(mapView)
            mapView.onDestroy()
        }
    }

    key(route?.trainNumber, route?.runDate) {
        Box(modifier.fillMaxSize().background(Color(0xFF060708))) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().alpha(if (styleReady) 1f else 0f),
                update = { view ->
                    val routeSnapshot = currentRoute.value
                    val networkSnapshot = currentNetworkTrains.value
                    view.getMapAsync { map ->
                        if (map.style != null && markerSync.needsUpdate(
                                routeSnapshot, networkSnapshot, map.cameraPosition.zoom)) {
                            syncMarkers(map, routeSnapshot, networkSnapshot,
                                observedIcon, estimatedIcon, previewIcon, markerRefs, clusterIcons, context, markerSelections,
                            currentTrainSelection.value != null, currentClusterSelection.value != null)
                        }
                    }
                })
        }
    }
}

/** MapLibre annotations are expensive to recreate while Compose animates the sheet. */
private class MarkerSync {
    private var route: RoutePreview? = null
    private var trains: List<NetworkTrain>? = null
    private var zoom = Double.NaN

    fun needsUpdate(nextRoute: RoutePreview?, nextTrains: List<NetworkTrain>, nextZoom: Double): Boolean {
        if (route === nextRoute && trains == nextTrains && abs(zoom - nextZoom) < 0.05) return false
        route = nextRoute
        trains = nextTrains
        zoom = nextZoom
        return true
    }
}

private fun syncMarkers(map: MapLibreMap, route: RoutePreview?, networkTrains: List<NetworkTrain>,
                        observedIcon: org.maplibre.android.annotations.Icon,
                        estimatedIcon: org.maplibre.android.annotations.Icon,
                        previewIcon: org.maplibre.android.annotations.Icon,
                        refs: MutableList<Marker>,
                        clusterIcons: MutableMap<Int, org.maplibre.android.annotations.Icon>,
                        context: android.content.Context,
                        selections: MutableMap<Long, List<NetworkTrain>>,
                        journeyAction: Boolean,
                        clusterAction: Boolean) {
    selections.clear()
    refs.forEach { map.removeMarker(it) }
    refs.clear()
    route?.positionProgress?.let { progress ->
        RailGeometry.pointAtProgress(route.geometry, progress)?.let { point ->
            refs += map.addMarker(MarkerOptions()
                .position(LatLng(point.latitude, point.longitude))
                .title("${route.trainNumber} · ${route.displayName}")
                .snippet(route.positionStatus ?: route.statusLabel)
                .icon(if (route.isPreview) previewIcon
                    else if (route.statusLabel.startsWith("STALE")) estimatedIcon else observedIcon))
        }
    }
    NetworkClusters.forZoom(networkTrains, map.cameraPosition.zoom).forEach { group ->
        val single = group.single
        val marker = map.addMarker(MarkerOptions()
            .position(LatLng(group.coordinate.latitude, group.coordinate.longitude))
            .title(if (single != null) "${single.trainNumber} · ${single.name}"
                else "${group.count} trains in this area")
            .snippet(if (single != null) "${single.positionKind} · ${single.source} · ${single.observedAt}\nOrigin date ${single.originDate}" +
                (if (journeyAction && single.journeyReference() != null) "\nOpen journey →" else "")
                else if (clusterAction) "Inspect these trains →" else "Zoom in to inspect individual services")
            .icon(if (single != null) {
                if (single.positionKind == "observed") observedIcon else estimatedIcon
            } else clusterIcons.getOrPut(group.count) { markerIcon(context, 0xFF009DFA.toInt(), group.count) }))
        refs += marker
        selections[marker.id] = group.trains
    }
}

private fun markerIcon(context: android.content.Context, color: Int, count: Int? = null): org.maplibre.android.annotations.Icon {
    val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = color
    paint.alpha = 65
    canvas.drawCircle(36f, 36f, 33f, paint)
    paint.alpha = 255
    paint.color = android.graphics.Color.rgb(10, 14, 20)
    canvas.drawCircle(36f, 36f, 19f, paint)
    paint.color = color
    canvas.drawCircle(36f, 36f, 15f, paint)
    paint.color = android.graphics.Color.WHITE
    if (count == null) canvas.drawCircle(36f, 36f, 4.5f, paint)
    else {
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (count < 100) 27f else 22f
        paint.isFakeBoldText = true
        canvas.drawText(count.toString(), 36f, 45f, paint)
    }
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}
