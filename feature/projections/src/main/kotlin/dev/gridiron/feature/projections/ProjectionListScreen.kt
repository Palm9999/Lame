package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/** ☰ → Projections: the upcoming week or rest of season, by position, scored with the active profile. */
@Composable
public fun ProjectionListRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    badges: Flow<Map<String, String>>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the list loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** The user's ESPN team for "My lineup"; null (or another season's) hides the mode. */
    myTeam: Flow<MyTeam?> = flowOf(null),
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    ProjectionListScreen(state, injuries, onPlayer, onBack, team?.takeIf { it.season == season })
}

private enum class ListMode { WEEK, ROS, LINEUP }

@Composable
public fun ProjectionListScreen(
    state: ProjectionListState,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    myTeam: MyTeam? = null,
) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FLEX) }
    var chosen by rememberSaveable { mutableStateOf(ListMode.WEEK) }
    val mode = if (chosen == ListMode.LINEUP && myTeam == null) ListMode.WEEK else chosen
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Projections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                ProjectionListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ProjectionListState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is ProjectionListState.Loaded -> {
                    Text(
                        statusLine(state.week, state.builtAt),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == ListMode.WEEK, onClick = { chosen = ListMode.WEEK }, label = { Text("Week ${state.week}") })
                        FilterChip(selected = mode == ListMode.ROS, onClick = { chosen = ListMode.ROS }, label = { Text("Rest of season") })
                        if (myTeam != null) {
                            FilterChip(
                                selected = mode == ListMode.LINEUP,
                                onClick = { chosen = ListMode.LINEUP },
                                label = { Text("My lineup") },
                                modifier = Modifier.testTag("chip:lineup"),
                            )
                        }
                    }
                    if (mode == ListMode.LINEUP && myTeam != null) {
                        LineupList(lineupView(myTeam, state.week, state.weekRows, badges), badges, onPlayer)
                    } else {
                        Row(
                            Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (t in PositionTab.entries) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                        }
                        val rows = visibleRows(if (mode == ListMode.WEEK) state.weekRows else state.rosRows, tab, badges, mode == ListMode.WEEK)
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(rows, key = { _, row -> row.playerId }) { i, row ->
                                ProjectionListRow("${i + 1}", row, badges[row.playerId], onPlayer)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineupList(view: LineupView, badges: Map<String, String>, onPlayer: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("${view.teamName} · week ${view.week}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Projected ${points(view.total)} pts",
                    Modifier.testTag("lineup:total"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (view.defaultSlots) {
                    Text(
                        "Using the usual slots (QB, 2 RB, 2 WR, TE, FLEX, K, D/ST). Sync your league again to use yours.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(view.starters, key = { i, line -> "s:$i:${line.slot}" }) { _, line ->
            if (line.row != null) {
                ProjectionListRow(line.slot, line.row, badges[line.row.playerId], onPlayer, LeadWidth)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.slot, Modifier.width(LeadWidth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No one can fill this slot", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (view.bench.isNotEmpty()) {
            item { SectionLabel("Bench") }
            itemsIndexed(view.bench, key = { _, row -> "b:${row.playerId}" }) { _, row ->
                ProjectionListRow("BE", row, badges[row.playerId], onPlayer, LeadWidth)
            }
        }
        if (view.unlisted.isNotEmpty()) {
            item { SectionLabel("Not projected") }
            itemsIndexed(view.unlisted, key = { i, u -> "u:$i:${u.name}" }) { _, u ->
                Text(
                    "${u.name} · ${if (u.matched) "no projection this week" else "not matched to the app's players"}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val LeadWidth = 64.dp

@Composable
private fun ProjectionListRow(lead: String, row: ProjectionRow, badge: String?, onPlayer: (String) -> Unit, leadWidth: Dp = 32.dp) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(row.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(lead, Modifier.width(leadWidth), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                badge?.takeIf { it != "A" }?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it in setOf("O", "IR", "D")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                listOfNotNull(Position.label(row.position), row.team).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (row.out) "Out" else points(row.points), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (!row.out) {
                Text(
                    "${points(row.floor)}–${points(row.ceiling)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun points(value: Double): String = String.format(Locale.US, "%.1f", value)
