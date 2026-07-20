package me.kalfa.agentconsole.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Horizontal event filter: "הכל" + one chip per event. */
@Composable
fun EventFilterChips(
    events: List<Pair<String, String>>,   // id to name
    selectedEventId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (events.size < 2) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedEventId == null,
            onClick = { onSelect(null) },
            label = { Text("הכל") }
        )
        events.forEach { (id, name) ->
            FilterChip(
                selected = selectedEventId == id,
                onClick = { onSelect(if (selectedEventId == id) null else id) },
                label = { Text(name, maxLines = 1) }
            )
        }
    }
}
