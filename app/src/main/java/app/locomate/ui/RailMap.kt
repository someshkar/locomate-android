package app.locomate.ui

import app.locomate.ui.theme.LM
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import app.locomate.data.MapLighting
import app.locomate.data.MapImagery
import app.locomate.data.MapDaylight
import app.locomate.data.RailPoint
import app.locomate.ui.theme.LocalAppearance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import java.time.Instant
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import app.locomate.ui.theme.LocalDarkTheme
import android.app.Activity
import app.locomate.BuildConfig
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
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs
import kotlin.math.roundToInt

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
    journeyCamera: JourneyMapController? = null,
    sheetVisibleHeight: Float = 0f,
    visibleViewport: Rect? = null,
    accessibilityViewport: Rect? = visibleViewport,
) {
    // MapLibre initialization can block the UI thread. Draw the sheet and dark
    // map placeholder first, then create the native map on the following frame.
    var initializeMap by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        initializeMap = true
    }
    if (!initializeMap) {
        Box(modifier.fillMaxSize().background(LM.Ground))
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appearance = LocalAppearance.current
    val ground = LM.Ground.toArgb()
    var mapCenter by remember(route?.trainNumber, route?.runDate) { mutableStateOf(RailPoint(23.7, 76.0)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val isDay = when (appearance.lighting) {
        MapLighting.Day -> true
        MapLighting.Night -> false
        MapLighting.Auto -> MapDaylight.isDay(Instant.ofEpochMilli(now), mapCenter.latitude, mapCenter.longitude)
    }
    val styleUrl = if (appearance.imagery == MapImagery.Satellite && BuildConfig.MAP_SATELLITE_STYLE_URL.isNotBlank())
        BuildConfig.MAP_SATELLITE_STYLE_URL else if (isDay) BuildConfig.MAP_DAY_STYLE_URL else BuildConfig.MAP_STYLE_URL
    val currentRotation = rememberUpdatedState(appearance.rotation)
    val mapGlass = LocalMapGlass.current
    val currentBoundsCallback = rememberUpdatedState(onVisibleBounds)
    val currentViewport = rememberUpdatedState(visibleViewport)
    val currentAccessibilityViewport = rememberUpdatedState(accessibilityViewport)
    val currentRoute = rememberUpdatedState(route)
    val currentNetworkTrains = rememberUpdatedState(networkTrains)
    val currentTrainSelection = rememberUpdatedState(onNetworkTrainSelected)
    val currentClusterSelection = rememberUpdatedState(onNetworkClusterSelected)
    val observedIcon = remember(context) { markerIcon(context, 0xFF37C982.toInt()) }
    val estimatedIcon = remember(context) { markerIcon(context, 0xFFFFB84D.toInt()) }
    val previewIcon = remember(context) { markerIcon(context, 0xFFBCA7FF.toInt()) }
    val clusterIcons = remember(context) { mutableMapOf<Int, org.maplibre.android.annotations.Icon>() }
    var styleReady by remember(route?.trainNumber, route?.runDate) { mutableStateOf(false) }
    val hostView = LocalView.current
    val appLightIcons = rememberUpdatedState(!LocalDarkTheme.current)
    SideEffect {
        (hostView.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, hostView).isAppearanceLightStatusBars =
                if (styleReady && (visibleViewport == null || visibleViewport.height > 0f))
                    isDay && appearance.imagery != MapImagery.Satellite else appLightIcons.value
        }
    }
    DisposableEffect(hostView) {
        onDispose { (hostView.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, hostView).isAppearanceLightStatusBars = appLightIcons.value
        } }
    }
    val markerRefs = remember(route?.trainNumber, route?.runDate) { mutableListOf<Marker>() }
    val markerSync = remember(route?.trainNumber, route?.runDate) { MarkerSync() }
    val markerSelections = remember(route?.trainNumber, route?.runDate) { mutableMapOf<Long, List<NetworkTrain>>() }
    val mapActive = remember(route?.trainNumber, route?.runDate) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val styleEpoch = remember(route?.trainNumber, route?.runDate) { java.util.concurrent.atomic.AtomicInteger(0) }
    val routeOverlay = remember(route?.trainNumber, route?.runDate) { RouteOverlaySync() }
    val viewportSync = remember(route?.trainNumber, route?.runDate) { MapViewportSync() }
    val mapView = remember(route?.trainNumber, route?.runDate) {
        MapLibre.getInstance(context)
        object : MapView(context) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                // The GL surface fills the screen for the glass backdrop, but
                // only its exposed strip should receive accessibility focus.
                val viewport = currentAccessibilityViewport.value ?: return
                val bounds = android.graphics.Rect()
                info.getBoundsInScreen(bounds)
                if (!bounds.intersect(
                    bounds.left + viewport.left.roundToInt(),
                    bounds.top + viewport.top.roundToInt(),
                    bounds.left + viewport.right.roundToInt(),
                    bounds.top + viewport.bottom.roundToInt(),
                )) bounds.setEmpty()
                info.setBoundsInScreen(bounds)
            }
        }.apply {
            setBackgroundColor(ground)
            onCreate(null)
            getMapAsync { map ->
                fun publishBounds() {
                    if (!mapActive.get()) return
                    visibleMapBounds(this@apply, map, currentViewport.value)?.let {
                        currentBoundsCallback.value?.invoke(it)
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
                    map.cameraPosition.target?.let { target ->
                        mapCenter = RailPoint(target.latitude.coerceIn(-90.0, 90.0), target.longitude.coerceIn(-180.0, 180.0))
                    }
                    publishBounds()
                    if (map.style != null && markerSync.needsUpdate(
                            currentRoute.value, currentNetworkTrains.value, map.cameraPosition.zoom)) {
                        syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                            observedIcon, estimatedIcon, previewIcon, markerRefs, clusterIcons, context, markerSelections,
                            currentTrainSelection.value != null, currentClusterSelection.value != null)
                    }
                }
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = currentRotation.value
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
                this@apply.doOnLayout {
                    viewportSync.update(this@apply, map, currentViewport.value)
                    publishBounds()
                }

            }
        }
    }

    LaunchedEffect(mapView, styleUrl) {
        val epoch = styleEpoch.incrementAndGet()
        mapView.getMapAsync { map ->
            if (!mapActive.get()) return@getMapAsync
            mapGlass?.detach(mapView)
            map.setStyle(styleUrl) {
                if (!mapActive.get() || styleEpoch.get() != epoch) return@setStyle
                routeOverlay.invalidate()
                markerSync.invalidate()
                routeOverlay.update(map, currentRoute.value)
                syncMarkers(map, currentRoute.value, currentNetworkTrains.value,
                    observedIcon, estimatedIcon, previewIcon, markerRefs, clusterIcons, context, markerSelections,
                    currentTrainSelection.value != null, currentClusterSelection.value != null)
                attribution?.attach(mapView, map)
                journeyCamera?.attach(mapView, map)
                // setStyle preserves the mounted map camera. Never restore an old snapshot:
                // the traveller may have panned or used a camera action during the download.
                styleReady = true
                mapView.post {
                    if (mapActive.get() && styleEpoch.get() == epoch) {
                        mapGlass?.attach(mapView, map, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        visibleMapBounds(mapView, map, currentViewport.value)?.let { currentBoundsCallback.value?.invoke(it) }
                    }
                }
            }
        }
    }
    LaunchedEffect(mapView, appearance.rotation) {
        mapView.getMapAsync { map ->
            if (mapActive.get()) map.uiSettings.isRotateGesturesEnabled = currentRotation.value
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val resized = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                mapView.getMapAsync { map ->
                    if (!mapActive.get()) return@getMapAsync
                    viewportSync.update(mapView, map, currentViewport.value)
                    visibleMapBounds(mapView, map, currentViewport.value)?.let {
                        currentBoundsCallback.value?.invoke(it)
                    }
                }
            }
        }
        mapView.addOnLayoutChangeListener(resized)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); mapGlass?.resume(mapView) }
                Lifecycle.Event.ON_PAUSE -> { mapGlass?.pause(mapView); mapView.onPause() }
                Lifecycle.Event.ON_STOP -> { attribution?.stop(mapView); mapView.onStop() }
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.removeOnLayoutChangeListener(resized)
            mapActive.set(false)
            attribution?.detach(mapView)
            journeyCamera?.detach(mapView)
            mapGlass?.detach(mapView)
            mapView.onDestroy()
        }
    }

    key(route?.trainNumber, route?.runDate) {
        Box(modifier.fillMaxSize().background(LM.Ground)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().alpha(if (styleReady) 1f else 0f),
                update = { view ->
                    val routeSnapshot = currentRoute.value
                    val networkSnapshot = currentNetworkTrains.value
                    // Read before the async native callback so AndroidView observes
                    // viewport-only changes, even when the view size is unchanged.
                    val viewportSnapshot = currentViewport.value
                    mapGlass?.updateContent(view, routeSnapshot to networkSnapshot)
                    journeyCamera?.update(routeSnapshot, sheetVisibleHeight)
                    view.getMapAsync { map ->
                        if (!mapActive.get() || routeSnapshot != currentRoute.value
                            || networkSnapshot != currentNetworkTrains.value
                            || viewportSnapshot != currentViewport.value) return@getMapAsync
                        viewportSync.update(view, map, viewportSnapshot)
                        visibleMapBounds(view, map, viewportSnapshot)?.let {
                            currentBoundsCallback.value?.invoke(it)
                        }
                        if (map.style != null) routeOverlay.update(map, routeSnapshot)
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

/** A refreshed route must update its line without rebuilding the native map. */
private class RouteOverlaySync {
    private var geometry: List<app.locomate.data.RailPoint>? = null
    private var line: Polyline? = null

    fun invalidate() { geometry = null }

    fun update(map: MapLibreMap, route: RoutePreview?) {
        val points = route?.geometry.orEmpty()
        if (points == geometry) return
        geometry = points
        line?.let(map::removePolyline)
        line = if (points.size >= 2) map.addPolyline(PolylineOptions()
            .addAll(points.map { LatLng(it.latitude, it.longitude) })
            .color(android.graphics.Color.rgb(95, 174, 245)).width(5f)) else null
    }
}

/** MapLibre annotations are expensive to recreate while Compose animates the sheet. */
private class MarkerSync {
    private var route: RoutePreview? = null
    private var trains: List<NetworkTrain>? = null
    private var zoom = Double.NaN

    fun invalidate() { route = null; trains = null; zoom = Double.NaN }

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
