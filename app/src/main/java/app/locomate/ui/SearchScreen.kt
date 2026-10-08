package app.locomate.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.ui.theme.PlexMono
import app.locomate.data.RoutePreview
import app.locomate.data.RailGateway
import app.locomate.data.TrainSearchResult
import app.locomate.data.RecentTrainStore
import app.locomate.data.StationSearchResult
import app.locomate.data.StationSearch
import app.locomate.ui.theme.LM
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDate

private data class SearchResultState(
    val query: String,
    val trains: List<TrainSearchResult> = emptyList(),
    val error: String? = null,
    val loading: Boolean = false,
    val stationCode: String? = null,
    val truncated: Boolean = false,
)
private data class StationResultState(val query: String, val stations: List<StationSearchResult> = emptyList(),
    val loading: Boolean = false, val error: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    routes: List<RoutePreview>,
    gateway: RailGateway,
    onSelect: (String) -> Unit,
    onSelectLive: (TrainSearchResult, String) -> Unit,
    bottomInset: Dp = 0.dp,
    originDate: String? = null,
    onOriginDateChange: (String) -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val recentStore = androidx.compose.runtime.remember(gateway) { RecentTrainStore(context, gateway.sourceUrl) }
    var recentTrains by androidx.compose.runtime.remember(recentStore) { mutableStateOf(recentStore.load()) }
    var recentNotice by androidx.compose.runtime.remember(recentStore) { mutableStateOf<String?>(null) }
    val rememberTrain: (TrainSearchResult) -> Unit = { train ->
        if (recentStore.record(train)) recentTrains = recentStore.load()
    }
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val attribution = androidx.compose.runtime.remember { MapAttributionController() }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedStationCode by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedStationName by rememberSaveable { mutableStateOf<String?>(null) }
    var localDate by rememberSaveable { mutableStateOf(RailGateway.indiaToday()) }
    val date = originDate ?: localDate
    val setDate: (String) -> Unit = { if (originDate != null) onOriginDateChange(it) else localDate = it }
    var retry by androidx.compose.runtime.remember(gateway) { mutableIntStateOf(0) }
    val requestQuery = query.trim()
    var searchState by androidx.compose.runtime.remember(gateway) { mutableStateOf(SearchResultState("")) }
    // A result belongs only to its query, including the frame before the replacement effect starts.
    var stationState by androidx.compose.runtime.remember(gateway) { mutableStateOf(StationResultState("")) }
    val currentStationSearch = stationState.takeIf { it.query == requestQuery && selectedStationCode == null }
        ?: StationResultState(requestQuery)
    val currentSearch = searchState.takeIf { it.query == requestQuery && it.stationCode == selectedStationCode }
        ?: SearchResultState(requestQuery, loading = gateway.configured && requestQuery.length >= 2)
    val liveResults = currentSearch.trains
    val searchError = currentSearch.error
    val searching = currentSearch.loading
    LaunchedEffect(gateway, requestQuery, selectedStationCode, retry) {
        val requestedStation = selectedStationCode
        searchState = SearchResultState(requestQuery, loading = gateway.configured && requestQuery.length >= 2, stationCode = requestedStation)
        if (!gateway.configured || requestQuery.length < 2) return@LaunchedEffect
        delay(300)
        try {
            val board = requestedStation?.let { gateway.stationTrains(it) }
            val trains = board?.trains ?: gateway.search(requestQuery)
            currentCoroutineContext().ensureActive()
            searchState = SearchResultState(requestQuery, trains = trains, stationCode = requestedStation, truncated = board?.truncated == true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            searchState = SearchResultState(requestQuery, error = error.message ?: "Search unavailable.", stationCode = requestedStation)
        }
    }
    LaunchedEffect(gateway, requestQuery, selectedStationCode, retry) {
        stationState = StationResultState(requestQuery)
        if (selectedStationCode != null || requestQuery.length < 2 || requestQuery.none { it.isLetter() }) return@LaunchedEffect
        if (!gateway.configured) {
            stationState = StationResultState(requestQuery, StationSearch.previewStations(routes).filter {
                it.code.contains(requestQuery, ignoreCase = true) || it.name.contains(requestQuery, ignoreCase = true)
            })
            return@LaunchedEffect
        }
        stationState = StationResultState(requestQuery, loading = true)
        delay(300)
        try {
            val stations = gateway.searchStations(requestQuery)
            currentCoroutineContext().ensureActive()
            stationState = StationResultState(requestQuery, stations)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            stationState = StationResultState(requestQuery, error = error.message ?: "Station search unavailable.")
        }
    }
    val results = if (requestQuery.length < 2) emptyList() else routes.filter {
        val station = selectedStationCode
        if (station != null) it.calls.any { stop -> stop.code == station }
        else it.trainNumber.contains(requestQuery) || it.name.contains(requestQuery, ignoreCase = true)
    }

    OverviewMapLayout(bottomInset, keyboardVisible = keyboardVisible,
        map = { modifier, viewport ->
            RailMap(null, modifier = modifier, attribution = attribution, visibleViewport = viewport)
        }) {
        LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = "Search trains" }, contentPadding = PaddingValues(20.dp)) {
            item("intro") {
                Column {
                    Text("Search", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, color = LM.Ink,
                        modifier = Modifier.semantics { heading() })
                    Text("Find trains and stations", color = LM.Ink2, fontSize = 13.5.sp)
                }
            }
            item("field") {
                Column {
                    Spacer(Modifier.height(18.dp))
                    SearchField(query, { query = it; selectedStationCode = null; selectedStationName = null },
                        searching || currentStationSearch.loading, onSubmit = { focus.clearFocus() })
                }
            }
            if (gateway.configured) {
                item("date") {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        OriginDatePicker(date, setDate, onOpen = { focus.clearFocus() })
                    }
                }
            }
            // The how-to hint earns its space only before anything is typed.
            if (requestQuery.isEmpty() || !gateway.configured) item("catalogue") {
                Column {
                    Spacer(Modifier.height(26.dp))
                    Text(if (gateway.configured) "TRAINS" else "HISTORICAL TRAINS", color = LM.Ink3,
                        fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = PlexMono, letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (gateway.configured) "Search for a train, then choose its India origin date."
                        else "Explore historical railway records. These are timetable samples, not current schedules.",
                        color = LM.Ink2, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(22.dp))
                }
            }
            if (requestQuery.isEmpty()) {
                item("recents-heading") {
                    val recentHeading: @Composable () -> Unit = {
                        Text("RECENT TRAINS", color = LM.Ink3, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                            fontFamily = PlexMono, letterSpacing = 1.5.sp, modifier = Modifier.semantics { heading() })
                    }
                    val clear: @Composable () -> Unit = {
                        if (recentTrains.isNotEmpty()) TextButton(onClick = {
                            if (recentStore.clear()) { recentTrains = emptyList(); recentNotice = null }
                            else recentNotice = "Couldn't clear recent trains. Try again."
                        }, modifier = Modifier.semantics { contentDescription = "Clear recent trains" }) {
                            Text("Clear", color = LM.Ink2, fontSize = 12.5.sp)
                        }
                    }
                    if (density.fontScale >= 1.5f) Column { recentHeading(); clear() }
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { recentHeading(); clear() }
                }
                if (recentNotice != null) item("recents-notice") { Text(recentNotice!!, color = LM.Warn) }
                if (recentTrains.isEmpty()) item("recents-empty") {
                    Text("Trains you choose will appear here.", color = LM.Ink2, fontSize = 13.sp)
                }
                itemsIndexed(recentTrains, key = { _, train -> "recent:${train.number}" }) { index, train ->
                    SearchResultRow(train.number, train.name, train.originCode, train.destinationCode,
                        train.originName, train.destinationName, train.sourceLabel, train.distanceKm,
                        showSeparator = index < recentTrains.lastIndex, onSelect = {
                            rememberTrain(train)
                            if (gateway.configured) onSelectLive(train, date) else onSelect(train.number)
                        })
                }
            }
            if (selectedStationCode != null) item("station-context") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Trains at ${selectedStationName ?: selectedStationCode}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = LM.Ink, modifier = Modifier.semantics { heading() })
                    Text(if (gateway.configured) "Scheduled services. Choose the train’s origin date to open a run."
                        else "Services in the historical route pack.", color = LM.Ink2, fontSize = 13.sp)
                }
            }
            if (searching) item("loading") { Text("Searching…", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp)) }
            if (searchError != null) item("error") {
                Column {
                    Text(searchError, color = LM.Warn, modifier = Modifier.padding(top = 20.dp))
                    TextButton(onClick = { retry++ }) { Text("Try again", color = LM.Accent) }
                }
            }
            if (gateway.configured && !searching && !currentStationSearch.loading && requestQuery.length >= 2
                && liveResults.isEmpty() && searchError == null && currentStationSearch.stations.isEmpty() && currentStationSearch.error == null) {
                item("empty") {
                    Text("No matching trains found.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            if (!gateway.configured && requestQuery.length >= 2 && results.isEmpty() && currentStationSearch.stations.isEmpty()) {
                item("empty") {
                    Text("No sample train matches this search.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            if (gateway.configured) itemsIndexed(liveResults, key = { index, train -> "${train.number}:$index" }) { index, train ->
                SearchResultRow(train.number, train.name, train.originCode, train.destinationCode,
                    train.originName, train.destinationName, train.sourceLabel, train.distanceKm,
                    departure = train.departure, arrival = train.arrival,
                    modifier = Modifier.animateItem(),
                    showSeparator = index < liveResults.lastIndex,
                    enabled = !searching && runCatching { LocalDate.parse(date) }.isSuccess,
                    onSelect = { if (query.trim() == requestQuery) { rememberTrain(train); onSelectLive(train, date) } })
            }
            if (!gateway.configured) itemsIndexed(results, key = { index, train -> "${train.trainNumber}:$index" }) { index, train ->
                SearchResultRow(train.trainNumber, train.name, train.originCode, train.destinationCode,
                    train.originName, train.destinationName, "Historical route pack", train.distanceKm,
                    showSeparator = index < results.lastIndex, onSelect = {
                        rememberTrain(TrainSearchResult(train.trainNumber, train.name, train.originCode, train.originName,
                            train.destinationCode, train.destinationName, live = false,
                            sourceLabel = "Historical route pack", distanceKm = train.distanceKm))
                        onSelect(train.trainNumber)
                    })
            }
            if (currentSearch.truncated) item("station-limit") {
                Text("Showing the first 1,000 scheduled services.", color = LM.Ink2, fontSize = 13.sp)
            }
            if (selectedStationCode == null && (requestQuery.isEmpty() || currentStationSearch.stations.isNotEmpty() || currentStationSearch.error != null)) {
                item("stations") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Spacer(Modifier.height(14.dp))
                        Text("STATIONS", color = LM.Ink3, fontSize = 10.5.sp, fontFamily = PlexMono, letterSpacing = 1.5.sp,
                            modifier = Modifier.semantics { heading() })
                        val stations = if (requestQuery.isEmpty()) StationSearch.shortcuts else currentStationSearch.stations
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (station in stations) TextButton(onClick = {
                                focus.clearFocus(); query = station.code
                                selectedStationCode = station.code; selectedStationName = station.name
                            }, shape = RoundedCornerShape(20.dp), modifier = Modifier
                                .background(LM.Ink.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                                .border(1.dp, LM.Ink.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                                .semantics { contentDescription = "Find trains at ${station.name}, ${station.code}" }) {
                                Text(buildAnnotatedString {
                                    withStyle(SpanStyle(color = LM.Accent, fontFamily = PlexMono, fontWeight = FontWeight.SemiBold)) { append(station.code) }
                                    append(" ${station.name}")
                                }, color = LM.Ink2, fontSize = 12.5.sp)
                            }
                        }
                        currentStationSearch.error?.let { error ->
                            Text("Station search unavailable. $error", color = LM.Ink2, fontSize = 13.sp)
                            TextButton(onClick = { retry++ }) { Text("Retry station search") }
                        }
                    }
                }
            }
            item("between-stations") {
                BetweenStationsSection(routes, gateway, rememberTrain, onSelect, onSelectLive, setDate)
            }
            item("attribution") {
                Column {
                    MapAttributionButton(attribution)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
