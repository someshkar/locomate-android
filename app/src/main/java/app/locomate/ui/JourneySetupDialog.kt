package app.locomate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.locomate.data.JourneyPlan
import app.locomate.data.RoutePreview
import app.locomate.ui.theme.LM

@Composable
fun JourneySetupDialog(route: RoutePreview, current: JourneyPlan, onDismiss: () -> Unit,
                       onSave: (JourneyPlan) -> Unit) {
    val calls = route.calls
    if (calls.size < 2) return
    var board by remember(route.runId, route.trainNumber) {
        mutableIntStateOf(calls.indexOfFirst { it.code == current.boardingCode }.coerceAtLeast(0))
    }
    var leave by remember(route.runId, route.trainNumber) {
        mutableIntStateOf(calls.indexOfFirst { it.code == current.alightingCode }.coerceAtLeast(1))
    }
    var coach by remember(route.runId, route.trainNumber) { mutableStateOf(current.coach) }
    var seat by remember(route.runId, route.trainNumber) { mutableStateOf(current.seat) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = LM.Elevated, shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, LM.Ink.copy(alpha = 0.11f)),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).heightIn(max = 700.dp)
                .semantics { paneTitle = "Choose boarding and alighting stops" }) {
            Column(Modifier.padding(20.dp)) {
                Text("Your journey", color = LM.Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(5.dp))
                Text("Choose where you board and leave this train. Stored on this device.",
                    color = LM.Ink2, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ChoiceTab("BOARD", calls[board].code, choosing == 0, Modifier.weight(1f)) { choosing = 0 }
                    ChoiceTab("LEAVE", calls[leave].code, choosing == 1, Modifier.weight(1f)) { choosing = 1 }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { scope.launch { listState.animateScrollToItem(calls.size) } }) {
                    Text("Edit private coach / seat")
                }
                LazyColumn(Modifier.weight(1f).selectableGroup(), state = listState) {
                    itemsIndexed(calls) { index, stop ->
                        val eligible = if (choosing == 0) index < calls.lastIndex else index > board
                        if (eligible) {
                            val selected = if (choosing == 0) index == board else index == leave
                            Surface(color = if (selected) LM.Raised else Color.Transparent,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                                        if (choosing == 0) {
                                            board = index
                                            if (leave <= board) leave = board + 1
                                            choosing = 1
                                        } else leave = index
                                    })) {
                                Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stop.name, color = LM.Ink, fontSize = 14.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                        Text(stop.code, color = LM.Ink3, fontSize = 11.sp)
                                    }
                                    if (selected) Text("✓", color = LM.Accent, fontSize = 17.sp,
                                        modifier = Modifier.clearAndSetSemantics { })
                                }
                            }
                        }
                    }
                    item(key = "private-details") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(coach, onValueChange = { if (it.length <= 16 && it.all { c -> c.isLetterOrDigit() || c in " -" }) coach = it },
                        label = { Text("Private coach") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(seat, onValueChange = { if (it.length <= 24 && it.all { c -> c.isLetterOrDigit() || c in " /-" }) seat = it },
                        label = { Text("Private seat") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("Optional. Stays on this device; excluded from community reports and Passport.", color = LM.Ink2, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                    }
                }
                Spacer(Modifier.height(13.dp))
                Button(onClick = { onSave(JourneyPlan(calls[board].code, calls[leave].code, coach.trim(), seat.trim())) },
                    colors = ButtonDefaults.buttonColors(containerColor = LM.Accent),
                    shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Save this segment", color = LM.OnAccent, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun ChoiceTab(label: String, code: String, selected: Boolean, modifier: Modifier,
                      onClick: () -> Unit) {
    Surface(color = if (selected) LM.Raised else LM.Raised,
        shape = RoundedCornerShape(14.dp), modifier = modifier.selectable(
            selected = selected, role = Role.Tab, onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = if (selected) LM.Accent else LM.Ink3, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(code, color = LM.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
