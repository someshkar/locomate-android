package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.RoutePreview
import app.locomate.data.RailGateway
import app.locomate.data.NetworkBounds
import app.locomate.data.NetworkSnapshot
import app.locomate.ui.theme.LM
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

@Composable
fun ExploreScreen(route: RoutePreview?, gateway: RailGateway) {
    var bounds by remember { mutableStateOf<NetworkBounds?>(null) }
    var snapshot by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gateway, bounds) {
        val visible = bounds ?: return@LaunchedEffect
        if (!gateway.configured) return@LaunchedEffect
        delay(400)
        try {
            snapshot = gateway.network(visible)
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "The network feed is unavailable."
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF080B12))) {
        RailMap(if (gateway.configured) null else route,
            networkTrains = snapshot?.trains.orEmpty(),
            onVisibleBounds = if (gateway.configured) ({ bounds = it }) else null)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.68f))
                )
            )
        )
        Surface(
            color = Color(0xE51A1C22),
            shape = RoundedCornerShape(30.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
            shadowElevation = 18.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 18.dp, vertical = 17.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
                Text("EXPLORE", color = LM.Ink3, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.padding(top = 5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("The rail network", color = LM.Ink, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text(if (gateway.configured) "GATEWAY" else "PREVIEW",
                        color = if (gateway.configured) LM.Accent else Color(0xFFBCA7FF),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(
            color = Color(0xF51A1C22),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 24.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp).padding(bottom = 115.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text(if (gateway.configured) "Network in view" else "India by rail",
                    color = LM.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text(if (gateway.configured) {
                    when {
                        error != null -> "Last valid markers retained. ${error.orEmpty()}"
                        snapshot != null -> "${snapshot?.trains?.size ?: 0} gateway train markers in this map view. Positions carry their own source and observation time."
                        else -> "Loading train positions for this map view…"
                    }
                } else "Explore a historical route sample. Live network trains appear when a rail gateway is configured.",
                    color = LM.Ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
