package app.locomate.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.locomate.data.JourneyAlertChannel
import app.locomate.data.JourneyAlertConsent
import app.locomate.data.JourneyAlertQuietHours
import app.locomate.data.JourneyAlertSubscription
import app.locomate.data.RoutePreview

@Composable
fun JourneyAlertsDialog(route: RoutePreview, subscription: JourneyAlertSubscription?, pushAvailable: Boolean,
                        onDismiss: () -> Unit, onDisable: () -> Unit, onNotificationSettings: () -> Unit,
                        onSave: (Set<JourneyAlertChannel>, JourneyAlertQuietHours?) -> Unit) {
    var channels by remember(route.runId) {
        mutableStateOf(subscription?.channels?.takeIf { it.isNotEmpty() } ?: JourneyAlertChannel.entries.toSet())
    }
    var quietEnabled by remember(route.runId) { mutableStateOf(subscription?.quietHours != null) }
    var quietStart by remember(route.runId) { mutableStateOf(subscription?.quietHours?.start ?: "22:00") }
    var quietEnd by remember(route.runId) { mutableStateOf(subscription?.quietHours?.end ?: "07:00") }
    val quiet = if (quietEnabled) JourneyAlertQuietHours(quietStart, quietEnd) else null
    val valid = channels.isNotEmpty() && (quiet == null || quiet.valid())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alerts for ${route.trainNumber}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${route.runDate} · ${route.displayName}")
                Spacer(Modifier.height(12.dp))
                Text(JourneyAlertConsent.NOTICE)
                Spacer(Modifier.height(12.dp))
                JourneyAlertChannel.entries.forEach { channel ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = channel in channels,
                        role = Role.Checkbox, onValueChange = { checked ->
                            channels = if (checked) channels + channel else channels - channel
                        }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = channel in channels, onCheckedChange = null)
                        Text(channel.label)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily quiet hours", modifier = Modifier.weight(1f))
                    Switch(checked = quietEnabled, onCheckedChange = { quietEnabled = it },
                        modifier = Modifier.semantics { contentDescription = "Daily quiet hours" })
                }
                if (quietEnabled) {
                    Text("India time · alerts during these hours are skipped.")
                    OutlinedTextField(quietStart, { quietStart = it.take(5) }, label = { Text("From (HH:mm)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(quietEnd, { quietEnd = it.take(5) }, label = { Text("Until (HH:mm)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (quiet?.valid() != true) Text("Use different 24-hour times, for example 22:00 and 07:00.")
                }
                if (channels.isEmpty()) Text("Choose at least one alert.")
                if (subscription != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(subscription.status)
                }
                if (!pushAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Text("Push delivery is not configured in this build. Alerts cannot be enabled yet.")
                }
                TextButton(onClick = onNotificationSettings) { Text("Android notification settings") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && pushAvailable, onClick = { onSave(channels, quiet) }) {
                Text(if (subscription?.active() == true) "Save alert choices" else "I agree · enable alerts")
            }
        },
        dismissButton = {
            Row {
                if (subscription?.enabled == true) TextButton(onClick = onDisable) { Text("Turn off") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
