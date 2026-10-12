package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.Kickoffs
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.ActivityResult
import dev.gridiron.core.data.live.FantasyLeague
import dev.gridiron.core.data.live.LeagueMatchup
import dev.gridiron.core.data.live.LeagueTeam
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.OpponentResult
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.SectionHeader
import dev.gridiron.core.designsystem.StatusBadge
import dev.gridiron.core.designsystem.SummaryCard
import dev.gridiron.core.designsystem.TeamStripe
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.model.ScoringProfile
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** One finished week of the opponent's: their ESPN [score] against the league's [average] team score that week. */
internal data class RecentScore(val week: Int, val score: Double, val average: Double)

/** Everything the Scout screen shows about this week's opponent; every part but the lineup is optional. */
internal data class ScoutReport(
    /** Their best lineup this week under the user's slots and scoring. */
    val view: LineupView,
    /** Their record and points, from the last league sync; null when the team isn't in it. */
    val team: LeagueTeam? = null,
    val teams: Int = 0,
    val recent: List<RecentScore> = emptyList(),
    val hurt: List<HurtLine> = emptyList(),
    /** The matchup slot by slot against the user's best lineup; null without it. */
    val preview: MatchupPreview? = null,
    /** Their latest moves, newest first, as League activity words them. */
    val moves: List<String> = emptyList(),
    val faabLeft: Int? = null,
)

/** The opponent's ESPN score in each of [weeks]' matchups, with that week's league average; a week they sat out is skipped. */
internal fun recentScores(teamId: Int, weeks: Map<Int, List<LeagueMatchup>>): List<RecentScore> =
    weeks.toSortedMap().mapNotNull { (week, games) ->
        val sides = games.flatMap { listOfNotNull(it.home, it.away) }
        val theirs = sides.firstOrNull { it.teamId == teamId } ?: return@mapNotNull null
        RecentScore(week, theirs.espnTotal, sides.map { it.espnTotal }.average())
    }

/** "4-2 · 3rd of 12 · 612.4 for, 580.1 against" (a tie adds "-1"). */
internal fun recordLine(team: LeagueTeam, teams: Int): String =
    "${team.wins}-${team.losses}" + (if (team.ties > 0) "-${team.ties}" else "") +
        " · ${placeText(team.rank)} of $teams · ${one(team.pointsFor)} for, ${one(team.pointsAgainst)} against"

/** "Last 3: wk 4 120.3 · wk 5 98.2 · wk 6 131.0 (league 110.4 a week)". */
internal fun recentLine(recent: List<RecentScore>): String =
    "Last ${recent.size}: " + recent.joinToString(" · ") { "wk ${it.week} ${one(it.score)}" } +
        " (league ${one(recent.map { it.average }.average())} a week)"

/**
 * Projections → Home → Scout: this week's opponent at a glance. Their record and recent scores (ESPN's matchups of the
 * last three weeks), their best lineup under your scoring with who's hurt, where each side is ahead slot by slot, and
 * their latest moves.
 */
@Composable
public fun ScoutRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    badges: Flow<Map<String, String>>,
    myTeam: Flow<MyTeam?>,
    league: Flow<FantasyLeague?>,
    opponent: suspend (season: Int, week: Int) -> OpponentResult,
    /** A finished week's matchups, ESPN's scores. */
    matchups: suspend (season: Int, week: Int) -> List<LeagueMatchup>,
    activity: suspend (season: Int) -> ActivityResult?,
    kickoffs: suspend (season: Int, week: Int) -> Kickoffs,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val snapshot by league.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    val loaded = state as? ProjectionListState.Loaded
    var rival by remember { mutableStateOf<OpponentResult?>(null) }
    var locked by remember { mutableStateOf(Kickoffs(emptySet(), emptySet())) }
    var weeks by remember { mutableStateOf<Map<Int, List<LeagueMatchup>>>(emptyMap()) }
    var moves by remember { mutableStateOf<ActivityResult?>(null) }
    LaunchedEffect(loaded?.week) {
        val week = loaded?.week ?: return@LaunchedEffect
        rival = soft { opponent(season, week) } ?: OpponentResult(null, "couldn't read the matchups")
        locked = soft { kickoffs(season, week) } ?: Kickoffs(emptySet(), emptySet())
        weeks = ((week - RECENT_WEEKS).coerceAtLeast(1) until week).mapNotNull { w -> soft { matchups(season, w) }?.let { w to it } }.toMap()
        moves = soft { activity(season) }
    }
    val them = (rival?.team)
    val report = if (loaded != null && them != null) {
        remember(loaded, them, injuries, locked, weeks, moves, snapshot, team) {
            val rows = loaded.weekRows.map { confirmedActive(it, injuries, locked.inactivesPosted) }
            val view = lineupView(them, loaded.week, rows, injuries, locked.started)
            val leagueTeam = snapshot?.teams?.firstOrNull { it.name == them.teamName }
            val mine = team?.takeIf { it.season == season }?.let { lineupView(it, loaded.week, rows, injuries, locked.started) }
            val activityResult = moves
            ScoutReport(
                view = view,
                team = leagueTeam,
                teams = snapshot?.teams?.size ?: 0,
                recent = leagueTeam?.let { recentScores(it.id, weeks) }.orEmpty(),
                hurt = hurtLines(view, injuries),
                preview = mine?.let { matchupPreview(it, view) },
                moves = if (leagueTeam == null || activityResult == null) {
                    emptyList()
                } else {
                    activityResult.items.filter { i -> i.moves.any { it.fromTeamId == leagueTeam.id || it.toTeamId == leagueTeam.id } }
                        .take(MOVES_SHOWN).map { activityText(it, activityResult.teams) }
                },
                faabLeft = snapshot?.faabBudget?.let { budget -> leagueTeam?.faabSpent?.let { (budget - it).coerceAtLeast(0) } },
            )
        }
    } else {
        null
    }
    ScoutScreen(report, message = rival?.message?.takeIf { them == null }, onPlayer = onPlayer, onBack = onBack)
}

private const val RECENT_WEEKS = 3
private const val MOVES_SHOWN = 5

private suspend fun <T> soft(block: suspend () -> T?): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
internal fun ScoutScreen(report: ScoutReport?, message: String?, onPlayer: (String) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar(report?.let { "Scout: ${it.view.teamName}" } ?: "Scout", onBack)
            when {
                report != null -> ScoutList(report, onPlayer)
                message != null -> EmptyState("No opponent to scout: $message.")
                else -> LoadingRows()
            }
        }
    }
}

@Composable
private fun ScoutList(r: ScoutReport, onPlayer: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("scout")) {
        item {
            SummaryCard {
                Text(r.view.teamName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                r.team?.let { Text(recordLine(it, r.teams), Modifier.testTag("scout:record"), style = MaterialTheme.typography.bodyMedium) }
                if (r.recent.isNotEmpty()) Text(recentLine(r.recent), Modifier.testTag("scout:recent"), style = MaterialTheme.typography.bodySmall)
                r.faabLeft?.let { Text("$$it FAAB left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "Projected ${one(r.view.total)} this week under your scoring",
                    Modifier.testTag("scout:total"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        item { SectionHeader("Their lineup, week ${r.view.week}") }
        items(r.view.starters.filter { it.row != null }, key = { "s:${it.slot}:${it.row!!.playerId}" }) { line ->
            val row = line.row!!
            Row(
                Modifier.fillMaxWidth().playerClick(row.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(line.slot, Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TeamStripe(row.team, height = 20.dp)
                Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (row.out) "Out" else one(row.points), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (r.hurt.isNotEmpty()) {
            item { SectionHeader("Their players to watch") }
            items(r.hurt, key = { "h:${it.playerId}" }) { h ->
                Row(
                    Modifier.fillMaxWidth().playerClick(h.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 6.dp).testTag("scout:hurt:${h.playerId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(h.slot, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusBadge(h.abbr)
                }
            }
        }
        r.preview?.let { p ->
            val ahead = p.slots.filter { it.edge > 0 }.sortedByDescending { it.edge }.take(EDGES)
            val behind = p.slots.filter { it.edge < 0 }.sortedBy { it.edge }.take(EDGES)
            if (ahead.isNotEmpty() || behind.isNotEmpty()) {
                item { SectionHeader("Where the matchup tilts") }
                items(ahead + behind, key = { "e:${it.slot}:${it.mine?.playerId}:${it.theirs?.playerId}" }) { e ->
                    Text(
                        "${e.slot}: ${e.mine?.name ?: "—"} vs ${e.theirs?.name ?: "—"} · " + (if (e.edge > 0) "you +" else "them +") + one(kotlin.math.abs(e.edge)),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (e.edge > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (r.moves.isNotEmpty()) {
            item { SectionHeader("Their latest moves") }
            items(r.moves.withIndex().toList(), key = { "m:${it.index}" }) { (i, text) ->
                Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("scout:move:$i"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Slots shown on each side of the tilt. */
private const val EDGES = 3

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
