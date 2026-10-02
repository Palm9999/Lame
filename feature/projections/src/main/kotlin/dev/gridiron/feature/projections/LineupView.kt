package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups

/** A starting slot of the lineup; [row] is null when no one on the roster can fill it. */
internal data class LineupLine(val slot: String, val row: ProjectionRow?)

/** A rostered player left out of the lineup: [matched] is false when the app doesn't know him at all. */
internal data class Unlisted(val name: String, val matched: Boolean)

/** The best lineup the roster can field this week, as the "My lineup" mode shows it. */
internal data class LineupView(
    val teamName: String,
    val week: Int,
    val total: Double,
    val starters: List<LineupLine>,
    val bench: List<ProjectionRow>,
    val unlisted: List<Unlisted>,
    /** True when the league's own slots weren't known and the usual nine were used. */
    val defaultSlots: Boolean,
)

/**
 * [team]'s best lineup for [week] from [weekRows] (the week's projections under the active profile). An ESPN Out or IR
 * player scores zero, as in the list; a rostered player with no projection (a bye, a rookie) or no match is listed
 * apart, because his position is unknown.
 */
internal fun lineupView(team: MyTeam, week: Int, weekRows: List<ProjectionRow>, badges: Map<String, String>): LineupView {
    val byId = weekRows.associateBy { it.playerId }
    val rows = mutableMapOf<String, ProjectionRow>()
    val unlisted = mutableListOf<Unlisted>()
    for (p in team.players) {
        val row = p.playerId?.let(byId::get)
        if (row == null) unlisted += Unlisted(p.name, matched = p.playerId != null) else rows[row.playerId] = outAdjusted(row, badges)
    }
    val best = Lineups.best(team.slots, rows.values.map { LineupCandidate(it.playerId, it.position, it.points) })
    return LineupView(
        teamName = team.teamName,
        week = week,
        total = best.total,
        starters = best.spots.map { LineupLine(it.slot, it.player?.let { c -> rows.getValue(c.playerId) }) },
        bench = best.bench.map { rows.getValue(it.playerId) },
        unlisted = unlisted,
        defaultSlots = team.slotsAreDefault,
    )
}
