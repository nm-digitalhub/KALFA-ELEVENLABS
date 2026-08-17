package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.kalfa.agentconsole.domain.telephony.ConsoleHistoryFilter
import me.kalfa.agentconsole.domain.telephony.HistoryDirection
import me.kalfa.agentconsole.domain.telephony.HistoryOutcome
import me.kalfa.agentconsole.domain.telephony.HistoryRange
import me.kalfa.agentconsole.domain.telephony.looksLikePhoneNumber

/**
 * Every axis the platform can filter on, in one sheet.
 *
 * WHY A SHEET AND NOT MORE CHIPS. The screen shipped with three preset ranges —
 * היום / שבוע / חודש — and that was mistaken for filtering. Voximplant accepts a
 * date range to the second, a specific number, and a duration band; a row of
 * preset chips cannot express any of them. The presets stay on the screen because
 * "today" is the common case and should be one tap, but the real parameters need
 * room, and a bottom sheet is the room.
 *
 * NOTHING IS APPLIED UNTIL "החל". A filter that re-queries on every keystroke
 * would fire a request per digit of a phone number, and each one costs a scan on
 * Voximplant's side. The draft is local; the commit is deliberate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryFilterSheet(
    current: ConsoleHistoryFilter,
    onApply: (ConsoleHistoryFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var showDatePicker by remember { mutableStateOf(false) }
    var phoneText by remember(current) { mutableStateOf(current.phone.orEmpty()) }
    var minText by remember(current) { mutableStateOf(current.minDurationSec?.toString().orEmpty()) }
    var maxText by remember(current) { mutableStateOf(current.maxDurationSec?.toString().orEmpty()) }

    val phoneError = phoneText.isNotBlank() && !looksLikePhoneNumber(phoneText)
    val minVal = minText.toIntOrNull()
    val maxVal = maxText.toIntOrNull()
    // A band whose floor is above its ceiling matches nothing. Named on the field
    // rather than returned as an empty list, which would look like "no such calls".
    val durationError = minVal != null && maxVal != null && minVal > maxVal

    ModalBottomSheet(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "סינון יומן שיחות",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                FilterSection("טווח זמן") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryRange.entries.forEach { r ->
                            FilterChip(
                                selected = !draft.hasExplicitWindow && draft.range == r,
                                onClick = {
                                    // Choosing a preset CLEARS an explicit window.
                                    // Leaving both set would make the result depend
                                    // on a precedence rule the agent cannot see.
                                    draft = draft.copy(range = r, fromMs = null, toMs = null)
                                },
                                label = { Text(r.labelHebrew) },
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (draft.hasExplicitWindow) {
                                "${formatDay(draft.fromMs)} – ${formatDay(draft.toMs)}"
                            } else {
                                "בחירת טווח תאריכים"
                            }
                        )
                    }
                }

                FilterSection("תוצאה") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryOutcome.entries.forEach { o ->
                            FilterChip(
                                selected = draft.outcome == o,
                                onClick = { draft = draft.copy(outcome = o) },
                                label = { Text(o.labelHebrew) },
                            )
                        }
                    }
                }

                FilterSection("כיוון") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HistoryDirection.entries.forEach { d ->
                            FilterChip(
                                selected = draft.direction == d,
                                onClick = { draft = draft.copy(direction = d) },
                                label = { Text(d.labelHebrew) },
                            )
                        }
                    }
                }

                FilterSection("מספר טלפון") {
                    OutlinedTextField(
                        value = phoneText,
                        onValueChange = { phoneText = it.trim() },
                        singleLine = true,
                        isError = phoneError,
                        placeholder = { Text("+972501234567") },
                        supportingText = {
                            Text(
                                if (phoneError) "מספר לא תקין."
                                // Both forms work: the server normalizes to one
                                // canonical number, so an agent never has to know
                                // which one the platform wants.
                                else "אפשר 0536212562 או +972536212562 — מסנן בצד Voximplant"
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                FilterSection("משך שיחה (שניות)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it.filter(Char::isDigit) },
                            singleLine = true,
                            isError = durationError,
                            label = { Text("מינימום") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { maxText = it.filter(Char::isDigit) },
                            singleLine = true,
                            isError = durationError,
                            label = { Text("מקסימום") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (durationError) {
                        Text(
                            text = "המינימום גדול מהמקסימום — לא יימצאו שיחות.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = {
                            draft = draft.cleared()
                            phoneText = ""
                            minText = ""
                            maxText = ""
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("נקה הכל") }

                    Button(
                        onClick = {
                            onApply(
                                draft.copy(
                                    phone = phoneText.takeIf { it.isNotBlank() && !phoneError },
                                    minDurationSec = minVal,
                                    maxDurationSec = maxVal,
                                )
                            )
                        },
                        // Blocked rather than silently sanitised: an invalid value
                        // dropped on apply would look like the filter was accepted
                        // and then ignored.
                        enabled = !phoneError && !durationError,
                        modifier = Modifier.weight(1f),
                    ) { Text("החל") }
                }
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = draft.fromMs,
            initialSelectedEndDateMillis = draft.toMs,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = state.selectedStartDateMillis
                        val end = state.selectedEndDateMillis
                        draft = draft.copy(
                            fromMs = start,
                            // The picker returns midnight for the end day, which would
                            // exclude every call made on the day the agent selected.
                            // Extended to the last moment of it.
                            toMs = end?.plus(DAY_MS - 1),
                        )
                        showDatePicker = false
                    },
                    enabled = state.selectedStartDateMillis != null,
                ) { Text("אישור") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("ביטול") }
            },
        ) {
            DateRangePicker(state = state)
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

@Composable
private fun FilterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
        content()
    }
}

/**
 * SimpleDateFormat, not java.time — minSdk is 24 here and desugaring is off, so
 * java.time compiles and then throws NoClassDefFoundError on API 24–25. Same
 * reason as formatCallTime on the history screen itself.
 */
private fun formatDay(ms: Long?): String {
    if (ms == null) return "—"
    return java.text.SimpleDateFormat("d.M.yy", java.util.Locale.US).format(java.util.Date(ms))
}
