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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.remember
import dev.gridiron.core.data.live.ActivityResult
import dev.gridiron.core.data.live.LeagueTrend
import dev.gridiron.core.data.live.leagueTrends
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.WaiverTrendsResult
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/** More → Waiver trends: who ESPN's public leagues are adding and dropping, with the app's points beside them. */
@Composable
public fun WaiverTrendsRoute(
    season: Int,
    trends: suspend (Int) -> WaiverTrendsResult,
    points: suspend (Int, ScoringProfile) -> TrendPoints,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the points load again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** Everyone on a league team, for the owner tags; null when no league is synced. */
    league: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
    /** The synced league's executed moves, for the My league chip; null without a league. */
    leagueActivity: suspend (Int) -> ActivityResult? = { null },
) {
    var inLeague by remember { mutableStateOf<List<LeagueTrend>>(emptyList()) }
    LaunchedEffect(season) {
        inLeague = try {
            leagueActivity(season)?.items?.let { leagueTrends(it, System.currentTimeMillis() - 7 * 24 * 3_600_000L) }.orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // a bonus: ESPN's public trends stand alone
        }
    }
    val vm: WaiverTrendsViewModel = viewModel(factory = WaiverTrendsViewModel.factory(trends, points))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val taken by league.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    WaiverTrendsScreen(
        state,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season }?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(),
        onPlayer,
        onBack,
        inLeague,
    )
}

private val TREND_POSITIONS = listOf(null, "QB", "RB", "WR", "TE", "K", "DST")

@Composable
public fun WaiverTrendsScreen(
    state: WaiverTrendsState,
    league: LeagueRostered?,
    mine: Set<String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Adds and drops in the user's own league over the last week; empty hides the My league chip. */
    inLeague: List<LeagueTrend> = emptyList(),
) {
    var added by rememberSaveable { mutableStateOf(true) }
    var myLeague by rememberSaveable { mutableStateOf(false) }
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Waiver trends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                WaiverTrendsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is WaiverTrendsState.Loaded -> {
                    val result = state.result
                    Text(
                        if (result.weekly) {
                            "Change in ESPN leagues' roster % over the last week, ESPN's own change beside it."
                        } else {
                            "ESPN's change in roster %. The change over a week shows once the phone has a week of daily snapshots."
                        },
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    result.error?.let {
                        Text(
                            "Not updated: $it.",
                            Modifier.padding(horizontal = 16.dp).testTag("trends:error"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = added, onClick = { added = true }, label = { Text("Most added") }, modifier = Modifier.testTag("chip:added"))
                        FilterChip(selected = !added, onClick = { added = false }, label = { Text("Most dropped") }, modifier = Modifier.testTag("chip:dropped"))
                        if (inLeague.isNotEmpty()) {
                            FilterChip(selected = myLeague, onClick = { myLeague = !myLeague }, label = { Text("My league") }, modifier = Modifier.testTag("chip:myLeague"))
                        }
                        if (league != null) {
                            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free agents") }, modifier = Modifier.testTag("chip:free"))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in TREND_POSITIONS) {
                            FilterChip(
                                selected = position == p,
                                onClick = { position = p },
                                label = { Text(p?.let(Position::label) ?: "All") },
                                modifier = Modifier.testTag("pos:${p ?: "all"}"),
                            )
                        }
                    }
                    if (myLeague && inLeague.isNotEmpty()) {
                        val moves = inLeague.filter { if (added) it.adds > 0 else it.drops > 0 }
                            .sortedWith(compareByDescending<LeagueTrend> { if (added) it.adds else it.drops }.thenBy { it.name.orEmpty() })
                        Text("Adds and drops in your league over the last week.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(moves, key = { "league:${it.espnId}" }) { t ->
                                Row(
                                    Modifier.fillMaxWidth().then(t.playerId?.let { id -> Modifier.clickable { onPlayer(id) } } ?: Modifier)
                                        .padding(horizontal = 16.dp, vertical = 8.dp).testTag("leagueTrend:${t.espnId}"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(t.name ?: "A player", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(if (added) "${t.adds} added" else "${t.drops} dropped", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        return@Column
                    }
                    val rows = trendRows(result, state.points, added, position)
                        .map { it to it.trend.playerId?.let { id -> ownerOf(id, league, mine) } }
                        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
                    if (rows.isEmpty()) {
                        Text("None right now.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { (row, _) -> row.trend.espnId }) { (row, owner) ->
                            TrendListRow(row, result.weekly, state.points.week, owner, onPlayer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendListRow(row: TrendRow, weekly: Boolean, week: Int?, owner: Owner?, onPlayer: (String) -> Unit) {
    val t = row.trend
    val id = t.playerId
    Row(
        Modifier.fillMaxWidth()
            .then(if (id != null) Modifier.clickable { onPlayer(id) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("trend:${t.espnId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(t.position?.let(Position::label), t.team, "${pct(t.percentOwned)}% rostered").joinToString(" · ") +
                    if (weekly) " · ESPN ${signed(t.espnChange)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val projected = listOfNotNull(
                row.weekPoints?.let { "Week $week ${pct(it)}" },
                row.rosPoints?.let { "rest of season ${pct(it)}" },
            )
            if (projected.isNotEmpty()) {
                Text(projected.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            owner?.let {
                Text(
                    when (it) {
                        Owner.FreeAgent -> "Free agent"
                        Owner.Yours -> "Yours"
                        is Owner.Other -> "On ${it.team}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (it == Owner.FreeAgent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(signed(row.change), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun pct(value: Double): String = String.format(Locale.US, "%.1f", value)
