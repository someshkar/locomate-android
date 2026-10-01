package app.locomate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
fun SearchSheet(
    routes: List<RoutePreview>,
    gateway: RailGateway,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onSelectLive: (TrainSearchResult, String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(RailGateway.indiaToday()) }
    val requestQuery = query.trim()
    var searchState by androidx.compose.runtime.remember(gateway) { mutableStateOf(SearchResultState("")) }
    // A result belongs only to its query, including the frame before the replacement effect starts.
    val currentSearch = searchState.takeIf { it.query == requestQuery }
        ?: SearchResultState(requestQuery, loading = gateway.configured && requestQuery.isNotEmpty())
    val liveResults = currentSearch.trains
    val searchError = currentSearch.error
    val searching = currentSearch.loading
    LaunchedEffect(gateway, requestQuery) {
        searchState = SearchResultState(requestQuery, loading = gateway.configured && requestQuery.isNotEmpty())
        if (!gateway.configured || requestQuery.isEmpty()) return@LaunchedEffect
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

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.72f).align(Alignment.BottomCenter)
            .semantics { paneTitle = "Search trains" },
            color = LM.Glass, shape = RoundedCornerShape(topStart = LM.RadiusSheet, topEnd = LM.RadiusSheet)) {
            Column(Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 40.dp, height = 5.dp).clip(RoundedCornerShape(3.dp))
                        .background(LM.Ink3.copy(alpha = 0.55f)))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Search", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, color = LM.Ink,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close search", tint = LM.Ink)
                    }
                }
                Text("Find trains by name or number", color = LM.Ink2, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = LM.Ink2) },
                    label = { Text("Train name or number", color = LM.Ink2) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LM.Raised,
                        unfocusedContainerColor = LM.Raised,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (gateway.configured) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it.take(10) },
                        label = { Text("Origin date · India time", color = LM.Ink2) },
                        supportingText = { Text("YYYY-MM-DD", color = LM.Ink3) },
                        isError = runCatching { LocalDate.parse(date) }.isFailure,
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LM.Raised,
                            unfocusedContainerColor = LM.Raised,
                            errorContainerColor = LM.Raised,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            errorBorderColor = LM.Error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(30.dp))
                Text(if (gateway.configured) "RAIL GATEWAY" else "PREVIEW CATALOGUE", color = LM.Ink3,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(10.dp))
                Text(if (gateway.configured) "Search for a train, then choose its India origin date."
                    else "Sample trains for design review. Live search appears when a rail gateway is configured.",
                    color = LM.Ink2, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(22.dp))
                if (searching) Text("Searching…", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                if (searchError != null) Text(searchError.orEmpty(), color = Color(0xFFFFB84D), modifier = Modifier.padding(vertical = 20.dp))
                if (gateway.configured && !searching && query.isNotBlank() && liveResults.isEmpty() && searchError == null) {
                    Text("No matching trains found.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
                if (!gateway.configured && results.isEmpty()) {
                    Text("No sample train matches this search.", color = LM.Ink2, modifier = Modifier.padding(vertical = 20.dp))
                }
                if (gateway.configured) liveResults.forEach { train ->
                    Surface(
                        color = LM.Elevated,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable(
                            enabled = !searching && runCatching { LocalDate.parse(date) }.isSuccess
                        ) { if (query.trim() == requestQuery) onSelectLive(train, date) }
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${train.number} · ${train.name}", color = LM.Ink,
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(5.dp))
                                Text("${train.originName} → ${train.destinationName}", color = LM.Ink2, fontSize = 13.sp)
                            }
                            Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = LM.Ink2,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (!gateway.configured) results.forEach { train ->
                    Surface(
                        color = LM.Elevated,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onSelect(train.trainNumber) }
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${train.trainNumber} · ${train.displayName}", color = LM.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(5.dp))
                                Text(train.routeLabel, color = LM.Ink2, fontSize = 13.sp)
                            }
                            Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = LM.Ink2, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        }
    }
}
