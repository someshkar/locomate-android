package app.locomate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.BetweenStationsResult
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.data.StationSearch
import app.locomate.data.StationSearchResult
import app.locomate.data.TrainSearchResult
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.PlexMono
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

private data class RouteLoadState(val key: String, val trains: List<TrainSearchResult> = emptyList(),
    val truncated: Boolean = false, val loading: Boolean = false, val error: String? = null)

@Composable
internal fun BetweenStationsSection(routes: List<RoutePreview>, gateway: RailGateway,
    rememberTrain: (TrainSearchResult) -> Unit, onSelect: (String) -> Unit,
    onSelectLive: (TrainSearchResult, String) -> Unit, onOriginDate: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val enlarged = LocalDensity.current.fontScale >= 1.5f
    var from by remember { mutableStateOf<StationSearchResult?>(null) }
    var to by remember { mutableStateOf<StationSearchResult?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var travelDate by rememberSaveable { mutableStateOf(RailGateway.indiaToday()) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var submittedKey by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<RouteLoadState?>(null) }
    var visibleCount by rememberSaveable { mutableIntStateOf(25) }
    val key = "${from?.code.orEmpty()}:${to?.code.orEmpty()}:$travelDate"
    val current = state.takeIf { it?.key == key }
    val canSearch = from != null && to != null && from?.code != to?.code

    LaunchedEffect(attempt, key, gateway) {
        if (attempt == 0 || submittedKey != key) return@LaunchedEffect
        val selectedFrom = from ?: return@LaunchedEffect
        val selectedTo = to ?: return@LaunchedEffect
        state = RouteLoadState(key, loading = true)
        try {
            val result = if (gateway.configured) gateway.trainsBetween(selectedFrom.code, selectedTo.code, travelDate)
                else previewBetween(routes, selectedFrom, selectedTo, travelDate)
            currentCoroutineContext().ensureActive()
            state = RouteLoadState(key, result.trains, result.truncated)
            visibleCount = 25
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state = RouteLoadState(key, error = error.message ?: "Route search unavailable.")
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("BETWEEN STATIONS", color = LM.Ink3, fontSize = 10.5.sp, fontFamily = PlexMono,
            letterSpacing = 1.5.sp, modifier = Modifier.semantics { heading() })
        Text("Find trains for a boarding date at your station.", color = LM.Ink2, fontSize = 13.sp)
        if (enlarged) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StationSelector("Board at", from) { focus.clearFocus(); picker = "from" }
            StationSelector("Leave at", to) { focus.clearFocus(); picker = "to" }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StationSelector("Board at", from, Modifier.weight(1f)) { focus.clearFocus(); picker = "from" }
            StationSelector("Leave at", to, Modifier.weight(1f)) { focus.clearFocus(); picker = "to" }
        }
        OriginDatePicker(travelDate, { travelDate = it }, onOpen = { focus.clearFocus() },
            title = "Boarding date",
            hint = "This is the day you board at the first station. Each train's India origin date is calculated from its timetable.")
        TextButton(enabled = canSearch && current?.loading != true,
            onClick = { submittedKey = key; attempt++ },
            modifier = Modifier.fillMaxWidth().background(if (canSearch) LM.Accent else LM.Raised, RoundedCornerShape(16.dp))
                .semantics { contentDescription = "Find trains between stations" }) {
            if (current?.loading == true) CircularProgressIndicator(Modifier.width(20.dp), color = LM.OnAccent)
            Text("Find trains between stations", color = if (canSearch) LM.OnAccent else LM.Ink3, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        if (from != null && from?.code == to?.code) Text("Choose different boarding and destination stations.",
            color = LM.Ink2, fontSize = 13.sp)
        current?.error?.let { error ->
            Text("Route search unavailable. $error", color = LM.Warn, fontSize = 13.sp)
            TextButton(onClick = { submittedKey = key; attempt++ }) { Text("Retry route search", color = LM.Accent) }
        }
        if (current != null && !current.loading && current.error == null && current.trains.isEmpty()) {
            Text(if (gateway.configured) "No scheduled trains found for these stations and boarding date."
                else "No historical route matches these stations.", color = LM.Ink2, fontSize = 13.sp)
        }
        if (current?.trains?.isNotEmpty() == true) {
            Text(if (gateway.configured) "Scheduled route timetable. Choose a train to open its dated run."
                else "Historical route pack. Choose a train to open its preview.", color = LM.Ink2, fontSize = 13.sp)
            current.trains.take(visibleCount).forEachIndexed { index, train ->
                SearchResultRow(train.number, train.name, train.originCode, train.destinationCode,
                    train.originName, train.destinationName, train.sourceLabel, train.distanceKm,
                    showSeparator = index < current.trains.lastIndex, onSelect = {
                        val origin = train.originDate ?: return@SearchResultRow
                        rememberTrain(train)
                        onOriginDate(origin)
                        if (gateway.configured) onSelectLive(train, origin) else onSelect(train.number)
                    })
                Text("${train.departure ?: "—"} → ${train.arrival ?: "—"} · train origin ${train.originDate ?: "unknown"}",
                    color = LM.Ink3, fontSize = 12.sp)
            }
            if (visibleCount < current.trains.size) TextButton(onClick = { visibleCount += 25 }) {
                Text("Show more trains", color = LM.Accent)
            }
            if (current.truncated) Text("Showing the first 1,000 scheduled trains.", color = LM.Ink2, fontSize = 13.sp)
        }
    }

    if (picker != null) {
        val target = picker!!
        StationChoiceDialog(gateway, routes, target, onDismiss = { picker = null }) { station ->
            if (target == "from") from = station else to = station
            picker = null
        }
    }
}

@Composable
private fun StationSelector(title: String, station: StationSearchResult?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.background(LM.Raised, RoundedCornerShape(16.dp))
        .border(1.dp, LM.Hairline, RoundedCornerShape(16.dp))
        .semantics { contentDescription = "$title station" }) {
        Column(Modifier.heightIn(min = 52.dp), verticalArrangement = Arrangement.Center) {
            Text(title.uppercase(), color = LM.Ink3, fontSize = 11.sp, fontFamily = PlexMono)
            Text(station?.let { "${it.code} · ${it.name}" } ?: "Choose station", color = LM.Ink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StationChoiceDialog(gateway: RailGateway, routes: List<RoutePreview>, target: String,
    onDismiss: () -> Unit,
    onSelect: (StationSearchResult) -> Unit) {
    var query by rememberSaveable(target) { mutableStateOf("") }
    var results by remember { mutableStateOf<List<StationSearchResult>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var resultTerm by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    val term = query.trim()
    LaunchedEffect(term, retry, gateway) {
        results = emptyList(); error = null; loading = false; resultTerm = term
        if (term.length < 2) return@LaunchedEffect
        if (!gateway.configured) {
            results = StationSearch.previewStations(routes).filter {
                it.code.contains(term, ignoreCase = true) || it.name.contains(term, ignoreCase = true)
            }
            return@LaunchedEffect
        }
        loading = true
        delay(300)
        try {
            val found = gateway.searchStations(term)
            currentCoroutineContext().ensureActive()
            results = found; loading = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            error = failure.message ?: "Station lookup unavailable."; loading = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (target == "from") "Board at" else "Leave at") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Station name or code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (loading) CircularProgressIndicator()
                error?.let { Text("Station lookup unavailable. $it", color = LM.Warn, fontSize = 13.sp)
                    TextButton(onClick = { retry++ }) { Text("Retry station lookup") } }
                val candidates = if (term.isEmpty()) StationSearch.shortcuts else if (resultTerm == term) results else emptyList()
                candidates.forEach { station ->
                    TextButton(onClick = { onSelect(station) }, modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Choose ${station.name}, ${station.code}" }) {
                        Text("${station.code} · ${station.name}", color = LM.Ink)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = {}, dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        })
}

private fun previewBetween(routes: List<RoutePreview>, from: StationSearchResult, to: StationSearchResult,
    travelDate: String): BetweenStationsResult {
    val date = LocalDate.parse(travelDate)
    val trains = routes.mapNotNull { route ->
        val start = route.calls.indexOfFirst { it.code == from.code }
        val end = route.calls.indexOfFirst { it.code == to.code }
        if (start < 0 || end <= start) return@mapNotNull null
        val boarding = route.calls[start]
        val arrival = route.calls[end]
        TrainSearchResult(route.trainNumber, route.name, from.code, from.name, to.code, to.name,
            live = false, sourceLabel = "Historical route pack", originDate = date.minusDays((boarding.day - 1).toLong()).toString(),
            boardingDay = boarding.day, arrivalDay = arrival.day,
            departure = boarding.scheduledDeparture, arrival = arrival.scheduledArrival)
    }
    return BetweenStationsResult(from, to, trains, false)
}
