package app.locomate.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.RoutePreview
import app.locomate.data.RouteStop
import app.locomate.data.JourneyPlan
import app.locomate.data.JourneySummaryClock
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.locomate.data.RailTimeText
import app.locomate.data.RailGateway
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.PlexMono
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JourneyScreen(route: RoutePreview?, plan: JourneyPlan? = null, saved: Boolean, productionMode: Boolean = false,
                  message: String? = null, statusCardEnabled: Boolean = false, onSave: () -> Unit,
                  onCalendar: () -> Unit = {}, onShare: () -> Unit = {}, onEdit: () -> Unit = {},
                  onSearch: () -> Unit = {}, onStatusCard: () -> Unit = {},
                  alertStatus: String? = null, onAlerts: () -> Unit = {}, railGateway: RailGateway? = null,
                  bottomInset: Dp = 0.dp) {
    val haptics = LocalHapticFeedback.current
    val (reliability, retryReliability) = rememberTrainReliability(route, railGateway)
    val segment = plan?.takeIf { route != null && it.isValidFor(route) }
    val boarding = route?.calls?.firstOrNull { it.code == segment?.boardingCode }
    val alighting = route?.calls?.firstOrNull { it.code == segment?.alightingCode }
    val fullRoute = route != null && (segment == null || segment == JourneyPlan.default(route))
    val calendarStart = if (fullRoute) route?.departureInstantMillis
        else boarding?.scheduledDepartureMillis ?: boarding?.scheduledArrivalMillis
    val calendarEnd = if (fullRoute) route?.arrivalInstantMillis else alighting?.scheduledArrivalMillis
    val segmentLabel = if (fullRoute) route?.routeLabel else if (boarding != null && alighting != null)
        "${boarding.name} to ${alighting.name}" else route?.routeLabel
    val mapAttribution = remember { MapAttributionController() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080B12))) {
        RailMap(route, attribution = mapAttribution)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.42f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.68f),
                )
            )
        )

        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val collapsedTop = screenHeightPx * 0.47f
        val expandedTop = screenHeightPx * 0.10f
        val largeText = density.fontScale >= 1.5f
        var sheetTop by remember(screenHeightPx, largeText) { mutableFloatStateOf(if (largeText) expandedTop else collapsedTop) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val expanded = sheetTop < (collapsedTop + expandedTop) / 2f
        // The surface extends below the screen; only the content reserves that
        // hidden portion and the actual navigation dock above the screen edge.
        val hiddenSheetHeight = with(density) { sheetTop.toDp() } - maxHeight * 0.10f

        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(topStart = LM.RadiusSheet, topEnd = LM.RadiusSheet),
            shadowElevation = 28.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
                .height(maxHeight * 0.90f)
                .offset { IntOffset(0, sheetTop.roundToInt()) }
        ) {
            Column(Modifier.background(Brush.verticalGradient(listOf(Color(0xF00B0C16), Color(0xFA090A12))))
                .padding(bottom = (hiddenSheetHeight + bottomInset).coerceAtLeast(0.dp))) {
                // Scrolled action touch bounds must not occlude this fixed header.
                Column(
                    Modifier.fillMaxWidth().zIndex(1f).pointerInput(collapsedTop, expandedTop) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onVerticalDrag = { change, amount ->
                                sheetTop = (sheetTop + amount).coerceIn(expandedTop, collapsedTop)
                                change.consume()
                            },
                            onDragEnd = {
                                val target = if (sheetTop < (collapsedTop + expandedTop) / 2f) expandedTop else collapsedTop
                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                settleJob = scope.launch {
                                    Animatable(sheetTop).animateTo(
                                        target,
                                        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMedium)
                                    ) { sheetTop = value }
                                }
                            }
                        )
                    }.padding(horizontal = 24.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(48.dp)
                        .clickable(role = Role.Button) {
                            settleJob?.cancel()
                            settleJob = scope.launch {
                                Animatable(sheetTop).animateTo(if (expanded) collapsedTop else expandedTop,
                                    spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMedium)) { sheetTop = value }
                            }
                        }.semantics {
                            contentDescription = if (expanded) "Collapse journey details" else "Expand journey details"
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.42f)))
                    }
                    MapAttributionButton(mapAttribution)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("My Journeys", color = LM.Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        Box(Modifier.size(48.dp)
                            .clickable(enabled = route != null, role = Role.Button, onClick = onSave)
                            .semantics {
                                contentDescription = if (saved) "Remove saved journey" else "Save journey"
                                stateDescription = if (saved) "Saved" else "Not saved"
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha = 0.09f)),
                                contentAlignment = Alignment.Center) {
                                Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    tint = LM.Ink, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }

                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    item(key = "journey-summary") {
                    Column {
                    if (route == null) {
                        Spacer(Modifier.height(15.dp))
                        Text("Every journey starts here.", color = LM.Ink, fontSize = 29.sp,
                            fontWeight = FontWeight.Bold, lineHeight = 33.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(message ?: "Find a train and choose its India origin date to see the route, station times and source of every update.",
                            color = LM.Ink2, fontSize = 15.sp, lineHeight = 22.sp)
                        Spacer(Modifier.height(24.dp))
                        Surface(onClick = onSearch, color = LM.Accent, shape = RoundedCornerShape(17.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(17.dp), horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.Black,
                                    modifier = Modifier.size(20.dp))
                                Text("Find your train", color = Color.Black, fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    } else {
                    Surface(color = Color.Black.copy(alpha = 0.12f), shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${route.trainNumber} · ${route.displayName}",
                            color = LM.Ink2, fontSize = 14.sp)
                        Text(route?.statusLabel ?: "SEARCH TO START", color = if (route?.isPreview == true) Color(0xFFBCA7FF) else LM.Accent,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    ScheduledDepartureCountdown(route, segment)
                    Text(segmentLabel ?: "Find your train", color = LM.Ink, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(if (route.isPreview) "Historical timetable · no live ETA"
                        else message ?: route.sourceDetail, color = LM.Ink3, fontSize = 13.sp)
                    route?.etaBand?.let { band ->
                        Spacer(Modifier.height(8.dp))
                        Text(band, color = if (route.statusLabel.startsWith("STALE")) Color(0xFFC7A377) else LM.Accent,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LM.Hairline))
                    Spacer(Modifier.height(14.dp))
                    val stale = route.statusLabel.startsWith("STALE")
                    val boardCall = boarding ?: route.calls.firstOrNull()
                    val leaveCall = alighting ?: route.calls.lastOrNull()
                    val boardClock = JourneySummaryClock.forStop(boardCall, departure = true,
                        preview = route.isPreview, stale = stale,
                        originDeparture = route.departure.takeIf { fullRoute || boardCall?.code == route.originCode })
                    val leaveClock = JourneySummaryClock.forStop(leaveCall, departure = false,
                        preview = route.isPreview, stale = stale)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = if (largeText) 1 else 2) {
                        StationTime(boardCall?.code ?: route.originCode, boardCall?.name ?: route.originName, boardClock, largeText)
                        StationTime(leaveCall?.code ?: route.destinationCode, leaveCall?.name ?: route.destinationName, leaveClock, largeText,
                            daySuffix = summaryDaySuffix(route, boardCall, leaveCall, leaveClock, fullRoute))
                    }

                    }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (expanded && route != null && route.calls.size > 1) {
                        Surface(onClick = onEdit, color = Color(0xFF252830),
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "Edit boarding and alighting stops. Board at ${boarding?.code ?: route.originCode}, leave at ${alighting?.code ?: route.destinationCode}"
                            }) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Board at ${boarding?.code ?: route.originCode} · Leave at ${alighting?.code ?: route.destinationCode}",
                                    color = LM.Ink2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.Edit, contentDescription = null,
                                    tint = LM.Accent, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Surface(color = if (route?.isPreview == true) Color(0xFF24202F) else Color(0xFF172532),
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = Color(0xFFBCA7FF), modifier = Modifier.size(20.dp))
                            Text(when {
                                route == null -> "Search for a dated train run to see its status."
                                route.isPreview -> "Route replay uses historical sample data. Nothing here is live."
                                else -> "${route.statusLabel}. ${route.sourceDetail}"
                            }, color = LM.Ink2,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 11.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (route != null) {
                        Surface(onClick = onAlerts, color = Color(0xFF173346),
                            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
                                .semantics { contentDescription = "Journey alerts" }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = LM.Accent)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text("Journey alerts", color = LM.Ink, fontWeight = FontWeight.SemiBold)
                                    Text(alertStatus ?: if (route.isPreview) "Unavailable for historical previews"
                                        else "Choose station, delay and arrival updates", color = LM.Ink2, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = if (largeText) 1 else 3) {
                            Surface(onClick = onStatusCard,
                                color = if (statusCardEnabled) Color(0xFF173346) else Color(0xFF242832),
                                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).semantics {
                                    contentDescription = if (statusCardEnabled) "Turn status card off" else "Turn status card on"
                                    stateDescription = if (statusCardEnabled) "On" else "Off"
                                }) {
                                Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (statusCardEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone,
                                        contentDescription = null, tint = LM.Accent, modifier = Modifier.size(18.dp))
                                    Text("Status card", color = LM.Ink, fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 5.dp))
                                }
                            }
                            if (!route.isPreview && calendarStart != null && calendarEnd != null) {
                                Surface(onClick = onCalendar,
                                    color = Color(0xFF242832), shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "Add journey to calendar" }) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = LM.Accent,
                                            modifier = Modifier.size(18.dp))
                                        Text("Calendar", color = LM.Ink, fontSize = 13.sp,
                                            modifier = Modifier.padding(start = 7.dp))
                                    }
                                }
                            }
                            Surface(onClick = onShare, color = Color(0xFF242832),
                                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)
                                    .semantics { contentDescription = "Share journey" }) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, tint = LM.Accent,
                                        modifier = Modifier.size(18.dp))
                                    Text("Share", color = LM.Ink, fontSize = 13.sp,
                                        modifier = Modifier.padding(start = 7.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(23.dp))
                    }
                    }
                    }
                    }
                    if (route != null) {
                        item(key = "reliability") {
                            TrainReliabilityCard(reliability, retryReliability)
                            Spacer(Modifier.height(23.dp))
                        }
                        item(key = "timeline-heading") {
                            Text("Station timeline", color = LM.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            Spacer(Modifier.height(12.dp))
                        }
                        itemsIndexed(route.calls, key = { index, stop -> "$index:${stop.code}" }) { index, stop ->
                            TimelineStop(stop, route.isPreview, route.statusLabel.startsWith("STALE"), index == 0)
                        }
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScheduledDepartureCountdown(route: RoutePreview, plan: JourneyPlan?) {
    val boarding = remember(route, plan) { RailTimeText.boardingDeparture(route, plan) } ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(boarding) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(boarding, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = System.currentTimeMillis()
                val remaining = boarding.departureAtMillis - now
                if (remaining <= 0) awaitCancellation()
                delay(minOf(60_000, remaining))
            }
        }
    }
    RailTimeText.untilScheduledDeparture(boarding.departureAtMillis, now)?.let { countdown ->
        val styled = buildAnnotatedString {
            var cursor = 0
            for (match in Regex("[0-9]+").findAll(countdown)) {
                append(countdown.substring(cursor, match.range.first))
                withStyle(SpanStyle(fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = LM.Ink)) {
                    append(match.value)
                }
                cursor = match.range.last + 1
            }
            append(countdown.substring(cursor))
        }
        Text(styled, color = LM.Ink2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
        Spacer(Modifier.height(5.dp))
        Text("${if (route.statusLabel.startsWith("STALE")) "Saved timetable · " else ""}Scheduled boarding at ${boarding.stationCode}",
            color = LM.Ink2, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelineStop(stop: RouteStop, preview: Boolean, stale: Boolean, first: Boolean) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val platform = stop.platform?.trim()?.takeIf {
        !preview && it.isNotEmpty() && it.lowercase() !in setOf("unknown", "n/a", "null", "-", "—", "?")
    }
    val observed = !preview && stop.state == "passed" && (stop.actualArrival != null || stop.actualDeparture != null)
    val forecast = !preview && !observed && stop.forecastP50 != null
    val stateLabel = when {
        preview -> "SCHEDULED · HISTORICAL"
        stale -> "STALE · LAST KNOWN"
        observed -> "OBSERVED"
        forecast -> "PREDICTED · ${stop.forecastSource?.uppercase() ?: "GATEWAY"}"
        else -> "SCHEDULED"
    }
    val stateColor = when {
        preview -> Color(0xFFBCA7FF)
        stale -> Color(0xFFFFB84D)
        observed -> Color(0xFF37C982)
        forecast -> Color(0xFFFFB84D)
        else -> LM.Ink3
    }
    val primaryTime = when {
        observed -> stop.actualArrival ?: stop.actualDeparture
        forecast -> stop.forecastP50
        else -> stop.scheduledArrival ?: stop.scheduledDeparture
    } ?: "—"
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(if (first || stop.state == "current") 12.dp else 8.dp)
                .clip(CircleShape).background(stateColor))
            Box(Modifier.padding(top = 5.dp).size(width = 1.dp, height = 65.dp)
                .background(LM.Hairline))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stop.name, color = LM.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(primaryTime, color = stateColor, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    fontFamily = PlexMono)
            }
            Spacer(Modifier.height(4.dp))
            Text("${stop.code}  ·  $stateLabel", color = stateColor, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, fontFamily = PlexMono, letterSpacing = 0.7.sp)
            if (!preview && stop.delayMinutes != null && (observed || forecast)) {
                val basis = if (stale) "Last known" else if (observed) "Observed" else "Predicted"
                Text("$basis · ${RailTimeText.delay(stop.delayMinutes)}", color = stateColor, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp))
            }
            val detail = when {
                forecast && stop.forecastP10 != null && stop.forecastP90 != null ->
                    "P10 ${stop.forecastP10}  ·  P50 ${stop.forecastP50}  ·  P90 ${stop.forecastP90}"
                observed && stop.scheduledArrival != null -> "Scheduled ${stop.scheduledArrival}"
                else -> null
            }
            if (detail != null) Text(detail, color = LM.Ink2, fontSize = 12.sp, fontFamily = PlexMono,
                modifier = Modifier.padding(top = 5.dp))
            if (forecast && stop.fallbackReason != null) Text(
                "Forecast basis: ${stop.fallbackReason.replace('-', ' ')}", color = LM.Ink3,
                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            if (platform != null && largeText) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.CenterEnd) {
                    PlatformBadge(platform, stale)
                }
            }
        }
        if (platform != null && !largeText) {
            Spacer(Modifier.size(12.dp))
            PlatformBadge(platform, stale)
        }
    }
}

@Composable
private fun PlatformBadge(platform: String, stale: Boolean) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    Surface(color = LM.Elevated, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, LM.Hairline), modifier = Modifier.widthIn(max = if (largeText) 220.dp else 140.dp)
            .semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Platform", color = LM.Ink2, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
            Text(platform, color = LM.Ink, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFamily = PlexMono)
            if (stale) Text("Last known", color = Color(0xFFFFB84D), fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun StationTime(code: String, name: String, clock: JourneySummaryClock, largeText: Boolean, daySuffix: String = "") {
    val tone = when (clock.evidence) {
        JourneySummaryClock.Evidence.Scheduled -> LM.Ink
        JourneySummaryClock.Evidence.Estimated -> Color(0xFFFFB84D)
        JourneySummaryClock.Evidence.Recorded -> Color(0xFF37C982)
    }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = name }) {
        if (largeText) {
            Text(code, color = LM.Ink2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = PlexMono)
            Text("${clock.time ?: "—"}$daySuffix", color = tone, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, color = LM.Ink2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = PlexMono)
                Text("${clock.time ?: "—"}$daySuffix", color = tone, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(clock.label, color = LM.Ink3, fontSize = 11.sp)
    }
}

private fun summaryDaySuffix(route: RoutePreview, board: RouteStop?, leave: RouteStop?,
                             clock: JourneySummaryClock, fullRoute: Boolean): String {
    if (clock.evidence != JourneySummaryClock.Evidence.Scheduled) return ""
    if (route.isPreview && fullRoute) return if (route.arrivalDay > 1) " +${route.arrivalDay - 1}" else ""
    val start = board?.scheduledDepartureMillis ?: route.departureInstantMillis.takeIf { fullRoute } ?: return ""
    val end = leave?.scheduledArrivalMillis ?: return ""
    val india = java.time.ZoneId.of("Asia/Kolkata")
    val days = java.time.temporal.ChronoUnit.DAYS.between(
        java.time.Instant.ofEpochMilli(start).atZone(india).toLocalDate(),
        java.time.Instant.ofEpochMilli(end).atZone(india).toLocalDate())
    return if (days > 0) " +$days" else ""
}
