package dev.gridiron.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.FantasyLeagueRepository
import dev.gridiron.core.data.live.LeagueChoice
import dev.gridiron.core.data.live.LeagueMatchup
import dev.gridiron.core.data.live.MatchupSide
import dev.gridiron.core.data.live.MatchupsResult
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.PullToRefresh
import dev.gridiron.core.designsystem.ScreenBar
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val STARTER_WEEKS = (1..18).toList()

private fun points(x: Double?): String = x?.let { "%.1f".format(Locale.US, it) } ?: "–"

private fun isStarter(slot: String): Boolean = slot != "BE" && slot != "IR"

/** True when a starter has no app number: unmatched, or no stats for the week yet. */
private fun missingApp(matchups: List<LeagueMatchup>): Boolean =
    matchups.any { m -> listOfNotNull(m.home, m.away).any { s -> s.lineup.any { isStarter(it.slot) && it.appPoints == null } } }

/**
 * The league's head-to-heads for one week, ESPN's points with the app's beside them; a matchup opens both lineups.
 * Opens on the league's current week. A failed fetch keeps the matchups already shown and says why.
 */
@Composable
fun MatchupsRoute(
    league: FantasyLeagueRepository,
    scoring: ScoringRepository,
    season: Int,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the app's points load again. */
    dataVersion: Flow<Long> = flowOf(0L),
) {
    val stats by dataVersion.collectAsState(initial = 0L)
    val profile by scoring.active.collectAsState(initial = null)
    val synced by league.league.collectAsState()
    val config by league.config.collectAsState(initial = null)
    val choices by league.leagues.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var week by rememberSaveable { mutableIntStateOf(0) }
    // The league the week belongs to: a switch starts again from the new league's current week.
    var weekLeague by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<Pair<Int, MatchupsResult>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { league.load() }
    LaunchedEffect(synced?.leagueId, synced?.week) {
        val id = synced?.leagueId ?: return@LaunchedEffect
        if (weekLeague != id) {
            weekLeague = id
            week = synced?.week ?: 0
            shown = null
        } else if (week == 0) {
            week = synced?.week ?: 0
        }
    }
    LaunchedEffect(season, week, profile, stats, reload, weekLeague) {
        val p = profile ?: return@LaunchedEffect
        if (week == 0) return@LaunchedEffect
        val result = league.matchups(season, week, p)
        val previous = shown?.takeIf { it.first == week }?.second
        shown = week to if (result.error != null && previous != null && previous.error == null) previous.copy(error = result.error) else result
    }
    MatchupsScreen(
        season = season,
        week = week,
        result = shown?.takeIf { it.first == week }?.second,
        teamNames = synced?.teams?.associate { it.id to it.name }.orEmpty(),
        myTeam = config?.teamId,
        profileName = profile?.name,
        onWeek = { week = it },
        onRefresh = { reload++ },
        onPlayer = onPlayer,
        onBack = onBack,
        leagues = choices,
        onLeague = { id -> scope.launch { league.setActive(id) } },
    )
}

@Composable
fun MatchupsScreen(
    season: Int,
    week: Int,
    result: MatchupsResult?,
    teamNames: Map<Int, String>,
    myTeam: Int?,
    profileName: String?,
    onWeek: (Int) -> Unit,
    onRefresh: () -> Unit,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** The user's ESPN leagues: with two or more, a chip each makes one active. */
    leagues: List<LeagueChoice> = emptyList(),
    onLeague: (String) -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    fun name(id: Int) = teamNames[id] ?: "Team $id"
    val opened = open?.let { result?.matchups?.getOrNull(it) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar(if (opened != null) "Matchup" else "Matchups · $season", onBack = { if (opened != null) open = null else onBack() }) {
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            if (leagues.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in leagues) {
                        FilterChip(
                            selected = l.active,
                            onClick = { open = null; onLeague(l.leagueId) },
                            label = { Text(l.name) },
                            modifier = Modifier.testTag("league:${l.leagueId}"),
                        )
                    }
                }
            }
            if (week != 0) WeekPicker(STARTER_WEEKS, week) { open = null; onWeek(it) }
            result?.error?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            when {
                week == 0 -> Message("Sync your league first (☰ → ESPN league).")
                result == null -> LoadingRows()
                result.matchups.isEmpty() -> Message(if (result.error == null) "No matchups this week." else "Couldn't load this week.", onRefresh)
                opened != null -> Lineups(opened, ::name, myTeam, profileName, onPlayer)
                // The shown week stays until the new one arrives, so the pull's spinner needn't stay.
                else -> PullToRefresh(refreshing = false, onRefresh) { MatchupList(result.matchups, ::name, myTeam, onOpen = { open = it }) }
            }
        }
    }
}

@Composable
private fun MatchupList(matchups: List<LeagueMatchup>, name: (Int) -> String, myTeam: Int?, onOpen: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("matchups-list")) {
        items(matchups.size, key = { "m:${matchups[it].home.teamId}" }) { i ->
            val m = matchups[i]
            Column(Modifier.fillMaxWidth().clickable { onOpen(i) }.testTag("matchup-$i").padding(horizontal = 16.dp, vertical = 10.dp)) {
                SideLine(name(m.home.teamId), m.home, mine = m.home.teamId == myTeam)
                m.away?.let { SideLine(name(it.teamId), it, mine = it.teamId == myTeam) } ?: Text("Bye", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (missingApp(matchups)) {
            item(key = "note") { AppNote() }
        }
    }
}

@Composable
private fun SideLine(team: String, side: MatchupSide, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$team${if (mine) " ★" else ""}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Column(horizontalAlignment = Alignment.End) {
            Text(points(side.espnTotal), fontWeight = FontWeight.Bold)
            Text("(app ${points(side.appTotal)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppNote() {
    Text(
        "App points are a dash for players the app can't match or has no stats for yet; nflverse stats land after games finish.",
        Modifier.fillMaxWidth().padding(16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Lineups(m: LeagueMatchup, name: (Int) -> String, myTeam: Int?, profileName: String?, onPlayer: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("lineups")) {
        lineup(name(m.home.teamId), m.home, m.home.teamId == myTeam, profileName, onPlayer)
        m.away?.let { lineup(name(it.teamId), it, it.teamId == myTeam, profileName, onPlayer) }
        if (missingApp(listOf(m))) item(key = "note") { AppNote() }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lineup(
    team: String,
    side: MatchupSide,
    mine: Boolean,
    profileName: String?,
    onPlayer: (String) -> Unit,
) {
    item(key = "h:${side.teamId}") {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$team${if (mine) " ★" else ""}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Total ${points(side.espnTotal)} (app ${points(side.appTotal)})", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(120.dp)) {
                Row(horizontalArrangement = Arrangement.End) {
                    Text("ESPN", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
                    Text("App", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall)
                }
                profileName?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    items(side.lineup, key = { "p:${side.teamId}:${it.espnId}" }) { p ->
        Row(
            Modifier.fillMaxWidth().clickable(enabled = p.playerId != null) { p.playerId?.let(onPlayer) }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.slot, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.name, Modifier.weight(1f))
            Text(points(p.espnPoints), Modifier.width(56.dp))
            Text(points(p.appPoints), Modifier.width(56.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
