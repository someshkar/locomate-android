package app.locomate.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.RoutePreview
import app.locomate.data.RouteStop
import app.locomate.data.JourneyPlan
import app.locomate.ui.theme.LM
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun JourneyScreen(route: RoutePreview?, plan: JourneyPlan? = null, saved: Boolean, productionMode: Boolean = false,
                  message: String? = null, onSave: () -> Unit,
                  onCalendar: () -> Unit = {}, onShare: () -> Unit = {}, onEdit: () -> Unit = {}) {
    val segment = plan?.takeIf { route != null && it.isValidFor(route) }
    val boarding = route?.calls?.firstOrNull { it.code == segment?.boardingCode }
    val alighting = route?.calls?.firstOrNull { it.code == segment?.alightingCode }
    val fullRoute = route != null && (segment == null || segment == JourneyPlan.default(route))
    val calendarStart = if (fullRoute) route?.departureInstantMillis
        else boarding?.scheduledDepartureMillis ?: boarding?.scheduledArrivalMillis
    val calendarEnd = if (fullRoute) route?.arrivalInstantMillis else alighting?.scheduledArrivalMillis
    val segmentLabel = if (fullRoute) route?.routeLabel else if (boarding != null && alighting != null)
        "${boarding.name} to ${alighting.name}" else route?.routeLabel
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080B12))) {
        RailMap(route)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.42f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.68f),
                )
            )
        )

        Surface(
            color = Color(0xB51D2028),
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 16.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
        ) {
            Row(Modifier.padding(horizontal = 17.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(route?.trainNumber ?: "LOCOMATE", color = LM.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(if (productionMode) "  ·  RAIL GATEWAY" else "  ·  ROUTE PREVIEW",
                    color = LM.Ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
        }

        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val collapsedTop = screenHeightPx * 0.47f
        val expandedTop = screenHeightPx * 0.10f
        var sheetTop by remember(screenHeightPx) { mutableFloatStateOf(collapsedTop) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        Surface(
            color = Color(0xF51A1C22),
            shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
            shadowElevation = 28.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
                .height(maxHeight * 0.90f)
                .offset { IntOffset(0, sheetTop.roundToInt()) }
        ) {
            Column {
                Column(
                    Modifier.fillMaxWidth().pointerInput(collapsedTop, expandedTop) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onVerticalDrag = { change, amount ->
                                sheetTop = (sheetTop + amount).coerceIn(expandedTop, collapsedTop)
                                change.consume()
                            },
                            onDragEnd = {
                                val target = if (sheetTop < (collapsedTop + expandedTop) / 2f) expandedTop else collapsedTop
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
                    Box(Modifier.fillMaxWidth().height(23.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.42f)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("My Journeys", color = LM.Ink, fontSize = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Surface(onClick = onSave, enabled = route != null,
                            color = Color.White.copy(alpha = 0.09f), shape = CircleShape) {
                            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = if (saved) "Remove saved journey" else "Save journey",
                                    tint = LM.Ink, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(25.dp))
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (route == null) "Route pack unavailable" else "${route.trainNumber} · ${route.displayName}",
                            color = LM.Ink2, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(route?.statusLabel ?: "SEARCH TO START", color = if (route?.isPreview == true) Color(0xFFBCA7FF) else LM.Accent,
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(segmentLabel ?: "Find your train", color = LM.Ink, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(message ?: route?.sourceDetail ?: "Search for a dated train run to see its status.", color = LM.Ink3, fontSize = 13.sp)
                    route?.etaBand?.let { band ->
                        Spacer(Modifier.height(8.dp))
                        Text(band, color = if (route.statusLabel.startsWith("STALE")) Color(0xFFC7A377) else LM.Accent,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(27.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LM.Hairline))
                    Spacer(Modifier.height(21.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StationTime(boarding?.code ?: route?.originCode ?: "—",
                            if (fullRoute) route?.departure ?: "—" else boarding?.scheduledDeparture ?: boarding?.scheduledArrival ?: "—",
                            "Scheduled boarding", if (route?.isPreview == true) Color(0xFFBCA7FF) else LM.Ink)
                        Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = LM.Ink3, modifier = Modifier.size(20.dp))
                        val arrivalTime = if (fullRoute) route?.let { it.arrival + if (it.arrivalDay > 1) " +${it.arrivalDay - 1}" else "" } ?: "—"
                            else alighting?.forecastP50 ?: alighting?.scheduledArrival ?: "—"
                        StationTime(alighting?.code ?: route?.destinationCode ?: "—", arrivalTime,
                            if (!fullRoute && alighting?.forecastP50 != null) "Predicted arrival" else "Arrival",
                            if (route?.isPreview == true) Color(0xFFBCA7FF)
                            else if (alighting?.forecastP50 != null || route?.statusLabel?.startsWith("PREDICTED") == true) Color(0xFFFFB84D)
                            else LM.Ink)
                    }
                    Spacer(Modifier.height(21.dp))
                    if (route != null && route.calls.size > 1) {
                        Surface(onClick = onEdit, color = Color(0xFF252830),
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Board at ${boarding?.code ?: route.originCode} · Leave at ${alighting?.code ?: route.destinationCode}",
                                    color = LM.Ink2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Outlined.Edit, contentDescription = "Edit boarding and alighting stops",
                                    tint = LM.Accent, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Surface(color = if (route?.isPreview != false) Color(0xFF24202F) else Color(0xFF172532),
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Info, contentDescription = null, tint = Color(0xFFBCA7FF), modifier = Modifier.size(20.dp))
                            Text(if (route?.isPreview != false) "Route replay uses historical sample data. Nothing here is live."
                                else "${route.statusLabel}. ${route.sourceDetail}", color = LM.Ink2,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 11.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (route != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!route.isPreview && calendarStart != null && calendarEnd != null) {
                                Surface(onClick = onCalendar,
                                    color = Color(0xFF242832), shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.weight(1f)) {
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
                                shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
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
                    Text("Station timeline", color = LM.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    route?.calls.orEmpty().forEachIndexed { index, stop ->
                        TimelineStop(stop, route?.isPreview == true, route?.statusLabel?.startsWith("STALE") == true, index == 0)
                    }
                    Spacer(Modifier.height(160.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineStop(stop: RouteStop, preview: Boolean, stale: Boolean, first: Boolean) {
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
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
            Box(Modifier.size(if (first || stop.state == "current") 12.dp else 8.dp)
                .clip(CircleShape).background(stateColor))
            Box(Modifier.padding(top = 5.dp).size(width = 1.dp, height = 65.dp)
                .background(LM.Hairline))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stop.name, color = LM.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(primaryTime, color = stateColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text("${stop.code}  ·  $stateLabel", color = stateColor, fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
            val detail = when {
                forecast && stop.forecastP10 != null && stop.forecastP90 != null ->
                    "P10 ${stop.forecastP10}  ·  P50 ${stop.forecastP50}  ·  P90 ${stop.forecastP90}"
                observed && stop.scheduledArrival != null -> "Scheduled ${stop.scheduledArrival}"
                else -> null
            }
            if (detail != null) Text(detail, color = LM.Ink2, fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp))
            if (stop.platform != null && !preview) Text("Platform ${stop.platform}", color = LM.Ink3,
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (forecast && stop.fallbackReason != null) Text(
                "Forecast basis: ${stop.fallbackReason.replace('-', ' ')}", color = LM.Ink3,
                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StationTime(code: String, time: String, label: String, tone: Color) {
    Column {
        Text(code, color = LM.Ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(time, color = tone, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = LM.Ink3, fontSize = 12.sp)
    }
}
