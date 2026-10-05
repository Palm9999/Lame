package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.live.HistoryRecord
import dev.gridiron.core.data.live.HistoryResult
import dev.gridiron.core.data.live.HistoryTables
import dev.gridiron.core.data.live.LeagueHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

public sealed interface HistoryState {
    public data object Loading : HistoryState

    /** [tables] (null when no season could be read, with [error] saying why); [skipped] seasons and why. */
    public data class Loaded(val tables: HistoryTables?, val me: String?, val skipped: List<Pair<Int, String>>, val error: String?) : HistoryState
}

public fun historyState(result: HistoryResult): HistoryState.Loaded = HistoryState.Loaded(
    tables = if (result.seasons.isEmpty()) null else LeagueHistory.of(result.seasons, result.me),
    me = result.me,
    skipped = result.skipped,
    error = result.error,
)

public class HistoryViewModel(private val history: suspend (Int) -> HistoryResult) : ViewModel() {
    private val _state = MutableStateFlow<HistoryState>(HistoryState.Loading)
    public val state: StateFlow<HistoryState> = _state.asStateFlow()

    public fun load(season: Int) {
        viewModelScope.launch { _state.value = historyState(history(season)) }
    }
}

/** More → League history: the active ESPN league's seasons, all-time table, your head-to-head and records. */
@Composable
public fun HistoryRoute(season: Int, history: suspend (Int) -> HistoryResult, onBack: () -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(history) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(season) { vm.load(season) }
    HistoryScreen(state, onBack)
}

private enum class HistoryTab(val label: String, val tag: String) {
    SEASONS("Seasons", "seasons"),
    ALL_TIME("All-time", "alltime"),
    H2H("Head-to-head", "h2h"),
    RECORDS("Records", "records"),
}

@Composable
public fun HistoryScreen(state: HistoryState, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(HistoryTab.SEASONS) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("League history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                HistoryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is HistoryState.Loaded -> {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in HistoryTab.entries) {
                            FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, modifier = Modifier.testTag("tab:${t.tag}"))
                        }
                    }
                    for ((year, why) in state.skipped) HistoryNote("Couldn't read $year: $why.", error = true)
                    val tables = state.tables
                    if (tables == null) {
                        HistoryNote("No history: ${state.error ?: "no seasons found"}.")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            when (tab) {
                                HistoryTab.SEASONS -> seasons(tables)
                                HistoryTab.ALL_TIME -> allTime(tables, state.me)
                                HistoryTab.H2H -> headToHead(tables, state.me)
                                HistoryTab.RECORDS -> records(tables)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.seasons(tables: HistoryTables) {
    for (s in tables.seasons) {
        item(key = "s:${s.season}") {
            Text(
                "${s.season} · " + (s.champion?.let { "Champion: ${it.name} (${s.championTeam})" } ?: "in progress"),
                Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        items(s.standings, key = { "s:${s.season}:${it.manager.key}:${it.teamName}" }) { r ->
            HistoryLine("${placeText(r.place)} · ${r.teamName} (${r.manager.name}) · ${record(r.wins, r.losses, r.ties)} · ${pts(r.pointsFor)}")
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.allTime(tables: HistoryTables, me: String?) {
    items(tables.allTime, key = { it.manager.key }) { m ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                m.manager.name + if (m.manager.key == me) " (you)" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (m.manager.key == me) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    record(m.wins, m.losses, m.ties),
                    String.format(Locale.US, "%.3f", m.winPct).removePrefix("0"),
                    "${m.titles} title${if (m.titles == 1) "" else "s"}",
                    "${m.playoffs} playoffs",
                    m.averageFinish?.let { "avg finish ${String.format(Locale.US, "%.1f", it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.headToHead(tables: HistoryTables, me: String?) {
    if (me == null) {
        item { HistoryNote("Choose your team on ESPN leagues to see your head-to-head.") }
        return
    }
    if (tables.headToHead.isEmpty()) item { HistoryNote("No decided games yet.") }
    items(tables.headToHead, key = { it.rival.key }) { r ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(r.rival.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${record(r.wins, r.losses, r.ties)} · ${pts(r.pointsFor)} to ${pts(r.pointsAgainst)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.records(tables: HistoryTables) {
    items(tables.records, key = { it.label }) { r ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("${r.label}: ${recordValue(r)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(r.manager.name, r.week?.let { "${r.season}, week $it" } ?: "${r.season}", r.detail).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun recordValue(r: HistoryRecord): String =
    if (r.label == "Best record") String.format(Locale.US, "%.3f", r.value).removePrefix("0") else pts(r.value)

private fun record(w: Int, l: Int, t: Int): String = "$w–$l" + if (t > 0) "–$t" else ""

private fun pts(v: Double): String = String.format(Locale.US, "%.1f", v)

@Composable
private fun HistoryLine(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HistoryNote(text: String, error: Boolean = false) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
