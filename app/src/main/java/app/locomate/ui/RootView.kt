package app.locomate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.PreviewRoutes
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.data.SavedJourney
import app.locomate.data.SavedJourneyStore
import app.locomate.data.TrainSearchResult
import app.locomate.ui.theme.LM
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

enum class Tab { Journeys, Explore, Passport }

@Composable
fun RootView() {
    val context = LocalContext.current
    val passport = remember { SavedJourneyStore(context) }
    val gateway = remember { RailGateway(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val routes = remember { PreviewRoutes.load(context) }
    var tab by rememberSaveable { mutableStateOf(Tab.Journeys) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selectedNumber by rememberSaveable { mutableStateOf("12951") }
    var liveRoute by remember { mutableStateOf<RoutePreview?>(null) }
    var selectedPreview by remember { mutableStateOf<RoutePreview?>(null) }
    var journeyMessage by remember { mutableStateOf<String?>(null) }
    var savedJourneys by remember { mutableStateOf(passport.load()) }
    val selectedRoute = if (gateway.configured) selectedPreview ?: liveRoute
        else routes.firstOrNull { it.trainNumber == selectedNumber } ?: routes.firstOrNull()

    fun updateSaved(next: List<SavedJourney>) {
        savedJourneys = next
        passport.save(next)
    }

    LaunchedEffect(gateway, liveRoute?.runId) {
        val run = liveRoute ?: return@LaunchedEffect
        val date = run.runDate ?: return@LaunchedEffect
        while (isActive) {
            delay(60_000)
            try {
                val refreshed = gateway.journey(run.trainNumber, date)
                if (liveRoute?.runId == run.runId) {
                    liveRoute = refreshed
                    journeyMessage = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (liveRoute?.runId == run.runId) {
                    liveRoute = liveRoute?.copy(
                        statusLabel = "STALE · LAST KNOWN",
                        sourceDetail = "Live refresh unavailable. Last saved rail data is shown."
                    )
                    journeyMessage = error.message
                }
            }
        }
    }

    BackHandler(enabled = searchOpen || tab != Tab.Journeys) {
        if (searchOpen) searchOpen = false else tab = Tab.Journeys
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = tab, label = "primary tab") { current ->
            when (current) {
                Tab.Journeys -> JourneyScreen(
                    route = selectedRoute,
                    productionMode = gateway.configured && selectedPreview == null,
                    saved = selectedRoute?.let { SavedJourney.from(it).key in savedJourneys.map(SavedJourney::key) } ?: false,
                    message = journeyMessage,
                    onSave = {
                        selectedRoute?.let { route ->
                            val entry = SavedJourney.from(route)
                            updateSaved(if (savedJourneys.any { it.key == entry.key })
                                savedJourneys.filterNot { it.key == entry.key }
                                else savedJourneys + entry)
                        }
                    }
                )
                Tab.Explore -> ExploreScreen(selectedRoute, gateway)
                Tab.Passport -> PassportScreen(
                    savedRoutes = savedJourneys,
                    onRemove = { key -> updateSaved(savedJourneys.filterNot { it.key == key }) },
                    onOpen = { saved ->
                        tab = Tab.Journeys
                        if (saved.preview) {
                            selectedNumber = saved.trainNumber
                            selectedPreview = routes.firstOrNull { it.trainNumber == saved.trainNumber }
                        } else if (!saved.preview && gateway.configured && saved.originDate != null) {
                            selectedPreview = null
                            liveRoute = null
                            journeyMessage = "Loading ${saved.trainNumber} for ${saved.originDate}…"
                            scope.launch {
                                try {
                                    liveRoute = gateway.journey(saved.trainNumber, saved.originDate)
                                    journeyMessage = null
                                } catch (error: Exception) {
                                    journeyMessage = error.message ?: "This saved run is unavailable."
                                }
                            }
                        }
                    }
                )
            }
        }
        CapsuleNavBar(
            tab = tab,
            onTab = { tab = it },
            onSearch = { searchOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }

    if (searchOpen) {
        SearchSheet(
            routes = routes,
            gateway = gateway,
            onClose = { searchOpen = false },
            onSelect = { number ->
                selectedNumber = number
                searchOpen = false
                tab = Tab.Journeys
            },
            onSelectLive = { train: TrainSearchResult, date: String ->
                searchOpen = false
                tab = Tab.Journeys
                selectedPreview = null
                liveRoute = null
                journeyMessage = "Loading ${train.number} for $date…"
                scope.launch {
                    try {
                        liveRoute = gateway.journey(train.number, date)
                        journeyMessage = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        journeyMessage = error.message ?: "This train run is unavailable."
                    }
                }
            },
        )
    }
}

@Composable
fun CapsuleNavBar(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color(0xE82B2C31),
            shape = RoundedCornerShape(44.dp),
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.weight(1f).height(72.dp)
        ) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                NavItem(Tab.Journeys, tab, "Journeys", Icons.Outlined.Train, onTab, Modifier.weight(1f))
                NavItem(Tab.Explore, tab, "Explore", Icons.Outlined.Explore, onTab, Modifier.weight(1f))
                NavItem(Tab.Passport, tab, "Passport", Icons.Outlined.AccountCircle, onTab, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Surface(
            onClick = onSearch,
            color = Color(0xE82B2C31),
            shape = CircleShape,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, contentDescription = "Search trains", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun NavItem(
    item: Tab,
    selected: Tab,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onTab: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val active = item == selected
    val color by animateColorAsState(if (active) LM.Ink else LM.Ink2, label = "nav ink")
    val container by animateColorAsState(
        if (active) Color.White.copy(alpha = 0.13f) else Color.Transparent,
        animationSpec = spring(),
        label = "nav selection"
    )
    Surface(
        color = container,
        shape = RoundedCornerShape(35.dp),
        modifier = modifier.fillMaxWidth().height(62.dp).clickable { onTab(item) }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}
