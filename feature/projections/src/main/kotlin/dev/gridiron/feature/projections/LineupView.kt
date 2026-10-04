package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.model.normalCdf
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import kotlin.math.roundToInt

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
    /** The total's standard deviation: the starters' spreads ([spread]) combined as if independent. */
    val spread: Double = 0.0,
) {
    /** The total's likely range, its 10th to 90th percentile under a normal: about 4 weeks in 5 land inside. */
    val low: Double get() = maxOf(0.0, total - Z90 * spread)
    val high: Double get() = total + Z90 * spread
}

/** A normal's 90th percentile, in standard deviations. */
private const val Z90 = 1.2816

/**
 * A player's standard deviation, from his floor and ceiling: they are calibrated to hold about 80% of games, which a
 * normal spans with 2 x [Z90] standard deviations. An Out player's range is zero.
 */
internal fun spread(row: ProjectionRow): Double = maxOf(0.0, row.ceiling - row.floor) / (2 * Z90)

/**
 * The chance [mine] outscores [theirs], treating each total as normal with its [LineupView.spread] and the two as
 * independent. With no spread at all it is 1, 0 or a half.
 */
internal fun winChance(mine: LineupView, theirs: LineupView): Double {
    val sd = kotlin.math.sqrt(mine.spread * mine.spread + theirs.spread * theirs.spread)
    val margin = mine.total - theirs.total
    if (sd == 0.0) return if (margin > 0) 1.0 else if (margin < 0) 0.0 else 0.5
    return normalCdf(margin / sd)
}

/** "68% to win": rounded, and never shown as a certainty either way (1-99%). */
internal fun winLine(chance: Double): String = "${(chance * 100).roundToInt().coerceIn(1, 99)}% to win"

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
    val starters = best.spots.mapNotNull { it.player?.let { c -> rows.getValue(c.playerId) } }
    return LineupView(
        teamName = team.teamName,
        week = week,
        total = best.total,
        starters = best.spots.map { LineupLine(it.slot, it.player?.let { c -> rows.getValue(c.playerId) }) },
        bench = best.bench.map { rows.getValue(it.playerId) },
        unlisted = unlisted,
        defaultSlots = team.slotsAreDefault,
        spread = kotlin.math.sqrt(starters.sumOf { spread(it).let { sd -> sd * sd } }),
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
