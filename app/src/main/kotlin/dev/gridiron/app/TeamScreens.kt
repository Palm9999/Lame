package dev.gridiron.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
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
import dev.gridiron.core.data.live.LiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

@Composable
fun InjuriesScreen(season: Int, teams: TeamsRepository, onBack: () -> Unit, dataVersion: Flow<Long> = flowOf(0L)) {
    ListScreen("Injury report · $season", onBack, dataVersion, load = { teams.injuries(season) }) { row: InjuryRow ->
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

/**
 * The current season's report is ESPN's live list with official practice
 * alongside; a past season (or no live data source, in tests) shows
 * nflverse's official list as before.
 */
@Composable
fun InjuriesRoute(
    season: Int,
    currentSeason: Int,
    teams: TeamsRepository,
    live: LiveRepository?,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
    /** Bumped when a refresh swaps in new stats: the official rows load again. */
    dataVersion: Flow<Long> = flowOf(0L),
) {
    if (live != null && season == currentSeason) {
        LiveInjuriesRoute(season, teams, live, onBack, onPlayer, dataVersion)
    } else {
        InjuriesScreen(season, teams, onBack, dataVersion)
    }
}

@Composable
private fun LiveInjuriesRoute(
    season: Int,
    teams: TeamsRepository,
    live: LiveRepository,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
    dataVersion: Flow<Long>,
) {
    val version by live.changes.collectAsState()
    val stats by dataVersion.collectAsState(initial = 0L)
    var groups by remember { mutableStateOf<List<InjuryGroup>?>(null) }
    var asOf by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { error = live.refreshIfStale()?.injuriesError }
    LaunchedEffect(version, stats) {
        // Official practice rows are a bonus: the live list shows without them.
        val official = try {
            teams.injuries(season)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        groups = injuryReport(live.injuries(), official)
        asOf = live.fetchedAt()
    }
    LiveInjuriesScreen(groups, asOf, error, onBack, onPlayer)
}

@Composable
fun LiveInjuriesScreen(
    groups: List<InjuryGroup>?,
    asOf: Instant?,
    error: String?,
    onBack: () -> Unit,
    onPlayer: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Injury report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LiveCaption(asOf, error)
            when {
                groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                groups.isEmpty() -> Message(if (error != null) "Couldn't reach ESPN. Try again later." else "No injuries reported.")
                else -> LazyColumn {
                    for (group in groups) {
                        item(key = "team:${group.team}") {
                            Text(
                                group.team,
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(group.lines, key = { it.injury.espnId }) { line ->
                            InjuryLineRow(line, onPlayer)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InjuryLineRow(line: InjuryLine, onPlayer: (String) -> Unit) {
    val i = line.injury
    val id = i.playerId
    Column(
        Modifier.fillMaxWidth()
            .then(if (id != null) Modifier.clickable { onPlayer(id) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            Text(i.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(i.status, color = injuryColor(i.abbr))
        }
        Text(
            listOfNotNull(i.position, line.practice?.let { "Practice: $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        i.shortComment?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
fun DefenseScreen(season: Int, teams: TeamsRepository, onBack: () -> Unit, dataVersion: Flow<Long> = flowOf(0L)) {
    ListScreen(
        "Team defense · $season",
        onBack,
        dataVersion,
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

/** A titled, back-able list loaded from the database, and again whenever [dataVersion] moves (a refresh swapped in new stats). */
@Composable
private fun <T> ListScreen(
    title: String,
    onBack: () -> Unit,
    dataVersion: Flow<Long>,
    load: suspend () -> List<T>,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (T) -> Unit,
) {
    var rows by remember { mutableStateOf<List<T>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val version by dataVersion.collectAsState(initial = 0L)
    LaunchedEffect(title, version) {
        try {
            rows = load()
            error = null
        } catch (e: CancellationException) {
            throw e // superseded by a newer load: not an error
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load"
        }
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
internal fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, textAlign = TextAlign.Center) }
}
