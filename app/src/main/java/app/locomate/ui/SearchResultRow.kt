package app.locomate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.PlexMono
import java.text.NumberFormat

/** Compact Doop row; catalogue provenance never claims the dated train is on time. */
@Composable
internal fun SearchResultRow(number: String, name: String, originCode: String, destinationCode: String,
    originName: String, destinationName: String, source: String, distanceKm: Double,
    showSeparator: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    val enlarged = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button,
        onClickLabel = "Open $number", onClick = onSelect)) {
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(number, color = LM.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = PlexMono)
                Text(name, color = LM.Ink2, fontSize = 12.5.sp)
                Text("$originCode → $destinationCode", color = LM.Ink2, fontSize = 12.5.sp, fontFamily = PlexMono,
                    modifier = Modifier.semantics { contentDescription = "$originName to $destinationName" })
                if (distanceKm > 0 && distanceKm.isFinite()) {
                    Text("${NumberFormat.getIntegerInstance().format(distanceKm.toLong())} km", color = LM.Ink3,
                        fontSize = 12.5.sp, fontFamily = PlexMono)
                }
            }
        }
        val provenance: @Composable () -> Unit = {
            Text(source, color = LM.Ink3, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = if (enlarged) Modifier.fillMaxWidth() else Modifier.widthIn(max = 124.dp))
        }
        if (enlarged) Column(Modifier.padding(horizontal = 2.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { details(); provenance() }
        else Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { details() }
            provenance()
        }
        if (showSeparator) HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
    }
}
