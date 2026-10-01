package app.locomate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.PreviewRoutes
import app.locomate.BuildConfig
import app.locomate.data.JourneyPlan
import app.locomate.data.JourneyPlanStore
import app.locomate.data.JourneyStatusNotification
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.data.SavedJourney
import app.locomate.data.SavedJourneyStore
import app.locomate.data.StatusPushWork
import app.locomate.data.TrainSearchResult
import app.locomate.ui.theme.LM
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

enum class Tab { Journeys, Explore, Passport }

@Composable
fun RootView(launchRevision: Int = 0) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val passport = remember { SavedJourneyStore(context) }
    val planStore = remember { JourneyPlanStore(context) }
    val gateway = remember { RailGateway(context) }
    val statusCard = remember { JourneyStatusNotification(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val routes = remember(gateway.configured) {
        if (gateway.configured) emptyList() else PreviewRoutes.load(context)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Journeys) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedNumber by rememberSaveable { mutableStateOf("12951") }
    var liveRoute by remember { mutableStateOf<RoutePreview?>(null) }
    var statusCardRunId by remember { mutableStateOf(statusCard.activeRun()?.runId) }
    var pendingStatusRoute by remember { mutableStateOf<RoutePreview?>(null) }
    var selectionEpoch by remember { mutableIntStateOf(0) }
    var selectedPreview by remember { mutableStateOf<RoutePreview?>(null) }
    var journeyMessage by remember { mutableStateOf<String?>(null) }
    var passportNotice by remember { mutableStateOf<String?>(null) }
    var editingJourney by remember { mutableStateOf(false) }
    var planVersion by remember { mutableIntStateOf(0) }
    var savedJourneys by remember { mutableStateOf(passport.load()) }
    LaunchedEffect(tab, searchOpen, settingsOpen, view) {
        if (!BuildConfig.DEBUG) return@LaunchedEffect
        val screen = when {
            searchOpen -> "Search"
            settingsOpen -> "Settings"
            else -> tab.name
        }
        PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("screen", screen)
    }
    val selectedRoute = if (gateway.configured) selectedPreview ?: liveRoute
        else routes.firstOrNull { it.trainNumber == selectedNumber } ?: routes.firstOrNull()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val route = pendingStatusRoute
        pendingStatusRoute = null
        if (route?.runId != selectedRoute?.runId) return@rememberLauncherForActivityResult
        if (granted && route != null && statusCard.show(route)) {
            statusCardRunId = route.runId
            StatusPushWork.enable(context)
            journeyMessage = "Status card shows ${route.trainNumber}'s current journey status."
        } else {
            journeyMessage = "Allow notifications in Android settings to show the status card."
        }
    }
    val selectedPlan = remember(selectedRoute?.runId, selectedRoute?.trainNumber,
        selectedRoute?.runDate, selectedRoute?.isPreview, planVersion) {
        selectedRoute?.let(planStore::load)
    }

    fun updateSaved(next: List<SavedJourney>) {
        savedJourneys = next
        passport.save(next)
    }

    fun openSearch() {
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        searchOpen = true
    }

    fun stopStatusCard() {
        val runId = statusCardRunId
        statusCard.cancel()
        statusCardRunId = null
        if (runId != null) StatusPushWork.unregister(context, runId)
    }

    fun stopStatusCardUnless(runId: String?) {
        if (statusCardRunId != null && statusCardRunId != runId) {
            stopStatusCard()
        }
    }

    LaunchedEffect(gateway, launchRevision) {
        if (!gateway.configured) return@LaunchedEffect
        val active = statusCard.activeRun() ?: return@LaunchedEffect
        statusCardRunId = active.runId
        StatusPushWork.enable(context)
        if (liveRoute?.runId == active.runId) {
            tab = Tab.Journeys
            return@LaunchedEffect
        }
        val epoch = selectionEpoch
        try {
            val restored = gateway.journey(active.trainNumber, active.originDate)
            if (selectionEpoch == epoch && statusCard.activeRun()?.runId == active.runId) {
                liveRoute = restored
                selectedPreview = null
                tab = Tab.Journeys
                journeyMessage = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (selectionEpoch == epoch) {
                stopStatusCard()
                journeyMessage = "The saved status card could not refresh. Select the train again."
            }
        }
    }

    LaunchedEffect(selectedRoute?.runId) {
        if (selectedRoute != null && statusCardRunId != null && selectedRoute.runId != statusCardRunId) {
            stopStatusCard()
        }
    }

    LaunchedEffect(liveRoute, statusCardRunId) {
        val current = liveRoute
        if (current != null && current.runId == statusCardRunId && !statusCard.show(current)) {
            stopStatusCard()
            journeyMessage = "The status card stopped because this run is stale or notifications are unavailable."
        }
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

    BackHandler(enabled = searchOpen || settingsOpen || tab != Tab.Journeys) {
        if (searchOpen) searchOpen = false
        else if (settingsOpen) settingsOpen = false
        else tab = Tab.Journeys
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = tab, label = "primary tab") { current ->
            when (current) {
                Tab.Journeys -> JourneyScreen(
                    route = selectedRoute,
                    plan = selectedPlan,
                    productionMode = gateway.configured && selectedPreview == null,
                    saved = selectedRoute?.let { SavedJourney.from(it, selectedPlan ?: JourneyPlan.default(it)).key in savedJourneys.map(SavedJourney::key) } ?: false,
                    message = journeyMessage,
                    statusCardEnabled = selectedRoute?.runId != null && selectedRoute.runId == statusCardRunId,
                    onStatusCard = {
                        val route = selectedRoute
                        when {
                            route == null || route.isPreview || route.runId == null || route.runDate == null ->
                                journeyMessage = "A dated production journey is required for a status card."
                            route.runId == statusCardRunId -> {
                                stopStatusCard()
                                journeyMessage = "Status card is off for ${route.trainNumber}."
                            }
                            route.statusLabel.startsWith("STALE") ->
                                journeyMessage = "A fresh journey is required for a status card."
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                                pendingStatusRoute = route
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            statusCard.show(route) -> {
                                statusCardRunId = route.runId
                                StatusPushWork.enable(context)
                                journeyMessage = "Status card shows ${route.trainNumber}'s current journey status."
                            }
                            else -> journeyMessage = "Enable notifications in Android settings to show the status card."
                        }
                    },
                    onEdit = { editingJourney = true },
                    onSearch = { openSearch() },
                    onCalendar = {
                        selectedRoute?.let { route ->
                            val board = route.calls.firstOrNull { it.code == selectedPlan?.boardingCode }
                            val leave = route.calls.firstOrNull { it.code == selectedPlan?.alightingCode }
                            val fullRun = selectedPlan == null || selectedPlan == JourneyPlan.default(route)
                            val start = if (fullRun) route.departureInstantMillis
                                else board?.scheduledDepartureMillis ?: board?.scheduledArrivalMillis
                            val end = if (fullRun) route.arrivalInstantMillis else leave?.scheduledArrivalMillis
                            if (!route.isPreview && start != null && end != null) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_INSERT).apply {
                                        data = CalendarContract.Events.CONTENT_URI
                                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                                        putExtra(CalendarContract.Events.TITLE, "${route.trainNumber} · ${route.displayName}")
                                        putExtra(CalendarContract.Events.EVENT_LOCATION,
                                            "${board?.name ?: route.originName} → ${leave?.name ?: route.destinationName}")
                                        putExtra(CalendarContract.Events.DESCRIPTION,
                                            "Scheduled rail journey · ${route.statusLabel}. Check current railway information before travel.")
                                    })
                                }.onFailure { journeyMessage = "No calendar app is available on this device." }
                            }
                        }
                    },
                    onShare = {
                        selectedRoute?.let { route ->
                            val board = route.calls.firstOrNull { it.code == selectedPlan?.boardingCode }
                            val leave = route.calls.firstOrNull { it.code == selectedPlan?.alightingCode }
                            val text = "${route.trainNumber} · ${route.displayName}\n${board?.name ?: route.originName} → ${leave?.name ?: route.destinationName}\n${route.statusLabel}. ${route.sourceDetail}"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }, "Share journey"))
                        }
                    },
                    onSave = {
                        selectedRoute?.let { route ->
                            val entry = SavedJourney.from(route, selectedPlan ?: JourneyPlan.default(route))
                            val alreadySaved = savedJourneys.any { it.key == entry.key }
                            updateSaved(if (alreadySaved)
                                savedJourneys.filterNot { it.key == entry.key }
                                else savedJourneys + entry)
                            haptics.performHapticFeedback(
                                if (alreadySaved) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn
                            )
                        }
                    }
                )
                Tab.Explore -> ExploreScreen(selectedRoute, gateway)
                Tab.Passport -> if (settingsOpen) SettingsScreen(
                    productionMode = gateway.configured,
                    savedCount = savedJourneys.size,
                    onBack = { settingsOpen = false },
                    onOfficialRailway = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://enquiry.indianrail.gov.in/mntes/")))
                    }
                ) else PassportScreen(
                    savedRoutes = savedJourneys,
                    notice = passportNotice,
                    onSettings = { settingsOpen = true },
                    onRemove = { key ->
                        updateSaved(savedJourneys.filterNot { it.key == key })
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    },
                    onOpen = { saved ->
                        selectionEpoch++
                        val selectedEpoch = selectionEpoch
                        if (!saved.preview && !gateway.configured) {
                            passportNotice = "A rail gateway is needed to reopen this dated run. Your saved summary is still on this device."
                        } else if (saved.preview) {
                            stopStatusCardUnless(null)
                            val previewRoute = routes.firstOrNull { it.trainNumber == saved.trainNumber }
                            if (previewRoute == null) {
                                passportNotice = "This historical route pack is no longer available. Your saved summary remains on this device."
                            } else {
                                val savedPlan = JourneyPlan(saved.originCode, saved.destinationCode)
                                if (savedPlan.isValidFor(previewRoute)) planStore.save(previewRoute, savedPlan)
                                planVersion++
                                passportNotice = null
                                tab = Tab.Journeys
                                selectedNumber = saved.trainNumber
                                selectedPreview = previewRoute
                            }
                        } else if (!saved.preview && gateway.configured && saved.originDate != null) {
                            stopStatusCardUnless("${saved.trainNumber}:${saved.originDate}")
                            passportNotice = null
                            tab = Tab.Journeys
                            selectedPreview = null
                            liveRoute = null
                            journeyMessage = "Loading ${saved.trainNumber} for ${saved.originDate}…"
                            scope.launch {
                                try {
                                    val loaded = gateway.journey(saved.trainNumber, saved.originDate)
                                    if (selectionEpoch != selectedEpoch) return@launch
                                    val savedPlan = JourneyPlan(saved.originCode, saved.destinationCode)
                                    if (savedPlan.isValidFor(loaded)) planStore.save(loaded, savedPlan)
                                    planVersion++
                                    liveRoute = loaded
                                    journeyMessage = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    if (selectionEpoch == selectedEpoch) {
                                        journeyMessage = error.message ?: "This saved run is unavailable."
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
        CapsuleNavBar(
            tab = tab,
            onTab = { tab = it; settingsOpen = false },
            onSearch = { openSearch() },
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
                selectionEpoch++
                stopStatusCardUnless(null)
                selectedNumber = number
                searchOpen = false
                tab = Tab.Journeys
            },
            onSelectLive = { train: TrainSearchResult, date: String ->
                selectionEpoch++
                val selectedEpoch = selectionEpoch
                stopStatusCardUnless("${train.number}:$date")
                searchOpen = false
                tab = Tab.Journeys
                selectedPreview = null
                liveRoute = null
                journeyMessage = "Loading ${train.number} for $date…"
                scope.launch {
                    try {
                        val loaded = gateway.journey(train.number, date)
                        if (selectionEpoch == selectedEpoch) {
                            liveRoute = loaded
                            journeyMessage = null
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (selectionEpoch == selectedEpoch) {
                            journeyMessage = error.message ?: "This train run is unavailable."
                        }
                    }
                }
            },
        )
    }
    if (editingJourney && selectedRoute != null && selectedPlan != null) {
        JourneySetupDialog(selectedRoute, selectedPlan,
            onDismiss = { editingJourney = false },
            onSave = { next ->
                planStore.save(selectedRoute, next)
                planVersion++
                editingJourney = false
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            })
    }
}

@Composable
fun CapsuleNavBar(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color(0xE82B2C31),
            shape = RoundedCornerShape(44.dp),
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.weight(1f).height(72.dp)
        ) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                val selectTab: (Tab) -> Unit = {
                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onTab(it)
                }
                NavItem(Tab.Journeys, tab, "Journeys", Icons.Outlined.Train, selectTab, Modifier.weight(1f))
                NavItem(Tab.Explore, tab, "Explore", Icons.Outlined.Explore, selectTab, Modifier.weight(1f))
                NavItem(Tab.Passport, tab, "Passport", Icons.Outlined.AccountCircle, selectTab, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Surface(
            onClick = onSearch,
            color = Color(0xE82B2C31),
            shape = CircleShape,
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier.size(72.dp).semantics { contentDescription = "Search trains" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
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
        onClick = { onTab(item) },
        color = container,
        shape = RoundedCornerShape(35.dp),
        modifier = modifier.fillMaxWidth().height(62.dp)
            .semantics {
                contentDescription = label
                this.selected = active
                role = Role.Tab
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}
