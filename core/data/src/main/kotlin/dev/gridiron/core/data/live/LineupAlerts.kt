package dev.gridiron.core.data.live

import dev.gridiron.core.data.ScoresWeek
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
