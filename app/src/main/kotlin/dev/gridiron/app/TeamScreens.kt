package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.DefenseRow
import dev.gridiron.core.data.InjuryRow
import dev.gridiron.core.data.TeamsRepository

@Composable
fun InjuriesScreen(season: Int, teams: TeamsRepository, onBack: () -> Unit) {
    ListScreen("Injury report · $season", onBack, load = { teams.injuries(season) }) { row: InjuryRow ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row {
                Text("${row.name}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(row.status ?: "—", color = statusColor(row.status))
            }
            Text(
                listOfNotNull("${row.position} · ${row.team}", "Wk ${row.week}", row.injury, row.practice).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun statusColor(status: String?) = when (status) {
    "Out", "Doubtful" -> MaterialTheme.colorScheme.error
    "Questionable" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}

private val DefenseHeaders = listOf("Team", "PA/g", "YA/g", "Sck", "INT", "FR", "TD")

@Composable
fun DefenseScreen(season: Int, teams: TeamsRepository, onBack: () -> Unit) {
    ListScreen(
        "Team defense · $season",
        onBack,
        load = { teams.defense(season) },
        header = { DefenseLine(DefenseHeaders, bold = true) },
    ) { d: DefenseRow ->
        val g = d.games.coerceAtLeast(1)
        DefenseLine(
            listOf(
                d.team, "%.1f".format(d.pointsAllowed / g), "%.0f".format(d.yardsAllowed / g),
                d.sacks.toInt().toString(), d.interceptions.toInt().toString(),
                d.fumblesRecovered.toInt().toString(), d.defensiveTds.toInt().toString(),
            ),
        )
    }
}

@Composable
private fun DefenseLine(cells: List<String>, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                Modifier.width(if (i == 0) 56.dp else 44.dp),
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                fontWeight = if (bold || i == 0) FontWeight.Bold else null,
            )
        }
    }
}

/** A titled, back-able list loaded once from the database. */
@Composable
private fun <T> ListScreen(
    title: String,
    onBack: () -> Unit,
    load: suspend () -> List<T>,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    var rows by remember { mutableStateOf<List<T>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(title) {
        runCatching { load() }.onSuccess { rows = it }.onFailure { error = it.message ?: "Couldn't load" }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            val r = rows
            when {
                error != null -> Message(error!!)
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                r.isEmpty() -> Message("No data for this season yet. Try Refresh stats.")
                else -> {
                    header?.invoke()
                    LazyColumn {
                        items(r) {
                            row(it)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center) }
}
