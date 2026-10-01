package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.SavedJourney
import app.locomate.data.PassportMetrics
import app.locomate.data.PassportPeriods
import app.locomate.data.openUnavailableReason
import app.locomate.ui.theme.LM

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PassportScreen(savedRoutes: List<SavedJourney>, notice: String? = null, onRemove: (String) -> Unit,
                   onOpen: (SavedJourney) -> Unit, onSettings: () -> Unit) {
    val years = remember(savedRoutes) { PassportPeriods.years(savedRoutes) }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    val year = selectedYear?.takeIf { it in years }
    LaunchedEffect(years) { if (selectedYear != null && selectedYear !in years) selectedYear = null }
    val filteredRoutes = remember(savedRoutes, year) { PassportPeriods.filter(savedRoutes, year) }
    val savedRuns = filteredRoutes.filterNot { it.preview }
    val metrics = PassportMetrics.from(filteredRoutes)
    val periodColors = FilterChipDefaults.filterChipColors(
        containerColor = LM.Raised,
        labelColor = LM.Ink2,
        selectedContainerColor = LM.Accent.copy(alpha = 0.16f),
        selectedLabelColor = LM.Accent,
    )
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF171A2A), Color(0xFF090B11), Color(0xFF090B11)))
        ).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Passport", color = LM.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Open settings", tint = LM.Ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Your saved rail runs, kept privately on this device.", color = LM.Ink2, fontSize = 15.sp, lineHeight = 21.sp)
        if (notice != null) {
            Spacer(Modifier.height(13.dp))
            Text(notice, color = Color(0xFFFFB84D), fontSize = 13.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(20.dp))
        Text("Train origin year", color = LM.Ink2, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = year == null, onClick = { selectedYear = null }, label = { Text("All-Time") },
                shape = CircleShape, colors = periodColors, border = null, modifier = Modifier.heightIn(min = 48.dp))
            years.forEach { option ->
                FilterChip(selected = year == option, onClick = { selectedYear = option }, label = { Text(option.toString()) },
                    shape = CircleShape, colors = periodColors, border = null, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(
            color = Color(0xFF272235),
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            shadowElevation = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(25.dp)) {
                Text("SAVED RUNS · ${year ?: "ALL-TIME"}", color = Color(0xFFC7BBE7), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(17.dp))
                Text(if (savedRuns.isEmpty()) "Your first run awaits"
                    else if (metrics.knownDistanceKm != null) "${metrics.knownDistanceKm} km" else "Distance unavailable", color = LM.Ink,
                    fontSize = if (metrics.knownDistanceKm != null) 43.sp else 29.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (savedRuns.isNotEmpty()) "Distance in saved runs. This is not verified travel history."
                    else if (metrics.previewCount > 0) "${metrics.previewCount} sample route${if (metrics.previewCount == 1) "" else "s"} saved separately. Preview routes are not counted as travel."
                    else "Save a dated journey to start your private collection.",
                    color = LM.Ink2, fontSize = 14.sp, lineHeight = 21.sp)
                if (savedRuns.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        maxItemsInEachRow = if (LocalDensity.current.fontScale >= 1.5f) 1 else 3) {
                        PassportMetric("SAVED RUNS", metrics.runCount.toString(), Modifier.weight(1f))
                        PassportMetric("SCHEDULED", metrics.scheduledDurationLabel, Modifier.weight(1f))
                        PassportMetric("STATIONS", metrics.stationCount.toString(), Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("Saved journeys", color = LM.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(15.dp))
        if (filteredRoutes.isNotEmpty()) {
            filteredRoutes.forEach { route ->
            val unavailableReason = route.openUnavailableReason()
            Surface(
                color = Color(0xFF1A1C22),
                shape = RoundedCornerShape(23.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clickable(enabled = unavailableReason == null) { onOpen(route) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Train, contentDescription = null, tint = LM.Accent)
                    Column(Modifier.weight(1f)) {
                        Text(route.trainName, color = LM.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${route.trainNumber} · ${route.originCode} → ${route.destinationCode} · ${if (route.preview) "Preview" else route.originDate ?: "Origin date unavailable"}",
                            color = LM.Ink2, fontSize = 12.sp)
                        if (unavailableReason != null) Text(unavailableReason, color = LM.Ink2,
                            fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    IconButton(onClick = { onRemove(route.key) }) {
                        Icon(Icons.Outlined.DeleteOutline,
                            contentDescription = "Remove ${route.trainNumber}, ${route.originCode} to ${route.destinationCode}, ${if (route.preview) "preview" else route.originDate ?: "dated run"}",
                            tint = LM.Ink2)
                    }
                }
            }
            }
        } else {
            Surface(color = Color(0xFF1A1C22), shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth()) {
                Text("No journeys saved yet", color = LM.Ink2, fontSize = 15.sp, modifier = Modifier.padding(22.dp))
            }
        }
        Spacer(Modifier.height(140.dp))
    }
}

@Composable
private fun PassportMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, color = LM.Ink3, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp)
        Spacer(Modifier.height(5.dp))
        Text(value, color = LM.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
