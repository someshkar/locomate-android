package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.locomate.data.*
import app.locomate.ui.theme.LM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhysicalReportOutboxCard(gateway: RailGateway?, withdrawalPending: Boolean) {
    if (gateway?.configured != true) return
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val queue = remember(context, gateway) { PhysicalReportQueue(context, gateway.sourceUrl) }
    var epoch by remember { mutableIntStateOf(0) }
    var records by remember { mutableStateOf<List<QueuedPhysicalReport>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renewal by remember { mutableStateOf<QueuedPhysicalReport?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {
        val listener = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) epoch++ }
        owner.lifecycle.addObserver(listener)
        onDispose { owner.lifecycle.removeObserver(listener) }
    }
    LaunchedEffect(epoch, gateway) {
        try { records = queue.pending() }
        catch (error: Exception) { records = emptyList(); notice = "Pending reports could not be read. Withdraw reporting consent to erase the local outbox." }
    }
    suspend fun send(report: QueuedPhysicalReport) {
        busy = true; notice = null
        try {
            if (!queue.contains(report)) return
            CommunitySync(CommunityQueue(context, gateway.sourceUrl), gateway,
                CommunityPreferences(context, gateway.sourceUrl)).flushWithdrawals()
            val result = gateway.submitQueuedPhysicalReport(report)
            queue.remove(report.key, report.installation)
            notice = "${result.message} Locomotive ${result.locomotiveState}; rake ${result.rakeState}."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice = "Report remains pending. ${(error as? GatewayError)?.message ?: "Retry when the gateway is available."}" }
        finally { busy = false; epoch++ }
    }
    Surface(color = LM.Elevated, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, LM.Hairline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pending public-number reports", color = LM.Ink, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            Text("Exact reports and retry keys survive closing the app. Automatic retry uses only your original 15-minute consent window. Expired consent requires renewal; old observations may require a new sighting.", color = LM.Ink2, fontSize = 13.sp)
            if (withdrawalPending) Text("Finish withdrawal or local cleanup before retrying reports.", color = LM.Warn, fontSize = 13.sp)
            if (records.isEmpty()) Text("No pending reports", color = LM.Ink2, fontSize = 13.sp)
            records.forEach { report ->
                Text("${report.trainNumber} · ${report.date}", color = LM.Ink, fontSize = 14.sp)
                Text(report.sightings().joinToString("; ") { "${it.kind.name} ${it.identifier}" }, color = LM.Ink2, fontSize = 13.sp)
                Text(if (report.hasCurrentConsent()) "Consent current · ready to retry" else "Consent expired · no automatic submission", color = LM.Ink3, fontSize = 12.sp)
                FlowRow {
                    if (report.hasCurrentConsent()) TextButton(enabled = !busy && !withdrawalPending,
                        onClick = { scope.launch { send(report) } }) { Text("Retry exact report") }
                    else TextButton(enabled = !busy && !withdrawalPending && report.canRenew(), onClick = { renewal = report }) { Text("Review and renew consent") }
                    TextButton(enabled = !busy, onClick = {
                        try { queue.remove(report.key, gateway.reportInstallationGeneration()); epoch++ }
                        catch (_: Exception) { notice = "Could not remove this pending report. Retry withdrawal or Delete my data." }
                    }) { Text("Remove pending report") }
                }
            }
            notice?.let { Text(it, color = LM.Ink2, fontSize = 13.sp) }
            TextButton(onClick = { epoch++ }, enabled = !busy) { Text("Refresh pending reports") }
        }
    }
    renewal?.let { report -> AlertDialog(onDismissRequest = { renewal = null },
        title = { Text("Renew report consent?") },
        text = { Text("${CommunityConsent.NOTICE}\n\nRenew consent for the saved public numbers on ${report.trainNumber}, ${report.date}. Original observation times stay unchanged. No location sharing is enabled.",
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            renewal = null
            scope.launch {
                try {
                    val next = queue.stage(report.trainNumber, report.date,
                        PhysicalSightingBatch(report.sightings(), System.currentTimeMillis()),
                        gateway.reportInstallationGeneration(), replacing = report.key)
                    send(next)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "Consent renewal could not be saved. Your old report remains pending." }
                finally { epoch++ }
            }
        }) { Text("Agree, renew and retry") } },
        dismissButton = { TextButton(onClick = { renewal = null }) { Text("Cancel") } }) }
}
