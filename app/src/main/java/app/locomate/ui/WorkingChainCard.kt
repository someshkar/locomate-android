package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.locomate.data.*
import app.locomate.ui.theme.LM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable internal fun WorkingChainCard(route: RoutePreview, gateway: RailGateway?,
                                         onOpenJourney: (String, String) -> Unit) {
    if (route.isPreview) return
    var epoch by remember(route.runId, gateway) { mutableIntStateOf(0) }
    var operational by remember(route.runId, gateway) { mutableStateOf<OperationalChain?>(null) }
    var physical by remember(route.runId, gateway) { mutableStateOf<PhysicalChain?>(null) }
    var operationalError by remember(route.runId, gateway) { mutableStateOf<String?>(null) }
    var physicalError by remember(route.runId, gateway) { mutableStateOf<String?>(null) }
    var loading by remember(route.runId, gateway) { mutableStateOf(false) }
    var reporting by remember(route.runId) { mutableStateOf(false) }
    LaunchedEffect(route.runId, gateway, epoch) {
        if (gateway?.configured != true || route.runDate == null) return@LaunchedEffect
        loading = true
        operationalError = null; physicalError = null
        try {
            try { operational = gateway.operationalChain(route.trainNumber, route.runDate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { operational = null; operationalError = (error as? GatewayError)?.message ?: "Operational continuity is temporarily unavailable. Try again." }
            try { physical = gateway.physicalChain(route.trainNumber, route.runDate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { physical = null; physicalError = (error as? GatewayError)?.message ?: "Physical identity is temporarily unavailable. Try again." }
        } finally { loading = false }
    }
    Surface(color = LM.Elevated, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, LM.Hairline)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Working connections", color = LM.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            Text("Operational continuity", color = LM.Ink, fontWeight = FontWeight.SemiBold)
            Text("Timetable links may be inferred. They do not establish the physical rake or locomotive.", color = LM.Ink2, fontSize = 13.sp)
            operational?.let { chain ->
                Text("${chain.availability} · ${chain.mode} · Updated ${chain.updatedAt}", color = LM.Ink3, fontSize = 12.sp)
                Text(chain.disclaimer, color = LM.Ink2, fontSize = 13.sp)
                chain.runs.forEach { (role, run) -> WorkingRunRow(role, run, onOpenJourney) }
                listOfNotNull(chain.linkage, chain.delay, chain.turnaround).forEach { Text(it, color = LM.Ink2, fontSize = 13.sp) }
            }
            operationalError?.let { Text(it, color = LM.Warn, fontSize = 13.sp) }
            HorizontalDivider(color = LM.Hairline)
            Text("Physical identity", color = LM.Ink, fontWeight = FontWeight.SemiBold)
            physical?.let { chain ->
                Text("${chain.state} · As of ${java.time.Instant.ofEpochMilli(chain.asOfMillis.toLong())}", color = LM.Ink3, fontSize = 12.sp)
                chain.assets.forEach { asset ->
                    Text("${asset.kind.replaceFirstChar { it.titlecase() }} · ${asset.state}", color = LM.Ink, fontSize = 14.sp)
                    asset.unavailableReason?.let { Text("No confirmed identity · ${it.replace('-', ' ')}", color = LM.Ink2, fontSize = 13.sp) }
                    Text("Source ${asset.evidence.source ?: "unavailable"} · ${asset.evidence.freshness}" +
                        (asset.evidence.confidence?.let { " · confidence ${(it * 100).toInt()}%" } ?: ""), color = LM.Ink3, fontSize = 12.sp)
                    asset.runs.forEach { (role, run) -> WorkingRunRow(role, run, onOpenJourney) }
                    listOf("Inbound" to asset.inbound, "Outbound" to asset.outbound).forEach { (label, link) ->
                        Text("$label ${link.state} · ${link.reason.replace('-', ' ')} · ${link.evidence.freshness}",
                            color = if (link.state == "broken") LM.Warn else LM.Ink2, fontSize = 12.sp)
                    }
                }
            }
            physicalError?.let { Text(it, color = LM.Warn, fontSize = 13.sp) }
            if (loading) Text("Loading working evidence…", color = LM.Ink2, fontSize = 13.sp)
            if (operationalError != null || physicalError != null) TextButton(onClick = { epoch++ }, enabled = !loading) { Text("Retry working evidence") }
            Text("Public-number sightings are proposed community evidence. A report does not immediately confirm identity.", color = LM.Ink2, fontSize = 13.sp)
            TextButton(onClick = { reporting = true }, enabled = gateway?.configured == true && !route.statusLabel.startsWith("STALE")) {
                Text("Report a public locomotive or coach number")
            }
        }
    }
    if (reporting && gateway != null) PhysicalSightingDialog(route, gateway, { reporting = false }, { epoch++ })
}

@Composable private fun WorkingRunRow(role: String, run: WorkingRun, onOpen: (String, String) -> Unit) {
    Column {
        Text("${role.replaceFirstChar { it.titlecase() }} · ${run.trainNumber} · ${run.date} · ${run.status}", color = LM.Ink, fontSize = 13.sp)
        Text("${run.route} · ${run.timing}", color = LM.Ink2, fontSize = 12.sp)
        if (role != "current") TextButton(onClick = { onOpen(run.trainNumber, run.date) }) { Text("Open ${run.trainNumber} on ${run.date}") }
    }
}

@Composable internal fun PhysicalSightingDialog(route: RoutePreview, gateway: RailGateway,
                                               onDismiss: () -> Unit, onAccepted: () -> Unit) {
    var kind by remember { mutableStateOf(SightingKind.Locomotive) }
    var identifier by remember { mutableStateOf("") }
    var station by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    var batch by remember { mutableStateOf<PhysicalSightingBatch?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val queue = remember(context, gateway) { PhysicalReportQueue(context, gateway.sourceUrl) }
    var queued by remember { mutableStateOf<QueuedPhysicalReport?>(null) }
    val valid = PhysicalSighting(kind, identifier, System.currentTimeMillis(), station.takeIf { it.isNotBlank() }).validIdentifier &&
        (station.isBlank() || RailStationCode.isValid(station))
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = LM.Elevated,
            modifier = Modifier.fillMaxWidth().padding(18.dp).heightIn(max = 700.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Report public physical ID", color = LM.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text("Train ${route.trainNumber} · ${route.runDate}", color = LM.Ink2)
                Text("Read a locomotive's painted five-digit number, or a coach's 5–12 digit manufacturer plate. B1, A1 and S1 are personal coach labels and cannot identify a physical coach.", color = LM.Ink2, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SightingKind.entries.forEach { option -> FilterChip(selected = kind == option, enabled = !busy && !sent,
                        onClick = { kind = option; identifier = ""; batch = null; queued = null }, label = { Text(option.name) }) }
                }
                OutlinedTextField(identifier, { if (it.length <= 12 && it.all(Char::isDigit)) { identifier = it; batch = null; queued = null } },
                    enabled = !busy && !sent, label = { Text("Public number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(station, { if (it.length <= 10 && it.all { c -> c.isLetterOrDigit() || c == '-' }) { station = it.uppercase(); batch = null; queued = null } },
                    enabled = !busy && !sent, label = { Text("Station code (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(CommunityConsent.NOTICE, color = LM.Ink2, fontSize = 13.sp)
                Text("This sends the public number, current report time, optional station and explicit consent to the gateway. No photo, GPS, PNR, private coach/seat or notes. It does not enable location collection.", color = LM.Ink2, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().toggleable(value = agreed, enabled = !busy && !sent,
                    role = Role.Checkbox, onValueChange = { agreed = it; batch = null; queued = null })) {
                    Checkbox(agreed, null, enabled = !busy && !sent)
                    Text("I read the notice and agree to submit this public number as proposed community evidence.", color = LM.Ink, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
                notice?.let { Text(it, color = if (sent) LM.Success else LM.Warn, fontSize = 13.sp) }
                Button(enabled = agreed && valid && !busy && !sent, onClick = {
                    scope.launch {
                        busy = true; notice = null
                        try {
                            val request = batch ?: PhysicalSightingBatch(listOf(PhysicalSighting(kind, identifier,
                                System.currentTimeMillis(), station.takeIf { it.isNotBlank() })), System.currentTimeMillis()).also { batch = it }
                            CommunitySync(CommunityQueue(context, gateway.sourceUrl), gateway,
                                CommunityPreferences(context, gateway.sourceUrl)).flushWithdrawals()
                            val report = queued ?: queue.stage(route.trainNumber, requireNotNull(route.runDate), request,
                                gateway.reportInstallationGeneration()).also { queued = it }
                            val result = gateway.submitQueuedPhysicalReport(report)
                            val cleared = runCatching { queue.remove(report.key, report.installation) }.isSuccess
                            sent = true
                            notice = "${result.message} Locomotive ${result.locomotiveState}; rake ${result.rakeState}." +
                                if (cleared) "" else " Receipt cleanup is still pending in Settings."
                            onAccepted()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { notice = "Report not confirmed. ${(error as? GatewayError)?.message ?: "The gateway did not confirm this report."} " +
                            if (queued != null) "The exact report is saved for retry; manage it in Settings." else "Try again." }
                        finally {
                            busy = false
                            if (queued?.let { runCatching { queue.contains(it) && it.hasCurrentConsent() }.getOrDefault(false) } == true)
                                PhysicalReportSyncWork.enqueue(context)
                        }
                    }
                }) { Text(if (busy) "Submitting…" else "Submit proposed evidence") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(if (sent) "Done" else "Cancel") }
            }
        }
    }
}
