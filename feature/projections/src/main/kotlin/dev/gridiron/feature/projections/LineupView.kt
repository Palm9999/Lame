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

/** The opponent for "My lineup": not asked for yet, being fetched, found (a roster on the user's slots), or why not. */
public sealed interface OpponentState {
    public data object Idle : OpponentState

    public data object Loading : OpponentState

    public data class Loaded(val team: MyTeam) : OpponentState

    public data class Unavailable(val message: String) : OpponentState
}

/** "You lead by 6.8", "You trail by 2.1" or "Even", from the two lineups' totals. */
internal fun matchupLine(mine: Double, theirs: Double): String {
    val margin = mine - theirs
    return when {
        kotlin.math.abs(margin) < 0.05 -> "Even"
        margin > 0 -> "You lead by ${"%.1f".format(java.util.Locale.US, margin)}"
        else -> "You trail by ${"%.1f".format(java.util.Locale.US, -margin)}"
    }
}

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

/** A free agent who would raise [team]'s lineup: see [dev.gridiron.core.projections.Pickup]. */
internal data class PickupLine(
    val add: ProjectionRow,
    val gain: Double,
    val slot: String,
    val replaces: ProjectionRow?,
    val drop: ProjectionRow?,
    /** Why he is moving up, when a starter ahead of him is hurt: "RB1 Name is Doubtful". */
    val starterOut: String? = null,
)

/**
 * The best waiver pickups for [team] this week: projected players on no league team ([rostered] is everyone on one),
 * each rated by how far he lifts the best lineup. An ESPN Out or IR player scores zero, so is never suggested.
 */
internal fun waiverPickups(
    team: MyTeam,
    weekRows: List<ProjectionRow>,
    badges: Map<String, String>,
    rostered: Set<String>,
    starterOut: Map<String, String> = emptyMap(),
): List<PickupLine> {
    val byId = weekRows.associateBy { it.playerId }
    val own = team.players.mapNotNull { p -> p.playerId?.let(byId::get) }.map { outAdjusted(it, badges) }
    val free = weekRows.filter { it.playerId !in rostered }.map { outAdjusted(it, badges) }.filter { it.points > 0 }
    val rows = (own + free).associateBy { it.playerId }
    fun candidate(row: ProjectionRow) = LineupCandidate(row.playerId, row.position, row.points)
    return Lineups.pickups(team.slots, own.map(::candidate), free.map(::candidate)).map {
        PickupLine(
            rows.getValue(it.add.playerId), it.gain, it.slot,
            it.replaces?.let { c -> rows.getValue(c.playerId) }, it.drop?.let { c -> rows.getValue(c.playerId) },
            starterOut[it.add.playerId],
        )
    }
}
