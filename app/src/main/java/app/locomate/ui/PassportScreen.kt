package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.SavedJourney
import app.locomate.data.PassportMetrics
import app.locomate.ui.theme.LM

@Composable
fun PassportScreen(savedRoutes: List<SavedJourney>, notice: String? = null, onRemove: (String) -> Unit,
                   onOpen: (SavedJourney) -> Unit, onSettings: () -> Unit) {
    val savedRuns = savedRoutes.filterNot { it.preview }
    val metrics = PassportMetrics.from(savedRoutes)
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF171A2A), Color(0xFF090B11), Color(0xFF090B11)))
        ).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Passport", color = LM.Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
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
        Spacer(Modifier.height(29.dp))
        Surface(
            color = Color(0xFF272235),
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            shadowElevation = 20.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(25.dp)) {
                Text("SAVED RUNS · AS OF TODAY", color = Color(0xFFC7BBE7), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
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
                    Text("${metrics.runCount} RUNS      ${metrics.stationCount} STATIONS      ${metrics.knownScheduledHours?.let { "${it}H SCHEDULED" } ?: "TIME UNAVAILABLE"}",
                        color = LM.Ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("Saved journeys", color = LM.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(15.dp))
        if (savedRoutes.isNotEmpty()) {
            savedRoutes.forEach { route ->
            Surface(
                color = Color(0xFF1A1C22),
                shape = RoundedCornerShape(23.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onOpen(route) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Train, contentDescription = null, tint = LM.Accent)
                    Column(Modifier.weight(1f)) {
                        Text(route.trainName, color = LM.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("${route.trainNumber} · ${route.originCode} → ${route.destinationCode} · ${if (route.preview) "Preview" else route.originDate ?: "Dated run"}",
                            color = LM.Ink2, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onRemove(route.key) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove saved journey", tint = LM.Ink2)
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
