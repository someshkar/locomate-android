package app.locomate.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.locomate.data.RoutePreview
import app.locomate.data.NetworkBounds
import app.locomate.data.NetworkTrain
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

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
    val observedIcon = remember(context) { markerIcon(context, 0xFF37C982.toInt()) }
    val estimatedIcon = remember(context) { markerIcon(context, 0xFFFFB84D.toInt()) }
    val mapView = remember(route?.trainNumber) {
        MapLibre.getInstance(context)
        MapView(context).apply {
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
                    val points = route?.geometry.orEmpty()
                    val center = if (points.isEmpty()) LatLng(23.7, 76.0) else LatLng(
                        (points.minOf { it.latitude } + points.maxOf { it.latitude }) / 2,
                        (points.minOf { it.longitude } + points.maxOf { it.longitude }) / 2,
                    )
                    map.cameraPosition = CameraPosition.Builder()
                        .target(center)
                        .zoom(4.55)
                        .build()
                    if (points.size >= 2) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(points.map { LatLng(it.latitude, it.longitude) })
                                .color(android.graphics.Color.rgb(95, 174, 245))
                                .width(5f)
                        )
                    }
                    this@apply.post { publishBounds() }
                }
            }
        }
    }
    val markerRefs = remember(mapView) { mutableListOf<Marker>() }

    DisposableEffect(lifecycle, mapView) {
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

    key(route?.trainNumber) {
        AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize(), update = { view ->
            view.getMapAsync { map ->
                markerRefs.forEach { map.removeMarker(it) }
                markerRefs.clear()
                networkTrains.forEach { train ->
                    markerRefs += map.addMarker(MarkerOptions()
                        .position(LatLng(train.coordinate.latitude, train.coordinate.longitude))
                        .title("${train.trainNumber} · ${train.name}")
                        .snippet("${train.positionKind} · ${train.source} · ${train.observedAt}")
                        .icon(if (train.positionKind == "observed") observedIcon else estimatedIcon))
                }
            }
        })
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
