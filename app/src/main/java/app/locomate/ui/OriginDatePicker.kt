package app.locomate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.locomate.data.RailGateway
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.PlexMono
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The date is a railway calendar day. Material's UTC date millis are never device-local instants. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OriginDatePicker(value: String, onValue: (String) -> Unit, onOpen: () -> Unit = {}) {
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val date = LocalDate.parse(value)
    val today = LocalDate.parse(RailGateway.indiaToday())
    val enlarged = LocalDensity.current.fontScale >= 1.5f
    val focus = LocalFocusManager.current
    val heading: @Composable () -> Unit = {
        Text("ORIGIN DATE", color = LM.Ink3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = PlexMono, letterSpacing = 0.8.sp)
    }
    val calendarButton: @Composable () -> Unit = {
        Row(Modifier.background(LM.Raised, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { onOpen(); showCalendar = true }
            .semantics { contentDescription = "Choose origin date"; stateDescription = value }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.CalendarMonth, null, tint = LM.Ink, modifier = Modifier.size(18.dp))
            Text(date.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("en-IN"))),
                color = LM.Ink, fontSize = 13.sp)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (enlarged) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { heading(); calendarButton() }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) { heading(); calendarButton() }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (offset in -1L..5L) {
                val quickDate = today.plusDays(offset)
                val label = when (offset) {
                    -1L -> "Yest"
                    0L -> "Today"
                    1L -> "Tmrw"
                    else -> quickDate.format(DateTimeFormatter.ofPattern("EEE", Locale.forLanguageTag("en-IN")))
                }
                val active = date == quickDate
                Column(Modifier.background(if (active) LM.Accent else LM.Raised, RoundedCornerShape(16.dp))
                    .border(0.75.dp, if (active) LM.Accent else LM.Hairline, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onValue(quickDate.toString()) }
                    .semantics { contentDescription = label; stateDescription = quickDate.toString(); selected = active }
                    .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label.uppercase(Locale.ROOT), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = PlexMono, color = if (active) LM.OnAccent else LM.Ink)
                    Text(quickDate.dayOfMonth.toString().padStart(2, '0'), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, color = if (active) LM.OnAccent else LM.Ink)
                }
            }
        }
        Text("The origin date is the day the train starts in India — overnight runs may reach your station the next day.",
            color = LM.Ink3, fontSize = 13.sp)
    }
    if (showCalendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1..9999)
        var inputMode by rememberSaveable { mutableStateOf(false) }
        var input by rememberSaveable { mutableStateOf(value) }
        // Keep keyboard entry strict; the SDK calendar parser normalizes impossible month days.
        val inputDate = runCatching { LocalDate.parse(input) }.getOrNull()
            ?.takeIf { it.toString() == input && it.year in 1..9999 }
        val calendarDate = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        val candidate = if (inputMode) inputDate else calendarDate
        DatePickerDialog(onDismissRequest = { showCalendar = false }, confirmButton = {
            TextButton(enabled = candidate != null, onClick = {
                candidate?.let {
                    onValue(it.toString())
                    showCalendar = false
                }
            }) { Text("Use date") }
        }, dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } }) {
            if (inputMode) Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Origin date · India time", color = LM.Ink2, fontSize = 13.sp)
                OutlinedTextField(input, { input = it }, label = { Text("Origin date") },
                    supportingText = { Text(if (inputDate == null) "Enter a valid date as YYYY-MM-DD." else "YYYY-MM-DD") },
                    singleLine = true, isError = inputDate == null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    inputDate?.let {
                        picker.selectedDateMillis = it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        picker.displayedMonthMillis = it.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    }
                    focus.clearFocus()
                    inputMode = false
                }) { Text("Show calendar") }
            } else Column(Modifier.verticalScroll(rememberScrollState())) {
                DatePicker(state = picker, showModeToggle = false,
                    title = { Text("Origin date · India time", modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                        color = LM.Ink2, fontSize = 13.sp) },
                    headline = {
                        Text(calendarDate?.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.forLanguageTag("en-IN")))
                            ?: "Choose a date", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = LM.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    })
                TextButton(onClick = { input = calendarDate?.toString().orEmpty(); inputMode = true },
                    modifier = Modifier.padding(horizontal = 12.dp)) { Text("Enter date") }
            }
        }
    }
}
