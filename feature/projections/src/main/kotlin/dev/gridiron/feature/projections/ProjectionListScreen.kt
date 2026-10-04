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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.gridiron.core.data.OpportunitiesResult
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueChoice
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.OpponentResult
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
    /** Your opponent for the week, fetched each time My lineup opens; the default says there is none. */
    opponent: suspend (season: Int, week: Int) -> OpponentResult = { _, _ -> OpponentResult(null, "not available") },
    /** Everyone on a league team, so the rest can be offered as pickups; null hides the pickups. */
    leagueRostered: Flow<LeagueRostered?> = flowOf(null),
    /** The ESPN leagues the user added; two or more show a switcher, and [onLeague] makes one active. */
    leagues: Flow<List<LeagueChoice>> = flowOf(emptyList()),
    setLeague: suspend (String) -> Unit = {},
    /** Who moves up because a starter is hurt this week; asked when My lineup opens, to mark the pickups. */
    opportunities: suspend (season: Int, profile: ScoringProfile) -> OpportunitiesResult = { _, _ -> OpportunitiesResult(emptyList(), 0, null) },
    /** The league's other teams, for Trade; empty hides the mode. */
    otherTeams: Flow<List<MyTeam>> = flowOf(emptyList()),
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val taken by leagueRostered.collectAsStateWithLifecycle(initialValue = null)
    val choices by leagues.collectAsStateWithLifecycle(initialValue = emptyList())
    val others by otherTeams.collectAsStateWithLifecycle(initialValue = emptyList())
    var rival by remember { mutableStateOf<OpponentState>(OpponentState.Idle) }
    var movingUp by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    ProjectionListScreen(
        state, injuries, onPlayer, onBack, team?.takeIf { it.season == season }, rival,
        rostered = taken?.takeIf { it.season == season }?.playerIds,
        starterOut = movingUp,
        leagues = choices,
        onLeague = { id -> scope.launch { setLeague(id) } },
        partners = others.filter { it.season == season },
        onLineupOpened = {
            profile?.let { p ->
                scope.launch {
                    val found = try {
                        opportunities(season, p).rows
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    movingUp = found.mapNotNull { r -> injuredNote(r)?.let { r.beneficiary.player.playerId to it } }.toMap()
                }
            }
            val week = (state as? ProjectionListState.Loaded)?.week
            if (week != null && rival != OpponentState.Loading) {
                rival = OpponentState.Loading
                scope.launch {
                    val result = opponent(season, week)
                    rival = result.team?.let(OpponentState::Loaded) ?: OpponentState.Unavailable(result.message ?: "no opponent found")
                }
            }
        },
    )
}

private enum class ListMode { WEEK, ROS, LINEUP, TRADE }

@Composable
public fun ProjectionListScreen(
    state: ProjectionListState,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    myTeam: MyTeam? = null,
    opponent: OpponentState = OpponentState.Idle,
    /** My lineup was opened: the route fetches the opponent. */
    onLineupOpened: () -> Unit = {},
    /** Everyone on a league team; null when unknown, which hides the waiver pickups. */
    rostered: Set<String>? = null,
    /** Pickups moving up because a starter is hurt, by player id: "RB1 Name is Doubtful". */
    starterOut: Map<String, String> = emptyMap(),
    /** The user's ESPN leagues: with two or more, a chip each switches the league My lineup follows. */
    leagues: List<LeagueChoice> = emptyList(),
    onLeague: (String) -> Unit = {},
    /** The league's other teams: with your team known, a Trade mode weighs trades with them. */
    partners: List<MyTeam> = emptyList(),
) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FLEX) }
    var chosen by rememberSaveable { mutableStateOf(ListMode.WEEK) }
    val canTrade = myTeam != null && partners.isNotEmpty()
    val mode = when {
        chosen == ListMode.LINEUP && myTeam == null -> ListMode.WEEK
        chosen == ListMode.TRADE && !canTrade -> ListMode.WEEK
        else -> chosen
    }
    LaunchedEffect(mode, myTeam?.teamName) { if (mode == ListMode.LINEUP) onLineupOpened() }
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
                    if (leagues.size > 1) {
                        Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (l in leagues) {
                                FilterChip(selected = l.active, onClick = { onLeague(l.leagueId) }, label = { Text(l.name) }, modifier = Modifier.testTag("league:${l.leagueId}"))
                            }
                        }
                    }
                    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        if (canTrade) {
                            FilterChip(
                                selected = mode == ListMode.TRADE,
                                onClick = { chosen = ListMode.TRADE },
                                label = { Text("Trade") },
                                modifier = Modifier.testTag("chip:trade"),
                            )
                        }
                    }
                    if (mode == ListMode.TRADE && myTeam != null) {
                        TradeView(myTeam, partners, state.rosRows)
                    } else if (mode == ListMode.LINEUP && myTeam != null) {
                        val rival = (opponent as? OpponentState.Loaded)?.let { lineupView(it.team, state.week, state.weekRows, badges) }
                        val pickups = if (rostered == null) null else remember(myTeam, state, badges, rostered, starterOut) { waiverPickups(myTeam, state.weekRows, badges, rostered, starterOut) }
                        LineupList(lineupView(myTeam, state.week, state.weekRows, badges), rival, opponent, pickups, badges, onPlayer)
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
private fun LineupList(
    view: LineupView,
    rival: LineupView?,
    opponent: OpponentState,
    pickups: List<PickupLine>?,
    badges: Map<String, String>,
    onPlayer: (String) -> Unit,
) {
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
                if (view.spread > 0.0) {
                    Text(
                        "Likely ${points(view.low)}–${points(view.high)}",
                        Modifier.testTag("lineup:range"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val versus = when {
                    rival != null ->
                        "vs ${rival.teamName}: ${points(rival.total)} pts · ${matchupLine(view.total, rival.total)} · " +
                            winLine(winChance(view, rival))
                    opponent == OpponentState.Loading -> "Checking your opponent…"
                    opponent is OpponentState.Unavailable -> "No comparison: ${opponent.message}."
                    else -> null
                }
                versus?.let {
                    Text(it, Modifier.testTag("lineup:vs"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
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
        if (pickups != null) {
            item { SectionLabel("Waiver pickups") }
            if (pickups.isEmpty()) {
                item {
                    Text(
                        "No pickup helps this week.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(pickups, key = { _, p -> "p:${p.add.playerId}" }) { _, pick -> PickupRow(pick, onPlayer) }
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
private fun PickupRow(pick: PickupLine, onPlayer: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(pick.add.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("pickup:${pick.add.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Add ${pick.add.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(Position.label(pick.add.position), pick.add.team).joinToString(" · ") +
                    " · ${points(pick.add.points)} pts · starts at ${pick.slot}" + (pick.replaces?.let { ", replacing ${it.name}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pick.starterOut?.let {
                Text("▲ $it", Modifier.testTag("pickup:out:${pick.add.playerId}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            pick.drop?.let {
                Text("Drop ${it.name} (${points(it.points)} pts)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("+${points(pick.gain)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
