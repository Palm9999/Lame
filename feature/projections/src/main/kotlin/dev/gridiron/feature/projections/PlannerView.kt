package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.designsystem.SectionHeader
import dev.gridiron.core.designsystem.playerClick
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import java.util.Locale

/**
 * A remaining week the best lineup can't fill a [slot]: [why] names the rostered players who could have and why they
 * can't ("Kelce on bye"); [fill] is the free agent who would fill it best that week, with his points.
 */
internal data class Hole(val week: Int, val slot: String, val why: String, val fill: Pair<ProjectionRow, Double>?)

/** One week's best free-agent D/ST or kicker: his points that week, against the user's own best at the position. */
internal data class Stream(val week: Int, val position: String, val pick: ProjectionRow, val points: Double, val yours: Double?)

/**
 * The slots [team]'s best lineup leaves empty in each of [weeks], from each player's points that week ([rosWeekly]; a
 * player with none can't play). A rostered player who fits the slot is named with why he can't: his team's bye
 * ([byes]), else no projection that week (hurt or out for now).
 */
internal fun holes(
    team: MyTeam,
    rosRows: List<ProjectionRow>,
    rosWeekly: Map<String, Map<Int, Double>>,
    byes: Map<String, Set<Int>>,
    rostered: Set<String>,
    weeks: List<Int>,
): List<Hole> {
    val byId = rosRows.associateBy { it.playerId }
    val mine = team.players.mapNotNull { p -> p.playerId?.let(byId::get) }
    val free = rosRows.filter { it.playerId !in rostered && it.playerId !in mine.map(ProjectionRow::playerId) }
    return weeks.flatMap { w ->
        fun candidate(r: ProjectionRow) = LineupCandidate(r.playerId, r.position, rosWeekly[r.playerId]?.get(w) ?: 0.0)
        val roster = mine.map(::candidate).filter { it.points > 0.0 }
        val best = Lineups.best(team.slots, roster)
        best.spots.filter { it.player == null }.map { spot ->
            val out = mine.filter { Lineups.fits(spot.slot, it.position) }.map { r ->
                if (r.team != null && w in byes[r.team].orEmpty()) "${r.name} on bye" else "${r.name} not projected"
            }
            val agents = free.map(::candidate).filter { it.points > 0.0 && Lineups.fits(spot.slot, it.position) }
            val pick = agents.maxByOrNull { it.points }
            Hole(w, spot.slot, out.joinToString(", ").ifEmpty { "no one rostered plays it" }, pick?.let { byId.getValue(it.playerId) to it.points })
        }
    }
}

/**
 * For each of [weeks], the best free-agent D/ST and kicker by that week's points ([rosWeekly]), beside the user's own
 * best that week (null when he has none projected).
 */
internal fun streams(
    team: MyTeam,
    rosRows: List<ProjectionRow>,
    rosWeekly: Map<String, Map<Int, Double>>,
    rostered: Set<String>,
    weeks: List<Int>,
): List<Stream> {
    val mineIds = team.players.mapNotNull { it.playerId }.toSet()
    return weeks.flatMap { w ->
        listOf("DST", "K").mapNotNull { pos ->
            fun points(r: ProjectionRow) = rosWeekly[r.playerId]?.get(w) ?: 0.0
            val atPos = rosRows.filter { it.position == pos }
            val pick = atPos.filter { it.playerId !in rostered && it.playerId !in mineIds }.maxByOrNull(::points)?.takeIf { points(it) > 0.0 }
                ?: return@mapNotNull null
            val yours = atPos.filter { it.playerId in mineIds }.maxOfOrNull(::points)?.takeIf { it > 0.0 }
            Stream(w, pos, pick, points(pick), yours)
        }
    }
}

/** How many weeks ahead the streamers look. */
internal const val STREAM_WEEKS = 3

@Composable
internal fun PlannerView(
    team: MyTeam,
    state: ProjectionListState.Loaded,
    rostered: Set<String>?,
    onPlayer: (String) -> Unit,
) {
    val taken = rostered.orEmpty()
    val last = team.playoffWeeks.maxOrNull() ?: state.week
    val weeks = (state.week..last).toList()
    val gaps = remember(team, state, taken) { holes(team, state.rosRows, state.rosWeekly, state.byes, taken, weeks) }
    val streamers = remember(team, state, taken) { streams(team, state.rosRows, state.rosWeekly, taken, weeks.take(STREAM_WEEKS)) }
    LazyColumn(Modifier.fillMaxSize().testTag("planner")) {
        if (state.rosWeekly.isEmpty()) {
            item { Note("Weekly projections are loading (or need a stats refresh); the planner reads them.") }
            return@LazyColumn
        }
        if (rostered == null) item { Note("Sync your league to see free agents; suggestions below may be rostered.") }
        discountNote(team, state.rosRows)?.let { item { Note(it) } }
        item { SectionHeader("Lineup holes, weeks ${weeks.first()}–${weeks.last()}") }
        if (gaps.isEmpty()) {
            item { Note("Your roster fills every starting slot every week through the fantasy playoffs.") }
        } else {
            itemsIndexed(gaps, key = { _, h -> "hole:${h.week}:${h.slot}" }) { _, h ->
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = h.fill != null) { h.fill?.let { onPlayer(it.first.playerId) } }
                        .padding(horizontal = 16.dp, vertical = 6.dp).testTag("hole:${h.week}:${h.slot}"),
                ) {
                    Text("Week ${h.week}: no ${h.slot}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        h.why + (h.fill?.let { (row, p) -> " · add ${row.name} (${row.team ?: "FA"}, ${pts(p)} pts)" } ?: " · no free agent fits"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionHeader("Streamers: best free-agent D/ST and K") }
        if (streamers.isEmpty()) item { Note("No free-agent D/ST or kicker projected in the next $STREAM_WEEKS weeks.") }
        itemsIndexed(streamers, key = { _, s -> "stream:${s.week}:${s.position}" }) { _, s ->
            Column(
                Modifier.fillMaxWidth().playerClick(s.pick.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("stream:${s.week}:${s.position}"),
            ) {
                Text(
                    "Week ${s.week} ${if (s.position == "DST") "D/ST" else "K"}: ${s.pick.name} ${pts(s.points)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    s.yours?.let { y -> if (s.points > y) "+${pts(s.points - y)} over yours (${pts(y)})" else "yours is as good (${pts(y)})" } ?: "you have none projected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Which of [team]'s players have each coming week cut to the chance they're back by then, or null when none do. */
internal fun discountNote(team: MyTeam, rosRows: List<ProjectionRow>): String? {
    val ids = team.players.mapNotNull { it.playerId }.toSet()
    val hurt = rosRows.filter { it.playerId in ids && it.rosDiscount != null }.sortedBy { it.name }
    if (hurt.isEmpty()) return null
    return "Injured: each week counts the chance he's back by then (${hurt.joinToString { "${it.name}, ${it.rosDiscount}" }})."
}

private fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
