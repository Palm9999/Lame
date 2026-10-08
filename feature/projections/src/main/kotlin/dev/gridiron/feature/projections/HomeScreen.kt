package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.GameState
import dev.gridiron.core.data.Kickoffs
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.LivePlayer
import dev.gridiron.core.data.live.LiveWin
import dev.gridiron.core.data.live.LiveWinChance
import dev.gridiron.core.data.live.MyMatchup
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.data.live.OpponentResult
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.Meter
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.SectionHeader
import dev.gridiron.core.designsystem.ShareLine
import dev.gridiron.core.designsystem.StatusBadge
import dev.gridiron.core.designsystem.SummaryCard
import dev.gridiron.core.designsystem.TeamStripe
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.model.ScoringProfile
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A player of the user's with an ESPN designation worth a look this week. */
internal data class HurtLine(val playerId: String, val name: String, val team: String?, val abbr: String, val slot: String)

/** What the Home tab shows; every part but the team is optional. */
internal data class HomeState(
    val teamName: String,
    val week: Int,
    val myTotal: Double,
    val rivalName: String? = null,
    val rivalTotal: Double? = null,
    /** The pre-game chance from both lineups' projections; null without an opponent. */
    val chance: Double? = null,
    /** The live score and chance while a game is on. */
    val live: LiveWinChance? = null,
    /** Today's win chances so far, oldest first, for the line. */
    val line: List<Double> = emptyList(),
    val hurt: List<HurtLine> = emptyList(),
    val pickup: PickupLine? = null,
    val news: List<NewsItem> = emptyList(),
    /** "Sun 1:00 PM · Start Name (QB), …": the next kickoff with players of the user's in it. */
    val nextKickoff: String? = null,
)

private val WATCH = setOf("O", "D", "Q", "IR", "SUSP")

/** The user's starters and bench with a designation in [WATCH], starters first. */
internal fun hurtLines(view: LineupView, badges: Map<String, String>): List<HurtLine> =
    (view.starters.mapNotNull { l -> l.row?.let { it to l.slot } } + view.bench.map { it to "BE" })
        .mapNotNull { (row, slot) -> badges[row.playerId]?.takeIf { it in WATCH }?.let { HurtLine(row.playerId, row.name, row.team, it, slot) } }

/**
 * The Home tab: the user's week at a glance. It reads what My lineup reads (projections, the league, kickoffs and the
 * live scoreboard) and keeps the day's win chances through [winLine], asking again each minute while a game is on.
 */
@Composable
public fun HomeRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    badges: Flow<Map<String, String>>,
    myTeam: Flow<MyTeam?>,
    leagueRostered: Flow<LeagueRostered?>,
    opponent: suspend (season: Int, week: Int) -> OpponentResult,
    kickoffs: suspend (season: Int, week: Int) -> Kickoffs,
    liveWeek: suspend (season: Int, week: Int) -> ScoresWeek?,
    myMatchup: suspend (season: Int, week: Int, profile: ScoringProfile) -> MyMatchup,
    news: suspend () -> List<NewsItem>,
    /** Records [chance] (when not null) as the latest of the week's win chances and returns them all, oldest first. */
    winLine: suspend (season: Int, week: Int, chance: Double?) -> List<Double>,
    onPlayer: (String) -> Unit,
    onLineup: () -> Unit,
    onMatchups: () -> Unit,
    onLeague: () -> Unit,
    dataVersion: Flow<Long> = flowOf(0L),
    /** The home-screen widget's summary, win chance and players to watch, whenever they change. */
    onSummary: (summary: String, chance: Double?, watch: String?) -> Unit = { _, _, _ -> },
) {
    val vm: ProjectionListViewModel = viewModel(factory = ProjectionListViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val injuries by badges.collectAsStateWithLifecycle(initialValue = emptyMap())
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val taken by leagueRostered.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, profile, version) { profile?.let { vm.load(season, it) } }
    val loaded = state as? ProjectionListState.Loaded
    val mine = team?.takeIf { it.season == season }
    var rival by remember { mutableStateOf<MyTeam?>(null) }
    var locked by remember { mutableStateOf(Kickoffs(emptySet(), emptySet())) }
    var stories by remember { mutableStateOf<List<NewsItem>>(emptyList()) }
    var live by remember { mutableStateOf<LiveWinChance?>(null) }
    var line by remember { mutableStateOf<List<Double>>(emptyList()) }
    LaunchedEffect(loaded?.week, mine?.teamName) {
        val week = loaded?.week ?: return@LaunchedEffect
        rival = soft { opponent(season, week).team }
        locked = soft { kickoffs(season, week) } ?: Kickoffs(emptySet(), emptySet())
        stories = soft { news() }.orEmpty()
    }
    val view = if (loaded != null && mine != null) {
        remember(loaded, mine, injuries, locked) {
            val rows = loaded.weekRows.map { confirmedActive(it, injuries, locked.inactivesPosted) }
            lineupView(mine, loaded.week, rows, injuries, locked.started)
        }
    } else {
        null
    }
    val rivalView = if (loaded != null && rival != null && view != null) {
        remember(loaded, rival, injuries, locked) { lineupView(rival!!, loaded.week, loaded.weekRows, injuries, locked.started) }
    } else {
        null
    }
    val chance = if (view != null && rivalView != null) winChance(view, rivalView) else null
    // The day's line: the pre-game chance, then each live minute's.
    LaunchedEffect(loaded?.week, chance, profile) {
        val week = loaded?.week ?: return@LaunchedEffect
        val active = profile ?: return@LaunchedEffect
        line = soft { winLine(season, week, chance) }.orEmpty()
        while (true) {
            val now = soft {
                val scoreboard = liveWeek(season, week) ?: return@soft null
                if (scoreboard.games.none { it.state == GameState.LIVE }) return@soft null
                val m = myMatchup(season, week, active)
                val a = m.mine ?: return@soft null
                val b = m.theirs ?: return@soft null
                LiveWin.estimate(a, b, scoreboard, loaded.weekRows.associate { it.playerId to LivePlayer(it.team, it.points, spread(it)) })
            }
            live = now
            if (now == null) break
            line = soft { winLine(season, week, now.chance) }.orEmpty()
            delay(LIVE_REFRESH_MILLIS)
        }
    }
    val home = if (view != null) {
        val myIds = mine?.players?.mapNotNull { it.playerId }?.toSet().orEmpty()
        HomeState(
            teamName = view.teamName,
            week = view.week,
            myTotal = view.total,
            rivalName = rivalView?.teamName,
            rivalTotal = rivalView?.total,
            chance = chance,
            live = live,
            line = line,
            hurt = hurtLines(view, injuries),
            pickup = taken?.takeIf { it.season == season }?.let { r ->
                remember(mine, loaded, injuries, r, locked) { waiverPickups(mine!!, loaded!!.weekRows, injuries, r.playerIds, started = locked.started).firstOrNull() }
            },
            news = stories.filter { n -> n.players.any { it.playerId in myIds } }.take(4),
            nextKickoff = gameDay(view, locked.times, injuries, Instant.now(), locked.inactivesPosted)?.windows?.firstOrNull()?.let {
                "${kickoffText(it.kickoff)} · ${windowText(it)}"
            },
        )
    } else {
        null
    }
    if (view != null && home != null) {
        val l = home.live
        val summary = if (l != null) {
            "Wk ${view.week} · ${pts(l.myScore)}–${pts(l.theirScore)} live vs ${home.rivalName} · ${winLine(l.chance)}"
        } else {
            widgetSummary(view, rivalView)
        }
        val watch = home.hurt.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.name} ${it.abbr}" }
        LaunchedEffect(summary, watch) { onSummary(summary, l?.chance ?: home.chance, watch) }
    }
    HomeScreen(
        home,
        loading = loaded == null && state !is ProjectionListState.Unavailable,
        noTeam = loaded != null && mine == null,
        onPlayer = onPlayer,
        onLineup = onLineup,
        onMatchups = onMatchups,
        onLeague = onLeague,
    )
}

private suspend fun <T> soft(block: suspend () -> T?): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
internal fun HomeScreen(
    home: HomeState?,
    loading: Boolean,
    noTeam: Boolean,
    onPlayer: (String) -> Unit,
    onLineup: () -> Unit,
    onMatchups: () -> Unit,
    onLeague: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar(home?.let { "Week ${it.week}" } ?: "Home", onBack = null)
            when {
                home != null -> HomeCards(home, onPlayer, onLineup, onMatchups)
                noTeam -> EmptyState("Pick your team in ESPN leagues to see your week here.", onRetry = onLeague)
                loading -> LoadingRows()
                else -> EmptyState("No projections for this week yet. Refresh stats.")
            }
        }
    }
}

@Composable
private fun HomeCards(home: HomeState, onPlayer: (String) -> Unit, onLineup: () -> Unit, onMatchups: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("home")) {
        item { MatchupCard(home, onLineup, onMatchups) }
        home.nextKickoff?.let { text ->
            item {
                SummaryCard {
                    Text("Next kickoff", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, Modifier.testTag("home:kickoff"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (home.hurt.isNotEmpty()) {
            item { SectionHeader("Your players to watch") }
            items(home.hurt, key = { "hurt:${it.playerId}" }) { h ->
                Row(
                    Modifier.fillMaxWidth().playerClick(h.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 8.dp).testTag("home:hurt:${h.playerId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TeamStripe(h.team, height = 20.dp)
                    Text(h.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(h.slot, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusBadge(h.abbr)
                }
            }
        }
        home.pickup?.let { p ->
            item { SectionHeader("Best pickup") }
            item {
                Column(Modifier.fillMaxWidth().playerClick(p.add.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 8.dp).testTag("home:pickup")) {
                    Text("Add ${p.add.name} · +${pts(p.gain)} pts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Starts at ${p.slot}" + (p.replaces?.let { ", replacing ${it.name}" } ?: "") + (p.drop?.let { " · drop ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (home.news.isNotEmpty()) {
            item { SectionHeader("News on your players") }
            items(home.news, key = { "news:${it.id}" }) { n ->
                val id = n.players.firstNotNullOfOrNull { it.playerId }
                Text(
                    n.headline,
                    Modifier.fillMaxWidth().then(if (id != null) Modifier.playerClick(id, onPlayer) else Modifier).padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MatchupCard(home: HomeState, onLineup: () -> Unit, onMatchups: () -> Unit) {
    SummaryCard(Modifier.testTag("home:matchup")) {
        Text(home.teamName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val live = home.live
        val mine = live?.myScore ?: home.myTotal
        val theirs = live?.theirScore ?: home.rivalTotal
        Row(verticalAlignment = Alignment.Bottom) {
            Text(pts(mine), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (theirs != null) {
                Text(" – ${pts(theirs)}", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            when {
                live != null -> "Live vs ${home.rivalName} · heading for ${pts(live.myExpected)}–${pts(live.theirExpected)}"
                home.rivalName != null -> "Projected vs ${home.rivalName}"
                else -> "Projected"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (live != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val chance = live?.chance ?: home.chance
        if (chance != null) {
            Meter(chance, winLine(chance), Modifier.padding(top = 8.dp))
            Text(winLine(chance), Modifier.testTag("home:chance"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        if (home.line.size >= 2) {
            ShareLine(home.line, "Win chance this week: ${home.line.joinToString { winLine(it) }}", Modifier.padding(top = 8.dp).testTag("home:line"))
            Text("Your win chance as it moved this week", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onLineup, modifier = Modifier.testTag("home:lineup")) { Text("My lineup") }
            if (home.rivalName != null) TextButton(onClick = onMatchups) { Text("Matchups") }
        }
    }
}

private fun pts(v: Double): String = String.format(Locale.US, "%.1f", v)
