package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.PlayoffPicture
import dev.gridiron.core.model.Position
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import dev.gridiron.core.projections.PlayoffOdds
import dev.gridiron.core.projections.Playoffs
import dev.gridiron.core.projections.ReplacementLevel
import dev.gridiron.core.projections.SimGame
import dev.gridiron.core.projections.SimTeam
import dev.gridiron.core.projections.TeamWeek
import dev.gridiron.core.projections.Trades
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Playoff odds: asked for when the mode opens, being fetched, found, or why not. */
public sealed interface PlayoffState {
    public data object Idle : PlayoffState

    public data object Loading : PlayoffState

    public data class Loaded(val picture: PlayoffPicture) : PlayoffState

    public data class Unavailable(val message: String) : PlayoffState
}

/**
 * Every team's playoff odds: each roster's best lineup in each remaining matchup from [rosWeekly] (a player's position
 * from [rosRows]; one without a projection counts as no one), the games [PlayoffPicture.remaining] simulated from the
 * records so far. Null without weekly projections.
 */
internal fun playoffOdds(picture: PlayoffPicture, rosRows: List<ProjectionRow>, rosWeekly: Map<String, Map<Int, Double>>): List<PlayoffOdds>? {
    if (rosWeekly.isEmpty()) return null
    val league = picture.league
    val slots = league.lineupSlots.ifEmpty { Lineups.DEFAULT_SLOTS }
    val position = rosRows.associate { it.playerId to it.position }
    val length = league.periodWeeks
    val periods = picture.remaining.map { it.period }.distinct()
    val weekly = league.teams.associate { team ->
        val roster = team.players.mapNotNull { p ->
            val id = p.playerId ?: return@mapNotNull null
            val pos = position[id] ?: return@mapNotNull null
            val w = rosWeekly[id].orEmpty()
            LineupCandidate(id, pos, w.values.sum(), w)
        }
        val byWeek = Playoffs.teamWeeks(slots, roster, periods.flatMap { p -> ((p - 1) * length + 1)..(p * length) })
        // A matchup of several weeks sums them, their spreads combined as independent.
        team.id to periods.associateWith { p ->
            val weeks = ((p - 1) * length + 1)..(p * length)
            TeamWeek(weeks.sumOf { byWeek[it]?.mean ?: 0.0 }, sqrt(weeks.sumOf { (byWeek[it]?.sd ?: 0.0).let { sd -> sd * sd } }))
        }
    }
    val teams = league.teams.map { SimTeam(it.id, it.name, it.wins + it.ties / 2.0, it.losses + it.ties / 2.0, it.pointsFor) }
    val games = picture.remaining.mapNotNull { g -> g.awayId?.let { SimGame(g.period, g.homeId, it) } }
    return Playoffs.simulate(teams, games, weekly, league.playoffTeams ?: DEFAULT_PLAYOFF_TEAMS)
}

/** One team in the power rankings: its rest-of-season [value] and the positions where it most leads and trails the league. */
internal data class PowerRow(val teamId: Int, val name: String, val value: Double, val strongest: String?, val weakest: String?)

/**
 * Every team ranked by its roster's rest-of-season worth ([Trades.value]: each remaining week's best lineup, byes
 * counted, plus a little bench depth). A team's strongest and weakest positions compare its best lineup's starters
 * there (points over replacement, [ReplacementLevel] across every projected player) with the league's average team.
 */
internal fun powerRankings(picture: PlayoffPicture, rosRows: List<ProjectionRow>, rosWeekly: Map<String, Map<Int, Double>>): List<PowerRow> {
    val league = picture.league
    val slots = league.lineupSlots.ifEmpty { Lineups.DEFAULT_SLOTS }
    val byId = rosRows.associateBy { it.playerId }
    val everyone = rosRows.map { LineupCandidate(it.playerId, it.position, it.points) }
    val values = ReplacementLevel.values(everyone, ReplacementLevel.of(everyone, league.teams.size.coerceAtLeast(1), slots))
    val rosters = league.teams.associate { team ->
        team.id to team.players.mapNotNull { p ->
            val row = p.playerId?.let(byId::get) ?: return@mapNotNull null
            LineupCandidate(row.playerId, row.position, row.points, rosWeekly[row.playerId].orEmpty())
        }
    }
    val byPosition = rosters.mapValues { (_, roster) ->
        Lineups.best(slots, roster).spots.mapNotNull { it.player }.groupBy { it.position }
            .mapValues { (_, ps) -> ps.sumOf { values[it.playerId] ?: 0.0 } }
    }
    val positions = byPosition.values.flatMap { it.keys }.distinct()
    val average = positions.associateWith { pos -> byPosition.values.sumOf { it[pos] ?: 0.0 } / byPosition.size.coerceAtLeast(1) }
    return league.teams.map { team ->
        val edge = positions.associateWith { pos -> (byPosition[team.id]?.get(pos) ?: 0.0) - average.getValue(pos) }
        PowerRow(
            team.id, team.name, Trades.value(slots, rosters[team.id].orEmpty()),
            edge.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key, edge.minByOrNull { it.value }?.takeIf { it.value < 0 }?.key,
        )
    }.sortedByDescending { it.value }
}

/** ESPN's default when the snapshot predates the setting. */
private const val DEFAULT_PLAYOFF_TEAMS = 4

/** "74%", never a certainty either way unless the standings already settle it. */
internal fun chanceText(chance: Double): String = when {
    chance >= 1.0 -> "100%"
    chance <= 0.0 -> "0%"
    else -> "${(chance * 100).roundToInt().coerceIn(1, 99)}%"
}

@Composable
internal fun PlayoffsView(state: PlayoffState, rosRows: List<ProjectionRow>, rosWeekly: Map<String, Map<Int, Double>>) {
    val picture = (state as? PlayoffState.Loaded)?.picture
    val odds by produceState<List<PlayoffOdds>?>(null, picture, rosWeekly) {
        value = picture?.let { withContext(Dispatchers.Default) { playoffOdds(it, rosRows, rosWeekly) } }
    }
    val power by produceState<List<PowerRow>?>(null, picture, rosWeekly) {
        value = picture?.let { withContext(Dispatchers.Default) { powerRankings(it, rosRows, rosWeekly) } }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("playoffs")) {
        when {
            state is PlayoffState.Unavailable -> item { Note("No playoff odds: ${state.message}.") }
            picture == null -> item { Note("Reading the league's schedule…") }
            rosWeekly.isEmpty() -> item { Note("Refresh stats to get weekly projections; playoff odds need them.") }
            odds == null -> item { Note("Simulating the season…") }
            else -> {
                power?.let { rows ->
                    item { Label("Power rankings: rest-of-season roster strength") }
                    itemsIndexed(rows, key = { _, r -> "power:${r.teamId}" }) { i, r ->
                        val mine = r.teamId == picture.myTeamId
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("power:${r.teamId}"), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(r.name + if (mine) " (you)" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(r.strongest?.let { "best at ${Position.label(it)}" }, r.weakest?.let { "thin at ${Position.label(it)}" })
                                        .joinToString(" · ").ifEmpty { "about average everywhere" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(String.format(Locale.US, "%.0f", r.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    item { Label("Playoff odds") }
                }
                val cut = picture.league.playoffTeams ?: DEFAULT_PLAYOFF_TEAMS
                item {
                    Note(
                        "${String.format(Locale.US, "%,d", Playoffs.SIMS)} simulated seasons from rest-of-season projections and " +
                            "the records so far (${picture.remaining.size} games left); the top $cut by wins, then points for, make it.",
                    )
                }
                itemsIndexed(odds.orEmpty(), key = { _, o -> "team:${o.teamId}" }) { _, o ->
                    val team = picture.league.teams.firstOrNull { it.id == o.teamId }
                    val mine = o.teamId == picture.myTeamId
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("odds:${o.teamId}"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.name + if (mine) " (you)" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold)
                            Text(
                                "Now ${team?.let { record(it.wins.toDouble(), it.losses.toDouble(), it.ties) } ?: "?"} · " +
                                    "projected ${record(o.expectedWins, o.expectedLosses, 0)} · top seed ${chanceText(o.topSeedChance)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(chanceText(o.playoffChance), Modifier.testTag("odds:chance:${o.teamId}"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    Note(
                        "Each week a team scores its best lineup's projection, give or take how much players vary week to week. " +
                            "Not counted: injuries yet to come, trades and pickups, and divisions or other tiebreakers.",
                    )
                }
            }
        }
    }
}

/** "8–5" or "8.4–5.6"; a tie shows as "–1". */
private fun record(wins: Double, losses: Double, ties: Int): String {
    fun n(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
    return "${n(wins)}–${n(losses)}" + if (ties > 0) "–$ties" else ""
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
