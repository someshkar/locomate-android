package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.ui.theme.LM
import app.locomate.data.CommunityConsent

@Composable
fun SettingsScreen(productionMode: Boolean, savedCount: Int, onBack: () -> Unit,
                   onOfficialRailway: () -> Unit, onExportData: () -> Unit,
                   onDeleteData: () -> Unit, privacyBusy: Boolean, privacyNotice: String?,
                   contributionEnabled: Boolean, contributionBackground: Boolean,
                   contributionBusy: Boolean, contributionNotice: String?,
                   withdrawalRetryNeeded: Boolean,
                   onContributionGrant: () -> Unit, onContributionRevoke: () -> Unit,
                   onContributionBackground: (Boolean) -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showGrantConfirm by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color(0xFF090A0D)).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(23.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to Passport", tint = LM.Ink)
            }
            Text("Settings", color = LM.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text("Rail data, privacy and source labels", color = LM.Ink2, fontSize = 14.sp)
        Spacer(Modifier.height(30.dp))
        SettingsCard("RAIL DATA SOURCE") {
            Text(if (productionMode) "Gateway configured" else "Historical route preview",
                color = LM.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(if (productionMode)
                "Dated runs and network trains come from the rail gateway. Each screen labels scheduled, observed, predicted and stale information."
                else "Preview routes come from a historical timetable snapshot. They are never presented as live positions or current ETAs.",
                color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard("ON THIS DEVICE") {
            Text("$savedCount saved journey${if (savedCount == 1) "" else "s"}",
                color = LM.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Passport is stored locally. It contains train and station summaries, without PNR, seat, or personal location. Remove a journey from Passport at any time.",
                color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard("HOW TO READ STATUS") {
            StatusLegend("OBSERVED", "A recorded train event", Color(0xFF37C982))
            StatusLegend("PREDICTED", "A forecast, shown with its available range", Color(0xFFFFB84D))
            StatusLegend("SCHEDULED", "A published timetable time", LM.Ink2)
            StatusLegend("STALE", "Last saved data while refresh is unavailable", Color(0xFFFFB84D))
            StatusLegend("PREVIEW", "Historical sample, never live", Color(0xFFBCA7FF))
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard("COMMUNITY CONTRIBUTION") {
            Text("Optional onboard positions for a current production journey you open.",
                color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp),
                color = if (productionMode) Color(0xFF162B3D) else Color(0xFF272A30),
                modifier = Modifier.fillMaxWidth().clickable(enabled = productionMode && !contributionBusy) {
                    if (contributionEnabled) showRevokeConfirm = true else showGrantConfirm = true
                }) {
                Text(when {
                    !productionMode -> "Contribution unavailable in preview"
                    contributionEnabled -> "Stop contribution and withdraw consent"
                    else -> "Enable contribution"
                },
                    color = if (productionMode) LM.Ink else LM.Ink2,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp))
            }
            if (contributionEnabled) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF162B3D),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !contributionBusy) {
                        onContributionBackground(!contributionBackground)
                    }) {
                    Text(if (contributionBackground) "Background contribution: on" else "Background contribution: off",
                        color = LM.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp))
                }
            }
            if (withdrawalRetryNeeded && !contributionEnabled) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF352020),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !contributionBusy,
                        onClick = onContributionRevoke)) {
                    Text("Retry withdrawal and local cleanup", color = Color(0xFFFFB3B3),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(if (productionMode)
                "Only map-matched, plausible train movement is sent. The foreground service shows an ongoing notification; background sharing continues only when enabled."
                else "A production rail gateway is required. Historical previews never contribute.",
                color = LM.Ink2, fontSize = 13.sp, lineHeight = 18.sp)
            if (contributionNotice != null) {
                Spacer(Modifier.height(10.dp))
                Text(contributionNotice, color = LM.Ink2, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsCard("YOUR DATA") {
            Text("Export your gateway record and this device's saved journeys, plans, and cached runs. The file can contain location and session tokens; share it only with a destination you trust.",
                color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF162B3D),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !privacyBusy, onClick = onExportData)) {
                Text(if (privacyBusy) "Working…" else "Export my data", color = LM.Ink,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF352020),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !privacyBusy) {
                    showDeleteConfirm = true
                }) {
                Text("Delete my data", color = Color(0xFFFFB3B3),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp))
            }
            if (privacyNotice != null) {
                Spacer(Modifier.height(10.dp))
                Text(privacyNotice, color = LM.Ink2, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(25.dp))
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF162B3D),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOfficialRailway)) {
            Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Open official NTES", color = LM.Ink, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = LM.Accent)
            }
        }
        Spacer(Modifier.height(140.dp))
    }
    if (showDeleteConfirm) AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("Delete all Locomate data?") },
        text = { Text("This removes your gateway installation, saved journeys, cached runs, and status card. It cannot be undone.") },
        confirmButton = {
            TextButton(onClick = { showDeleteConfirm = false; onDeleteData() }) {
                Text("Delete server and device data", color = Color(0xFFD64949))
            }
        },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
    )
    if (showGrantConfirm) AlertDialog(
        onDismissRequest = { showGrantConfirm = false },
        title = { Text("Enable community contribution?") },
        text = { Text(CommunityConsent.NOTICE) },
        confirmButton = {
            TextButton(onClick = { showGrantConfirm = false; onContributionGrant() }) {
                Text("I consent and enable")
            }
        },
        dismissButton = { TextButton(onClick = { showGrantConfirm = false }) { Text("Cancel") } },
    )
    if (showRevokeConfirm) AlertDialog(
        onDismissRequest = { showRevokeConfirm = false },
        title = { Text("Withdraw contribution consent?") },
        text = { Text("Collection stops immediately and queued observations are erased from this device.") },
        confirmButton = {
            TextButton(onClick = { showRevokeConfirm = false; onContributionRevoke() }) {
                Text("Withdraw and delete", color = Color(0xFFD64949))
            }
        },
        dismissButton = { TextButton(onClick = { showRevokeConfirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsCard(label: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF191B21),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(label, color = LM.Ink3, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp)
            Spacer(Modifier.height(13.dp))
            content()
        }
    }
}

@Composable
private fun StatusLegend(label: String, detail: String, color: Color) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(detail, color = LM.Ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
