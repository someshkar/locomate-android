package app.locomate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.locomate.data.RoutePreview
import app.locomate.data.NetworkBounds
import app.locomate.data.NetworkTrain
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

/** Native map surface. The sample corridor is shown only in explicit preview mode. */
@Composable
fun RailMap(
    route: RoutePreview?,
    modifier: Modifier = Modifier,
    networkTrains: List<NetworkTrain> = emptyList(),
    onVisibleBounds: ((NetworkBounds) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentBoundsCallback = rememberUpdatedState(onVisibleBounds)
    val currentRoute = rememberUpdatedState(route)
    val currentNetworkTrains = rememberUpdatedState(networkTrains)
    val observedIcon = remember(context) { markerIcon(context, 0xFF37C982.toInt()) }
    val estimatedIcon = remember(context) { markerIcon(context, 0xFFFFB84D.toInt()) }
    val previewIcon = remember(context) { markerIcon(context, 0xFFBCA7FF.toInt()) }
    var styleReady by remember(route?.trainNumber, route?.runDate) { mutableStateOf(false) }
    val markerRefs = remember(route?.trainNumber, route?.runDate) { mutableListOf<Marker>() }
    val mapView = remember(route?.trainNumber, route?.runDate) {
        MapLibre.getInstance(context)
        MapView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(6, 7, 8))
            onCreate(null)
            getMapAsync { map ->
                fun publishBounds() {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    if (bounds.longitudeSpan > 0 && bounds.latitudeSpan > 0) {
                        currentBoundsCallback.value?.invoke(NetworkBounds(
                            bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth))
                    }
                }
                map.addOnCameraIdleListener { publishBounds() }
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isAttributionEnabled = true
                map.setStyle("https://tiles.openfreemap.org/styles/dark") {
                    styleReady = true
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
                    if (points.size >= 2) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(points.map { LatLng(it.latitude, it.longitude) })
                                .color(android.graphics.Color.rgb(95, 174, 245))
                                .width(5f)
                        )
                    }
                    syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                        observedIcon, estimatedIcon, previewIcon, markerRefs)
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
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    key(route?.trainNumber, route?.runDate) {
        Box(modifier.fillMaxSize().background(Color(0xFF060708))) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().alpha(if (styleReady) 1f else 0f),
                update = { view ->
                    view.getMapAsync { map ->
                        if (map.style != null) syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                            observedIcon, estimatedIcon, previewIcon, markerRefs)
                    }
                })
        }
    }
}

private fun syncMarkers(map: MapLibreMap, route: RoutePreview?, networkTrains: List<NetworkTrain>,
                        observedIcon: org.maplibre.android.annotations.Icon,
                        estimatedIcon: org.maplibre.android.annotations.Icon,
                        previewIcon: org.maplibre.android.annotations.Icon,
                        refs: MutableList<Marker>) {
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
    networkTrains.forEach { train ->
        refs += map.addMarker(MarkerOptions()
            .position(LatLng(train.coordinate.latitude, train.coordinate.longitude))
            .title("${train.trainNumber} · ${train.name}")
            .snippet("${train.positionKind} · ${train.source} · ${train.observedAt}")
            .icon(if (train.positionKind == "observed") observedIcon else estimatedIcon))
    }
}

private fun markerIcon(context: android.content.Context, color: Int): org.maplibre.android.annotations.Icon {
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
    canvas.drawCircle(36f, 36f, 4.5f, paint)
    return IconFactory.getInstance(context).fromBitmap(bitmap)
}
