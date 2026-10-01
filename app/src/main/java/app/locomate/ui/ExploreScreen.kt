package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.RoutePreview
import app.locomate.data.RailGateway
import app.locomate.data.NetworkBounds
import app.locomate.data.NetworkSnapshot
import app.locomate.data.NetworkFreshness
import app.locomate.data.JourneyAlertLink
import app.locomate.data.NetworkTrain
import app.locomate.data.journeyReference
import app.locomate.data.datedIdentity
import app.locomate.ui.theme.LM
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation

private sealed interface NetworkListScope {
    data object All : NetworkListScope
    data class Cluster(val identities: Set<String>) : NetworkListScope
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(route: RoutePreview?, gateway: RailGateway, bottomInset: Dp = 115.dp,
                  onOpenJourney: ((JourneyAlertLink) -> Unit)? = null) {
    var bounds by remember { mutableStateOf<NetworkBounds?>(null) }
    var prepared by remember(gateway) { mutableStateOf<NetworkFreshness.Prepared?>(null) }
    val snapshot = prepared?.snapshot
    var error by remember { mutableStateOf<String?>(null) }
    var listing by remember(gateway) { mutableStateOf<NetworkListScope?>(null) }
    val mapAttribution = remember { MapAttributionController() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var networkView by remember(gateway) { mutableStateOf(NetworkFreshness.View(emptyList(), false, null)) }
    var requestEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(gateway, bounds, lifecycle) {
        val visible = bounds ?: return@LaunchedEffect
        if (!gateway.configured) return@LaunchedEffect
        val epoch = ++requestEpoch
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(400)
            while (isActive) {
                try {
                    val response = gateway.network(visible)
                    val parsed = withContext(Dispatchers.Default) { NetworkFreshness.prepare(response) }
                    currentCoroutineContext().ensureActive()
                    if (epoch == requestEpoch) {
                        prepared = parsed
                        networkView = parsed.at(System.currentTimeMillis())
                        error = null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (epoch == requestEpoch) error = failure.message ?: "The network feed is unavailable."
                }
                delay(60_000)
            }
        }
    }
    LaunchedEffect(prepared, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = System.currentTimeMillis()
                val view = prepared?.at(now) ?: NetworkFreshness.View(emptyList(), false, null)
                networkView = view
                val next = view.nextChangeAtMillis ?: awaitCancellation()
                // Coalesce closely spaced marker expiries; no parsing or filtering every frame/second.
                delay((next - now).coerceAtLeast(1_000))
            }
        }
    }
    val trains = networkView.trains
    val expired = networkView.expired
    fun openCurrentJourney(train: NetworkTrain) {
        // Check again at the action boundary; a UI frame may precede the expiry timer.
        val current = prepared?.at(System.currentTimeMillis()) ?: return
        networkView = current
        val reference = train.journeyReference() ?: return
        if (train in current.trains) {
            listing = null
            onOpenJourney?.invoke(reference)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080B12))) {
        RailMap(if (gateway.configured) null else route,
            networkTrains = trains,
            onVisibleBounds = if (gateway.configured) ({ bounds = it }) else null,
            attribution = mapAttribution,
            onNetworkTrainSelected = if (onOpenJourney == null) null else ::openCurrentJourney,
            onNetworkClusterSelected = { members ->
                val current = prepared?.at(System.currentTimeMillis())?.trains.orEmpty().toHashSet()
                val valid = members.filter { it in current }
                if (valid.isNotEmpty()) listing = NetworkListScope.Cluster(valid.mapNotNull { it.datedIdentity() }.toSet())
            })
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.68f))
                )
            )
        )
        Surface(
            color = Color(0xE51A1C22),
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            shadowElevation = 18.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 18.dp, vertical = 17.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                Text("EXPLORE", color = LM.Ink3, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.padding(top = 5.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("The rail network", color = LM.Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() })
                    Text(if (gateway.configured) "GATEWAY" else "PREVIEW",
                        color = if (gateway.configured) LM.Accent else Color(0xFFBCA7FF),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(
            color = Color(0xF51A1C22),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 24.dp,
            modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.65f)
                .padding(horizontal = 20.dp).padding(bottom = bottomInset)
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp)) {
                Text(if (gateway.configured) "Network in view" else "India by rail",
                    color = LM.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (gateway.configured && snapshot != null) {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.5f) 1 else 3) {
                        NetworkStat("IN VIEW", trains.size, Modifier.weight(1f))
                        NetworkStat("OBSERVED", trains.count { it.positionKind == "observed" }, Modifier.weight(1f))
                        NetworkStat("PREDICTED", trains.count { it.positionKind == "predicted" }, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text(if (gateway.configured) {
                    when {
                        expired -> "Positions expired. ${error ?: "Refreshing the network…"}"
                        error != null -> "Refresh unavailable. Valid positions remain until they expire. ${error.orEmpty()}"
                        snapshot != null -> "${trains.size} fresh gateway train markers in this map view. Positions carry their own source and observation time."
                        else -> "Loading train positions for this map view…"
                    }
                } else "Explore a historical route sample. Live network trains appear when a rail gateway is configured.",
                    color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                if (gateway.configured) TextButton(onClick = { listing = NetworkListScope.All }, enabled = snapshot != null && !expired,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Trains in view") }
                MapAttributionButton(mapAttribution)
            }
        }
    }
    listing?.let { scope ->
        val members = when (scope) {
            NetworkListScope.All -> trains
            is NetworkListScope.Cluster -> trains.filter { it.datedIdentity() in scope.identities }
        }
        snapshot?.copy(trains = members)?.let { current ->
            NetworkTrainListDialog(current, onDismiss = { listing = null },
                onOpenTrain = if (onOpenJourney != null) ::openCurrentJourney else null)
        }
    }
}

@Composable
private fun NetworkStat(label: String, value: Int, modifier: Modifier) {
    Surface(color = LM.Raised, shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(value.toString(), color = LM.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = LM.Ink3, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp)
        }
    }
}


/** Text equivalent of the native map's GL annotations, including their source and freshness. */
@Composable
internal fun NetworkTrainListDialog(snapshot: NetworkSnapshot, onDismiss: () -> Unit,
                                    onOpenTrain: ((NetworkTrain) -> Unit)? = null) {
    val rows = remember(snapshot.trains) { snapshot.trains.filter { it.datedIdentity() != null }.distinctBy { it.datedIdentity() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trains in view") },
        text = {
            LazyColumn {
                item {
                    Text("Snapshot ${networkTime(snapshot.generatedAt)}. Positions may change after this snapshot.")
                    Spacer(Modifier.height(12.dp))
                }
                if (rows.isEmpty()) item { Text("No current trains in this list.") }
                items(rows, key = { requireNotNull(it.datedIdentity()) }) { train ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
                        Text("${train.trainNumber} · ${train.name}", fontWeight = FontWeight.SemiBold)
                        Text("Origin date ${train.originDate}")
                        Text("${train.positionKind.replaceFirstChar { it.uppercase() }} position · Source: ${train.source}")
                        Text("Observed ${networkTime(train.observedAt)}")
                        Text(train.delayMinutes?.let { if (it == 0) "On time" else if (it < 0) "${-it} minutes early" else "$it minutes late" } ?: "Delay unavailable")
                        Text("Latitude ${train.coordinate.latitude}, longitude ${train.coordinate.longitude}")
                        if (onOpenTrain != null) {
                            val reference = train.journeyReference()
                            TextButton(onClick = { onOpenTrain(train) }, enabled = reference != null,
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = "Open journey for train ${train.trainNumber} on ${train.originDate}"
                                }) { Text(if (reference != null) "Open ${reference.trainNumber} · ${reference.serviceDate}" else "Journey unavailable") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close train list") } })
}

private fun networkTime(value: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm 'IST'", java.util.Locale.ENGLISH)
        .withZone(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.Instant.parse(value))
}.getOrDefault(value)
