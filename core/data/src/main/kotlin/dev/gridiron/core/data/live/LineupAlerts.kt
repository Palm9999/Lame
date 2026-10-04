package dev.gridiron.core.data.live

import dev.gridiron.core.data.ScoresWeek
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import java.time.Instant

/** What a lineup check knows of one rostered player this week: [points] is his projection (zero without one). */
public data class WeekPlayer(val playerId: String, val name: String, val position: String, val team: String?, val points: Double)

/**
 * A starter in the user's ESPN lineup who won't play: [reason] ("is Out", "is on bye"); [replacement] is the best bench
 * player who can take his slot and whose game hasn't started, null when there is none.
 */
public data class LineupAlert(val starter: WeekPlayer, val slot: String, val reason: String, val replacement: WeekPlayer?) {
    public val title: String get() = "Lineup: ${starter.name} ${reason}"

    public val text: String
        get() = replacement?.let { "Start ${it.name} (${String.format(java.util.Locale.US, "%.1f", it.points)} pts) at $slot instead." }
            ?: "No one on your bench can take his $slot slot."
}

public object LineupAlerts {
    private val NOT_STARTING = setOf("BE", "IR")
    private val SITS = mapOf("O" to "is Out", "IR" to "is on IR", "D" to "is Doubtful", "SUSP" to "is suspended")

    /**
     * Starters whose game kicks off at [window] and who ESPN lists Out, IR, Doubtful or suspended ([status]: ESPN's
     * abbreviation by player id), plus, in the week's first window only, starters whose team is on bye. [players] holds
     * what is known of the roster this week; a starter it doesn't know is skipped. Each gets the best bench replacement.
     */
    public fun check(team: MyTeam, players: Map<String, WeekPlayer>, status: Map<String, String>, week: ScoresWeek, window: Instant): List<LineupAlert> {
        val kickoff = week.games.flatMap { g -> listOfNotNull(g.kickoff?.let { g.home to it }, g.kickoff?.let { g.away to it }) }.toMap()
        val first = week.games.mapNotNull { it.kickoff }.minOrNull()
        val bench = team.players.filter { it.slot == "BE" }.mapNotNull { p -> p.playerId?.let(players::get) }
        val used = HashSet<String>()
        return team.players.filter { it.slot !in NOT_STARTING }.mapNotNull { p ->
            val starter = p.playerId?.let(players::get) ?: return@mapNotNull null
            val nflTeam = starter.team ?: return@mapNotNull null
            val reason = when {
                nflTeam in week.byes || (kickoff[nflTeam] == null && week.games.none { it.home == nflTeam || it.away == nflTeam }) ->
                    if (window == first) "is on bye" else return@mapNotNull null
                kickoff[nflTeam] == window -> SITS[status[starter.playerId]] ?: return@mapNotNull null
                else -> return@mapNotNull null
            }
            val replacement = bench
                .filter { b -> b.playerId !in used && Lineups.fits(p.slot, b.position) && status[b.playerId] !in SITS.keys }
                .filter { b -> b.team != null && kickoff[b.team]?.let { !it.isBefore(window) } == true }
                .maxWithOrNull(compareBy<WeekPlayer> { it.points }.thenByDescending { it.name })
            replacement?.let { used += it.playerId }
            LineupAlert(starter, p.slot, reason, replacement)
        }
    }
}

/** A finished week's lineup against the best one ESPN's own points allowed: [left] is what the bench outscored it by. */
public data class WeekReview(
    val week: Int,
    val scored: Double,
    val best: Double,
    /** Benched players the best lineup starts, with their points, best first. */
    val shouldHaveStarted: List<MatchupPlayer>,
    /** Starters it benches, lowest first. */
    val shouldHaveSat: List<MatchupPlayer>,
) {
    val left: Double get() = (best - scored).coerceAtLeast(0.0)
}

public object LineupReview {
    private val NOT_STARTING = setOf("BE", "IR")

    /**
     * [side]'s week against its best lineup on [slots] from the points ESPN gave each player ([MatchupPlayer.espnPoints];
     * none counts as zero). IR can't start. A player whose position [positionOf] doesn't know stays where he was: a
     * starter keeps his slot (and his points), a bench player stays on the bench.
     */
    public fun of(week: Int, side: MatchupSide, slots: Map<String, Int>, positionOf: (MatchupPlayer) -> String?): WeekReview {
        val starters = side.lineup.filter { it.slot !in NOT_STARTING }
        val scored = starters.sumOf { it.espnPoints ?: 0.0 }
        val known = side.lineup.filter { it.slot != "IR" }.mapNotNull { p -> positionOf(p)?.let { p to it } }
        val fixed = starters.filter { s -> known.none { it.first == s } }
        val open = slots.toMutableMap().apply { for (s in fixed) computeIfPresent(s.slot) { _, n -> (n - 1).takeIf { it > 0 } } }
        val best = Lineups.best(open, known.map { (p, pos) -> LineupCandidate(p.espnId, pos, p.espnPoints ?: 0.0) })
        val bestIds = best.spots.mapNotNull { it.player?.playerId }.toSet() + fixed.map { it.espnId }
        val startedIds = starters.map { it.espnId }.toSet()
        val start = side.lineup.filter { it.espnId in bestIds && it.espnId !in startedIds }.sortedByDescending { it.espnPoints ?: 0.0 }
        return WeekReview(
            week = week,
            scored = scored,
            best = maxOf(best.total + fixed.sumOf { it.espnPoints ?: 0.0 }, scored),
            shouldHaveStarted = start,
            shouldHaveSat = if (start.isEmpty()) emptyList() else starters.filter { it.espnId !in bestIds }.sortedBy { it.espnPoints ?: 0.0 },
        )
    }
}
