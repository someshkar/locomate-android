package app.locomate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.PlexMono
import app.locomate.ui.theme.pressScale
import java.text.NumberFormat

private val clock = Regex("""\A(?:[01][0-9]|2[0-3]):[0-5][0-9]""")

/**
 * Doop result row: the train leads, the timetable clocks sit at a glance on the
 * right, and catalogue provenance is one quiet line that never claims the dated
 * train is on time.
 */
@Composable
internal fun SearchResultRow(number: String, name: String, originCode: String, destinationCode: String,
    originName: String, destinationName: String, source: String, distanceKm: Double,
    showSeparator: Boolean, enabled: Boolean = true, departure: String? = null, arrival: String? = null,
    modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val enlarged = LocalDensity.current.fontScale >= 1.5f
    val press = remember { MutableInteractionSource() }
    val dep = departure?.let { clock.find(it)?.value }
    val arr = arrival?.let { clock.find(it)?.value }
    Column(modifier.fillMaxWidth().pressScale(press, 0.98f).clickable(enabled = enabled, role = Role.Button,
        interactionSource = press, indication = null, onClickLabel = "Open $number", onClick = onSelect)) {
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(number, color = LM.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = PlexMono)
                Text(name, color = LM.Ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (enlarged) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                val distance = if (distanceKm > 0 && distanceKm.isFinite())
                    " · ${NumberFormat.getIntegerInstance().format(distanceKm.toLong())} km" else ""
                Text("$originCode → $destinationCode$distance", color = LM.Ink3, fontSize = 12.5.sp, fontFamily = PlexMono,
                    modifier = Modifier.semantics { contentDescription = "$originName to $destinationName$distance" })
                Text(source, color = LM.Ink3.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (enlarged) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val schedule: @Composable () -> Unit = {
            if (dep != null && arr != null) Column(horizontalAlignment = if (enlarged) Alignment.Start else Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Scheduled departure $dep, arrival $arr" }) {
                Text(dep, color = LM.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("arr $arr", color = LM.Ink3, fontSize = 12.5.sp, fontFamily = PlexMono)
            }
        }
        if (enlarged) Column(Modifier.padding(horizontal = 2.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { details(); schedule() }
        else Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { details() }
            schedule()
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = LM.Ink3,
                modifier = Modifier.size(20.dp))
        }
        if (showSeparator) HorizontalDivider(color = LM.Ink.copy(alpha = 0.05f))
    }
}
