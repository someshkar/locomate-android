package app.locomate.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.ui.theme.PlexMono
import app.locomate.data.RoutePreview
import app.locomate.data.RailGateway
import app.locomate.data.TrainSearchResult
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
)

@Composable
fun SearchScreen(
    routes: List<RoutePreview>,
    gateway: RailGateway,
    onSelect: (String) -> Unit,
    onSelectLive: (TrainSearchResult, String) -> Unit,
    bottomInset: Dp = 0.dp,
) {
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val attribution = androidx.compose.runtime.remember { MapAttributionController() }
    var query by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(RailGateway.indiaToday()) }
    var retry by androidx.compose.runtime.remember(gateway) { mutableIntStateOf(0) }
    val requestQuery = query.trim()
    var searchState by androidx.compose.runtime.remember(gateway) { mutableStateOf(SearchResultState("")) }
    // A result belongs only to its query, including the frame before the replacement effect starts.
    val currentSearch = searchState.takeIf { it.query == requestQuery }
        ?: SearchResultState(requestQuery, loading = gateway.configured && requestQuery.length >= 2)
    val liveResults = currentSearch.trains
    val searchError = currentSearch.error
    val searching = currentSearch.loading
    LaunchedEffect(gateway, requestQuery, retry) {
        searchState = SearchResultState(requestQuery, loading = gateway.configured && requestQuery.length >= 2)
        if (!gateway.configured || requestQuery.length < 2) return@LaunchedEffect
        delay(300)
        try {
            val trains = gateway.search(requestQuery)
            currentCoroutineContext().ensureActive()
            searchState = SearchResultState(requestQuery, trains = trains)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            searchState = SearchResultState(requestQuery, error = error.message ?: "Search unavailable.")
        }
    }
    val results = routes.filter {
        query.isBlank() || it.trainNumber.contains(query.trim()) || it.name.contains(query.trim(), ignoreCase = true)
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
                    Text("Find trains by name or number", color = LM.Ink2, fontSize = 13.5.sp)
                }
            }
            item("field") {
                Column {
                    Spacer(Modifier.height(18.dp))
                    SearchField(query, { query = it }, searching, onSubmit = { focus.clearFocus() })
                }
            }
            if (gateway.configured) {
                item("date") {
                    Column {
                        Spacer(Modifier.height(16.dp))
                        OriginDatePicker(date, { date = it }, onOpen = { focus.clearFocus() })
                    }
                }
            }
            item("catalogue") {
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
            if (searching) item("loading") { Text("Searching…", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp)) }
            if (searchError != null) item("error") {
                Column {
                    Text(searchError, color = LM.Warn, modifier = Modifier.padding(top = 20.dp))
                    TextButton(onClick = { retry++ }) { Text("Try again", color = LM.Accent) }
                }
            }
            if (gateway.configured && !searching && requestQuery.length >= 2 && liveResults.isEmpty() && searchError == null) {
                item("empty") {
                    Text("No matching trains found.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            if (!gateway.configured && results.isEmpty()) {
                item("empty") {
                    Text("No sample train matches this search.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            if (gateway.configured) itemsIndexed(liveResults, key = { index, train -> "${train.number}:$index" }) { index, train ->
                SearchResultRow(train.number, train.name, train.originCode, train.destinationCode,
                    train.originName, train.destinationName, train.sourceLabel, train.distanceKm,
                    showSeparator = index < liveResults.lastIndex,
                    enabled = !searching && runCatching { LocalDate.parse(date) }.isSuccess,
                    onSelect = { if (query.trim() == requestQuery) onSelectLive(train, date) })
            }
            if (!gateway.configured) itemsIndexed(results, key = { index, train -> "${train.trainNumber}:$index" }) { index, train ->
                SearchResultRow(train.trainNumber, train.name, train.originCode, train.destinationCode,
                    train.originName, train.destinationName, "Historical route pack", train.distanceKm,
                    showSeparator = index < results.lastIndex, onSelect = { onSelect(train.trainNumber) })
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
