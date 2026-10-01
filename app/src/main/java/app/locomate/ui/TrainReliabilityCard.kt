package app.locomate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.data.TrainReliabilitySummary
import app.locomate.ui.theme.LM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal sealed interface ReliabilityState {
    data object Unavailable : ReliabilityState
    data object Loading : ReliabilityState
    data object Failed : ReliabilityState
    data class Loaded(val summary: TrainReliabilitySummary) : ReliabilityState
}

/** Owned outside the lazy card: scrolling cannot repeat its request. No disk cache or consent mutation. */
@Composable
internal fun rememberTrainReliability(route: RoutePreview?, gateway: RailGateway?): Pair<ReliabilityState, () -> Unit> {
    val enabled = route != null && !route.isPreview && gateway?.configured == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(gateway, route?.trainNumber, route?.runDate, enabled) {
        mutableStateOf<ReliabilityState>(if (enabled) ReliabilityState.Loading else ReliabilityState.Unavailable)
    }
    var retry by remember(gateway, route?.trainNumber, route?.runDate, enabled) { mutableIntStateOf(0) }
    LaunchedEffect(gateway, route?.trainNumber, route?.runDate, enabled, retry, lifecycle) {
        if (!enabled || gateway == null || route == null) return@LaunchedEffect
        var completed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!completed) {
                state = ReliabilityState.Loading
                try {
                    val summary = gateway.trainReliability(route.trainNumber)
                    currentCoroutineContext().ensureActive()
                    state = ReliabilityState.Loaded(summary)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    state = ReliabilityState.Failed
                }
                completed = true
            }
            awaitCancellation()
        }
    }
    return state to { retry++ }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrainReliabilityCard(state: ReliabilityState, onRetry: () -> Unit) {
    Surface(color = LM.Elevated, shape = RoundedCornerShape(LM.RadiusCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reliability history", color = LM.Ink2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            when (state) {
                ReliabilityState.Unavailable -> Text(
                    "Reliability history requires a production journey. Preview data is never used to estimate real performance.",
                    color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                ReliabilityState.Loading -> Text("Loading recorded destination arrivals…", color = LM.Ink2, fontSize = 13.sp)
                ReliabilityState.Failed -> {
                    Text("Reliability history is unavailable. Try again.", color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry history") }
                }
                is ReliabilityState.Loaded -> {
                    val summary = state.summary
                    if (summary.denominator > 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("On time · ${summary.percentages.onTime!!.roundToInt()}%", color = LM.Ink, fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text("Early · ${summary.percentages.early!!.roundToInt()}%", color = LM.Ink2, fontSize = 14.sp)
                            Text("Late · ${summary.percentages.late!!.roundToInt()}%", color = LM.Ink2, fontSize = 14.sp)
                        }
                        Text("${summary.denominator} recorded destination arrival${if (summary.denominator == 1L) "" else "s"}.",
                            color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    } else {
                        Text(if (summary.counts.total == 0L) "No recorded runs yet. Arrival timing percentages are unavailable."
                            else "Arrival timing percentages are unavailable for the recorded runs.",
                            color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    Text("On time: within 5 minutes early or late. Earlier arrivals are counted separately.",
                        color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    Text("Excluded from percentages: ${summary.counts.cancelled} cancelled · ${summary.counts.unknown} unknown.",
                        color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    if (summary.lowSample && summary.denominator > 0) Text("Small sample: fewer than 10 recorded arrivals.",
                        color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    Text(summary.coverage?.let { "Service dates: ${it.from} to ${it.to}." } ?: "No recorded service dates yet.",
                        color = LM.Ink2, fontSize = 13.sp, lineHeight = 20.sp)
                    val generated = remember(summary.generatedAt) {
                        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"))
                            .format(Instant.parse(summary.generatedAt))
                    }
                    Text("Summary generated $generated IST", color = LM.Ink2, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Partial recorded history, not a complete operating history or a prediction for this journey.",
                        color = LM.Ink2, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
