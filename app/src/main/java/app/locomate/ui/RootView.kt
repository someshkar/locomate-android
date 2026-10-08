package app.locomate.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.widget.Toast
import app.locomate.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import app.locomate.ui.theme.Motion
import app.locomate.ui.theme.pressScale
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.PreviewRoutes
import app.locomate.data.PrivacyDataManager
import app.locomate.data.CommunityConsent
import app.locomate.data.CommunityPreferences
import app.locomate.data.CommunityQueue
import app.locomate.data.PhysicalReportQueue
import app.locomate.data.PhysicalReportSyncWork
import app.locomate.data.CommunitySync
import app.locomate.data.CommunityLocationFilter
import app.locomate.data.CommunityLocationService
import app.locomate.BuildConfig
import app.locomate.data.NotificationPermissionDraft
import app.locomate.data.CurrentJourneyStore
import app.locomate.data.JourneyPlan
import app.locomate.data.JourneyPlanStore
import app.locomate.data.JourneyStatusNotification
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.data.SavedJourney
import app.locomate.data.SavedJourneyStore
import app.locomate.data.journeyShareText
import app.locomate.data.openUnavailableReason
import app.locomate.data.StatusPushWork
import app.locomate.data.JourneyAlertChannel
import app.locomate.data.JourneyAlertQuietHours
import app.locomate.data.JourneyAlertLink
import app.locomate.data.JourneyAlertStore
import app.locomate.data.JourneyAlertSubscription
import app.locomate.data.JourneyAlertsNotification
import app.locomate.data.JourneyAlertsWork
import app.locomate.data.TrainSearchResult
import app.locomate.ui.theme.LM
import androidx.metrics.performance.PerformanceMetricsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

enum class Tab { Journeys, Explore, Passport }

private data class AlertDraft(val route: RoutePreview, val channels: Set<JourneyAlertChannel>,
                              val quietHours: JourneyAlertQuietHours?)

@Composable
fun RootView(launchRevision: Int = 0, onDataReset: () -> Unit = {}, railGateway: RailGateway? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val passport = remember { SavedJourneyStore(context) }
    val planStore = remember { JourneyPlanStore(context) }
    val gateway = remember(railGateway) { railGateway ?: RailGateway(context) }
    val currentJourney = remember(gateway) { CurrentJourneyStore(context, gateway.sourceUrl) }
    val privacy = remember(gateway) { PrivacyDataManager(context, gateway) }
    val contributionPreferences = remember { CommunityPreferences(context) }
    val contributionQueue = remember { CommunityQueue(context) }
    val contributionSync = remember { CommunitySync(contributionQueue, gateway, contributionPreferences) }
    val statusCard = remember { JourneyStatusNotification(context) }
    val alertStore = remember { JourneyAlertStore(context) }
    val alertNotifications = remember { JourneyAlertsNotification(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val routes = remember(gateway.configured) {
        if (gateway.configured) emptyList() else PreviewRoutes.load(context)
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Journeys) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchOriginDate by rememberSaveable { mutableStateOf(RailGateway.indiaToday()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var selectedNumber by rememberSaveable { mutableStateOf("12951") }
    var liveRoute by remember { mutableStateOf<RoutePreview?>(null) }
    var statusCardRunId by remember { mutableStateOf(statusCard.activeRun()?.runId) }
    var statusCardRevision by remember { mutableIntStateOf(0) }
    var pendingStatusRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAlertRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var editingAlerts by remember { mutableStateOf<RoutePreview?>(null) }
    var alertRevision by remember { mutableIntStateOf(0) }
    var alertNotice by remember { mutableStateOf<String?>(null) }
    var selectionEpoch by remember { mutableIntStateOf(0) }
    var selectedPreview by remember { mutableStateOf<RoutePreview?>(null) }
    var journeyMessage by remember { mutableStateOf<String?>(null) }
    var passportNotice by remember { mutableStateOf<String?>(null) }
    var editingJourney by remember { mutableStateOf(false) }
    var planVersion by remember { mutableIntStateOf(0) }
    var savedJourneys by remember { mutableStateOf(passport.load()) }
    var privacyBusy by remember { mutableStateOf(false) }
    var privacyNotice by remember { mutableStateOf<String?>(if (StatusPushWork.deleting(context))
        "Deletion is pending. Network activity and sharing are paused. Retry Delete my data to finish cleanup." else null) }
    var contributionEnabled by remember { mutableStateOf(contributionPreferences.enabled) }
    var contributionBackground by remember { mutableStateOf(contributionPreferences.background) }
    var contributionBusy by remember { mutableStateOf(false) }
    var contributionNotice by remember { mutableStateOf<String?>(null) }
    var withdrawalRetryNeeded by remember {
        mutableStateOf(runCatching {
            contributionQueue.pendingWithdrawals().isNotEmpty() ||
                (!contributionPreferences.enabled && contributionQueue.pendingObservations().isNotEmpty())
        }.getOrDefault(false))
    }
    var locationPermissionRevision by remember { mutableIntStateOf(0) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        locationPermissionRevision++
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            contributionNotice = "Precise location permission is needed. Collection stays off until it is allowed."
        }
    }
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
    DisposableEffect(statusCard) {
        // Compose owns listener lifetime; reads run in an effect, never inline during a prefs commit.
        val unsubscribe = statusCard.observe { statusCardRevision++ }
        onDispose { unsubscribe() }
    }
    DisposableEffect(alertStore) {
        val unsubscribe = alertStore.observe { alertRevision++ }
        onDispose { unsubscribe() }
    }
    val alertSnapshot = remember(alertRevision, launchRevision) { runCatching { alertStore.all() } }
    val alertSubscriptions = alertSnapshot.getOrDefault(emptyList())
    val alertReadError = alertSnapshot.exceptionOrNull()?.let {
        "Saved alert choices could not be read. They have been preserved for your data export."
    }
    val selectedAlerts = alertSubscriptions.firstOrNull { it.runId == selectedRoute?.runId }
    val incomingAlert = remember(launchRevision) {
        JourneyAlertLink.parse((context as? Activity)?.intent?.dataString)
    }

    fun reportAlert(message: String) {
        alertNotice = message
        journeyMessage = message
    }

    fun saveAlerts(draft: AlertDraft) {
        if (StatusPushWork.deleting(context)) {
            reportAlert("Data deletion is pending. Retry Delete my data in Settings before enabling alerts.")
            return
        }
        if (!StatusPushWork.available(context)) {
            reportAlert("Push delivery is not configured in this build.")
            return
        }
        if (!alertNotifications.available()) {
            reportAlert("Enable Journey alerts in Android notification settings, then try again.")
            return
        }
        runCatching { alertStore.enable(draft.route, draft.channels, draft.quietHours) }
            .onFailure { reportAlert(it.message ?: "Could not save alert choices. Try again."); return }
        reportAlert(if (runCatching { JourneyAlertsWork.recover(context) }.isSuccess)
            "Alert choices saved. Waiting for gateway confirmation."
        else "Alert choices saved. Retry pending changes in Settings to connect delivery.")
    }

    fun disableAlerts(runId: String) {
        runCatching { alertStore.disable(runId) }
            .onFailure { reportAlert(it.message ?: "Could not turn alerts off. Try again."); return }
        alertNotifications.cancel(runId)
        reportAlert(if (runCatching { JourneyAlertsWork.recover(context) }.isSuccess)
            "Alerts are off on this device. Gateway removal will retry if offline."
        else "Alerts are off on this device. Retry pending changes in Settings to remove them from the gateway.")
    }

    val alertPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val draft = NotificationPermissionDraft.decode(pendingAlertRequest, gateway.sourceUrl, alerts = true)
        pendingAlertRequest = null
        if (!granted) journeyMessage = "Alerts remain off. Allow notifications in Android settings to enable them."
        else if (draft == null) journeyMessage = "Notifications are allowed. Choose journey alerts again to finish enabling them."
        else scope.launch {
            try {
                val route = selectedRoute?.takeIf { it.runId == draft.reference.runId }
                    ?: gateway.journey(draft.reference.trainNumber, draft.reference.serviceDate)
                saveAlerts(AlertDraft(route, draft.channels, draft.quietHours))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportAlert("Notifications are allowed, but this journey could not reload. Choose alerts again when online. ${error.message.orEmpty()}") }
        }
    }
    LaunchedEffect(gateway, launchRevision) {
        if (!privacyBusy) runCatching { JourneyAlertsWork.recover(context) }
    }
    LaunchedEffect(gateway, launchRevision) {
        if (gateway.configured) runCatching { contributionSync.flushWithdrawals() }
            .onSuccess {
                withdrawalRetryNeeded = runCatching {
                    contributionQueue.pendingWithdrawals().isNotEmpty() ||
                        (!contributionPreferences.enabled && contributionQueue.pendingObservations().isNotEmpty())
                }.getOrDefault(true)
            }
    }
    LaunchedEffect(selectedRoute?.runId, contributionEnabled, contributionBackground,
        locationPermissionRevision, launchRevision, privacyBusy) {
        CommunityLocationService.stop(context)
        val route = selectedRoute
        if (privacyBusy || StatusPushWork.deleting(context) || !contributionEnabled || !gateway.configured || route == null ||
            !CommunityLocationFilter.inRunWindow(route)) return@LaunchedEffect
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            contributionNotice = "Allow precise location to contribute this journey."
            return@LaunchedEffect
        }
        runCatching { CommunityLocationService.start(context, route) }
            .onFailure { contributionNotice = "Could not start location contribution: ${it.message ?: "check device settings"}" }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val draft = NotificationPermissionDraft.decode(pendingStatusRequest, gateway.sourceUrl, alerts = false)
        pendingStatusRequest = null
        if (!granted) journeyMessage = "Allow notifications in Android settings to show the status card."
        else if (draft == null) journeyMessage = "Notifications are allowed. Tap Status card again to show it."
        else {
            val epoch = selectionEpoch
            scope.launch {
                try {
                    val route = selectedRoute?.takeIf { it.runId == draft.reference.runId }
                        ?: gateway.journey(draft.reference.trainNumber, draft.reference.serviceDate)
                    if (selectionEpoch != epoch || currentJourney.read()?.runId != draft.reference.runId) return@launch
                    if (statusCard.enable(route)) {
                        statusCardRunId = route.runId
                        StatusPushWork.enable(context)
                        journeyMessage = "Status card shows ${route.trainNumber}'s current journey status."
                    } else journeyMessage = "A fresh journey and enabled notifications are needed for the status card."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { journeyMessage = "Notifications are allowed, but this journey could not reload. Tap Status card again when online. ${error.message.orEmpty()}" }
            }
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

    fun rememberCurrentRun(reference: JourneyAlertLink): Boolean = runCatching {
        currentJourney.select(reference)
        true
    }.getOrElse {
        journeyMessage = it.message ?: "Could not save this journey for restart."
        false
    }

    fun stopStatusCardUnless(runId: String?) {
        if (statusCardRunId != null && statusCardRunId != runId) {
            stopStatusCard()
        }
    }

    fun openDatedJourney(reference: JourneyAlertLink) {
        if (!gateway.configured || !rememberCurrentRun(reference)) return
        selectionEpoch++
        val epoch = selectionEpoch
        stopStatusCardUnless(reference.runId)
        searchOpen = false
        settingsOpen = false
        tab = Tab.Journeys
        selectedPreview = null
        liveRoute = null
        journeyMessage = "Loading ${reference.trainNumber} for ${reference.serviceDate}…"
        scope.launch {
            try {
                val loaded = gateway.journey(reference.trainNumber, reference.serviceDate)
                if (selectionEpoch == epoch) {
                    liveRoute = loaded
                    journeyMessage = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (selectionEpoch == epoch) journeyMessage = error.message ?: "This train run is unavailable."
            }
        }
    }

    LaunchedEffect(incomingAlert?.url, launchRevision) {
        val link = incomingAlert ?: return@LaunchedEffect
        if (gateway.configured && !rememberCurrentRun(link)) {
            val activity = context as? Activity
            if (activity?.intent?.dataString == link.url) activity.intent.data = null
            return@LaunchedEffect
        }
        tab = Tab.Journeys
        settingsOpen = false
        searchOpen = false
        selectionEpoch++
        val epoch = selectionEpoch
        stopStatusCardUnless(link.runId)
        try {
            if (!gateway.configured) {
                journeyMessage = "This dated journey requires a configured rail gateway. Preview data cannot open it."
                return@LaunchedEffect
            }
            val journey = gateway.journey(link.trainNumber, link.serviceDate)
            if (selectionEpoch == epoch) {
                liveRoute = journey
                selectedPreview = null
                journeyMessage = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (selectionEpoch == epoch) journeyMessage = "Could not open ${link.runId}: ${error.message ?: "try again when online"}"
        } finally {
            val activity = context as? Activity
            if (activity?.intent?.dataString == link.url) activity.intent.data = null
        }
    }

    LaunchedEffect(gateway, launchRevision, statusCardRevision) {
        val active = statusCard.activeRun()
        statusCardRunId = active?.runId
        val savedReference = runCatching { currentJourney.read() }.getOrElse {
                journeyMessage = "The saved journey reference could not be read. Select your train again."
                null
            }
        if (!gateway.configured || StatusPushWork.deleting(context) || incomingAlert != null) return@LaunchedEffect
        val reference = active?.let { JourneyAlertLink.fromRunId(it.runId) } ?: savedReference ?: return@LaunchedEffect
        if (active != null) {
            if (!rememberCurrentRun(reference)) return@LaunchedEffect
            StatusPushWork.enable(context)
        }
        if (liveRoute != null || selectedPreview != null) return@LaunchedEffect
        val epoch = selectionEpoch
        journeyMessage = "Restoring ${reference.trainNumber} for ${reference.serviceDate}…"
        try {
            val restored = gateway.journey(reference.trainNumber, reference.serviceDate)
            if (selectionEpoch == epoch && incomingAlert == null) {
                liveRoute = restored
                selectedPreview = null
                journeyMessage = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (selectionEpoch == epoch) {
                if (active != null) stopStatusCard()
                journeyMessage = "Could not restore ${reference.runId}: ${error.message ?: "try again when online"}"
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
        if (current != null && current.runId == statusCardRunId && !statusCard.refresh(current)) {
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

    NavigationScaffold(tab = tab, onTab = { tab = it; searchOpen = false; settingsOpen = false },
        onSearch = { openSearch() }, searchActive = searchOpen) { dockInset ->
        if (searchOpen) {
            SearchScreen(
                routes = routes,
                bottomInset = dockInset,
                gateway = gateway,
                originDate = searchOriginDate,
                onOriginDateChange = { searchOriginDate = it },
                onSelect = { number ->
                    selectionEpoch++
                    stopStatusCardUnless(null)
                    selectedNumber = number
                    searchOpen = false
                    tab = Tab.Journeys
                },
                onSelectLive = { train: TrainSearchResult, date: String ->
                    JourneyAlertLink.fromRunId("${train.number}:$date")?.let(::openDatedJourney)
                },
            )
        } else Crossfade(targetState = tab, label = "primary tab") { current ->
            when (current) {
                Tab.Journeys -> JourneyScreen(
                    onRelatedJourney = { train, date -> JourneyAlertLink.fromRunId("$train:$date")?.let(::openDatedJourney) },
                    route = selectedRoute,
                    bottomInset = dockInset,
                    railGateway = gateway,
                    plan = selectedPlan,
                    productionMode = gateway.configured && selectedPreview == null,
                    saved = selectedRoute?.let { SavedJourney.from(it, selectedPlan ?: JourneyPlan.default(it)).key in savedJourneys.map(SavedJourney::key) } ?: false,
                    message = journeyMessage,
                    statusCardEnabled = selectedRoute?.runId != null && selectedRoute.runId == statusCardRunId,
                    alertStatus = alertReadError ?: selectedAlerts?.status,
                    onAlerts = {
                        val route = selectedRoute
                        if (alertReadError != null) {
                            journeyMessage = alertReadError
                        } else if (route == null || JourneyAlertSubscription.localExpiry(route) == null) {
                            journeyMessage = "Alerts need a current dated production journey. Historical previews cannot send alerts."
                        } else editingAlerts = route
                    },
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
                                pendingStatusRequest = NotificationPermissionDraft(
                                    JourneyAlertLink(route.trainNumber, route.runDate)).encode(gateway.sourceUrl)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            statusCard.enable(route) -> {
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
                            val text = journeyShareText(route, selectedPlan)
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
                Tab.Explore -> ExploreScreen(selectedRoute, gateway, bottomInset = dockInset,
                    onOpenJourney = ::openDatedJourney)
                Tab.Passport -> if (settingsOpen) SettingsScreen(
                    railGateway = gateway,
                    alertSubscriptions = alertSubscriptions,
                    alertReadError = alertReadError,
                    alertNotice = alertNotice,
                    onDisableAlerts = { disableAlerts(it) },
                    onRetryAlerts = { runCatching { JourneyAlertsWork.recover(context) }
                        .onSuccess { alertNotice = "Pending alert changes queued for retry." }
                        .onFailure { alertNotice = it.message ?: "Could not retry alert changes." } },
                    productionMode = gateway.configured,
                    savedCount = savedJourneys.size,
                    onBack = { settingsOpen = false },
                    privacyBusy = privacyBusy,
                    privacyNotice = privacyNotice,
                    contributionEnabled = contributionEnabled,
                    contributionBackground = contributionBackground,
                    contributionBusy = contributionBusy,
                    contributionNotice = contributionNotice,
                    withdrawalRetryNeeded = withdrawalRetryNeeded,
                    onContributionGrant = {
                        scope.launch {
                            contributionBusy = true
                            contributionNotice = null
                            try {
                                contributionSync.flushWithdrawals()
                                gateway.recordCommunityConsent(CommunityConsent.evidence(true))
                                contributionQueue.clearObservations()
                                contributionPreferences.grant()
                                contributionEnabled = true
                                contributionNotice = "Consent recorded. Only a current journey you open can start sharing."
                                if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                contributionNotice = "Consent could not be recorded. Collection stays off."
                            } finally {
                                contributionBusy = false
                            }
                        }
                    },
                    onContributionRevoke = {
                        CommunityLocationService.stop(context)
                        PhysicalReportSyncWork.cancel(context)
                        val persisted = runCatching {
                            PhysicalReportQueue(context, gateway.sourceUrl).clear()
                            contributionPreferences.revoke()
                        }.isSuccess
                        contributionEnabled = contributionPreferences.enabled
                        contributionBackground = contributionPreferences.background
                        if (!persisted) {
                            contributionNotice = "Collection stopped for this session, but consent could not be changed on this device. Retry withdrawal."
                        } else {
                            val erased = runCatching { contributionQueue.clearObservations() }.isSuccess
                            val saved = runCatching {
                                if (contributionQueue.pendingWithdrawals().isEmpty()) {
                                    contributionQueue.addWithdrawal(CommunityConsent.evidence(false))
                                }
                            }.isSuccess
                            contributionNotice = when {
                                !erased -> "Collection stopped, but queued observations could not be erased. Retry deletion."
                                saved -> "Collection stopped and local observations deleted. Sending withdrawal…"
                                else -> "Collection stopped, but withdrawal could not be saved. Retry while online."
                            }
                            if (saved) scope.launch {
                                runCatching { contributionSync.flushWithdrawals() }
                                    .onSuccess {
                                        withdrawalRetryNeeded = !erased
                                        if (erased) contributionNotice = "Consent withdrawn on this device and the gateway."
                                    }
                                    .onFailure { if (erased) contributionNotice = "Collection stopped here. Gateway withdrawal is saved for retry." }
                            }
                        }
                        withdrawalRetryNeeded = true
                    },
                    onContributionBackground = { enabled ->
                        contributionPreferences.setBackground(enabled)
                        contributionBackground = contributionPreferences.background
                        contributionNotice = if (enabled)
                            "Background sharing is on while a current journey is active."
                            else "Background sharing is off."
                    },
                    onExportData = {
                        scope.launch {
                            privacyBusy = true
                            privacyNotice = null
                            try {
                                val file = privacy.prepareExport()
                                val uri = FileProvider.getUriForFile(context,
                                    "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("Locomate data", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Export Locomate data"))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                privacyNotice = error.message ?: "Could not prepare your data export. Try again."
                            } finally {
                                privacyBusy = false
                            }
                        }
                    },
                    onDeleteData = {
                        scope.launch {
                            privacyBusy = true
                            privacyNotice = null
                            try {
                                val complete = privacy.deleteAll()
                                Toast.makeText(context,
                                    if (complete) "Locomate data deleted"
                                    else "Server data deleted; retry deletion to finish device cleanup",
                                    Toast.LENGTH_LONG).show()
                                privacyBusy = false
                                onDataReset()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                privacyNotice = "Deletion is pending. Network activity and sharing remain paused. Retry Delete my data. ${error.message.orEmpty()}"
                                privacyBusy = false
                            }
                        }
                    },
                    onOfficialRailway = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://enquiry.indianrail.gov.in/mntes/")))
                    }
                ) else PassportScreen(
                    savedRoutes = savedJourneys,
                    bottomInset = dockInset,
                    notice = passportNotice,
                    onSettings = { settingsOpen = true },
                    onRemove = { key ->
                        updateSaved(savedJourneys.filterNot { it.key == key })
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                    },
                    onOpen = { saved ->
                        saved.openUnavailableReason()?.let { reason ->
                            passportNotice = reason
                            return@PassportScreen
                        }
                        if (!saved.preview && gateway.configured && saved.originDate != null) {
                            val reference = JourneyAlertLink.fromRunId("${saved.trainNumber}:${saved.originDate}")
                            if (reference == null || !rememberCurrentRun(reference)) return@PassportScreen
                        }
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
                                val savedPlan = saved.personalPlan ?: JourneyPlan(saved.originCode, saved.destinationCode,
                                    planStore.load(previewRoute).coach, planStore.load(previewRoute).seat)
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
                                    val savedPlan = saved.personalPlan ?: JourneyPlan(saved.originCode, saved.destinationCode,
                                        planStore.load(loaded).coach, planStore.load(loaded).seat)
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
    editingAlerts?.let { route ->
        JourneyAlertsDialog(route, alertSubscriptions.firstOrNull { it.runId == route.runId },
            pushAvailable = StatusPushWork.available(context),
            onDismiss = { editingAlerts = null },
            onDisable = {
                route.runId?.let(::disableAlerts)
                editingAlerts = null
            },
            onNotificationSettings = {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            onSave = { channels, quietHours ->
                editingAlerts = null
                val draft = AlertDraft(route, channels, quietHours)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingAlertRequest = NotificationPermissionDraft(
                        JourneyAlertLink(route.trainNumber, requireNotNull(route.runDate)), channels, quietHours)
                        .encode(gateway.sourceUrl)
                    alertPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else saveAlerts(draft)
            })
    }
}

/** Shares the actual dock footprint (including system inset and outer padding) with map cards. */
@Composable
internal fun NavigationScaffold(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit,
                                searchActive: Boolean = false,
                                mapGlass: MapGlassController = remember { MapGlassController() },
                                content: @Composable (Dp) -> Unit) {
    var dockHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // While typing, the dock steps aside so results get the room above the keyboard.
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val dockInset = if (imeVisible) 16.dp else maxOf(115.dp, with(density) { dockHeightPx.toDp() } + 12.dp)
    val pageLayer = rememberGraphicsLayer()
    val backdrop = remember(pageLayer) { DockBackdrop(pageLayer) }
    androidx.compose.runtime.CompositionLocalProvider(LocalMapGlass provides mapGlass, LocalDockBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().then(if (searchActive) Modifier.imePadding() else Modifier)) {
            Box(Modifier.fillMaxSize()
                .onGloballyPositioned { backdrop.bounds = it.boundsInWindow() }
                .drawWithContent {
                    pageLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(pageLayer)
                    backdrop.pageDrawn()
                }) { content(dockInset) }
            androidx.compose.animation.AnimatedVisibility(
                visible = !imeVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically(Motion.dockSpring) { it } +
                    androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(Motion.dockSpring) { it } +
                    androidx.compose.animation.fadeOut()
            ) {
                CapsuleNavBar(tab, onTab, onSearch, searchActive = searchActive,
                    modifier = Modifier
                        .onSizeChanged { dockHeightPx = it.height }
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
fun CapsuleNavBar(tab: Tab, onTab: (Tab) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier, searchActive: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    val fallback = Brush.verticalGradient(listOf(LM.DockTop, LM.DockBottom))
    val lens = Brush.verticalGradient(listOf(LM.Elevated.copy(alpha = 0.42f),
        LM.Elevated.copy(alpha = 0.26f)))
    Row(modifier.widthIn(max = 330.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 20.dp,
            modifier = Modifier.weight(1f).heightIn(min = 70.dp)
        ) {
            DockGlassSurface(Modifier.dockRim(36f)) { glass ->
                val tabs = listOf(Tab.Journeys, Tab.Explore, Tab.Passport)
                val selectedIndex = tabs.indexOf(tab).takeUnless { searchActive || it < 0 }
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.background(if (glass) lens else fallback).padding(8.dp)
                ) {
                    // One pill slides between tabs on a spring, as on iOS, rather
                    // than each tab fading its own background in and out.
                    val slot = maxWidth / tabs.size
                    val pillOffset by androidx.compose.animation.core.animateDpAsState(
                        slot * (selectedIndex ?: 0),
                        app.locomate.ui.theme.motionSpec(androidx.compose.animation.core.spring(
                            dampingRatio = 0.76f, stiffness = 584f)),
                        label = "dock pill")
                    val pillAlpha by androidx.compose.animation.core.animateFloatAsState(
                        if (selectedIndex == null) 0f else 1f, label = "dock pill alpha")
                    Box(Modifier.matchParentSize()) {
                        Box(Modifier.offset(x = pillOffset).width(slot).fillMaxHeight()
                            .graphicsLayer { alpha = pillAlpha }
                            .background(LM.Ink.copy(alpha = 0.07f), RoundedCornerShape(27.dp))
                            .dockRim(27f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val selectTab: (Tab) -> Unit = {
                            haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onTab(it)
                        }
                        NavItem(Tab.Journeys, tab.takeUnless { searchActive }, "Journeys", R.drawable.navigation_journeys, selectTab, Modifier.weight(1f))
                        NavItem(Tab.Explore, tab.takeUnless { searchActive }, "Explore", R.drawable.navigation_explore, selectTab, Modifier.weight(1f))
                        NavItem(Tab.Passport, tab.takeUnless { searchActive }, "Passport", R.drawable.navigation_passport, selectTab, Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        val searchPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Surface(
            onClick = onSearch,
            color = Color.Transparent,
            shape = CircleShape,
            shadowElevation = 20.dp,
            interactionSource = searchPress,
            modifier = Modifier.size(60.dp).pressScale(searchPress, 0.9f).semantics { contentDescription = "Search trains"; selected = searchActive; role = Role.Tab }
        ) {
            DockGlassSurface(Modifier.dockRim(30f)) { glass ->
                Box(Modifier.fillMaxSize().background(if (glass) lens else fallback), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.navigation_search), contentDescription = null, tint = LM.Ink, modifier = Modifier.size(23.dp))
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    item: Tab,
    selected: Tab?,
    label: String,
    @DrawableRes icon: Int,
    onTab: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    val active = item == selected
    val showLabel = LocalDensity.current.fontScale < 1.8f
    val color by animateColorAsState(if (active) LM.Ink else LM.DockLabel, label = "nav ink")
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = { onTab(item) },
        color = Color.Transparent,
        shape = RoundedCornerShape(27.dp),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp).pressScale(interaction, 0.92f)
            .semantics {
                contentDescription = label
                this.selected = active
                role = Role.Tab
            }
    ) {
        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
            Spacer(Modifier.height(4.dp))
            if (showLabel) {
                Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
