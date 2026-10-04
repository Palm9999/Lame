package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.model.normalCdf
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import dev.gridiron.core.projections.Trades
import kotlin.math.roundToInt

/** A starting slot of the lineup; [row] is null when no one on the roster can fill it. [locked]: his game has started. */
internal data class LineupLine(val slot: String, val row: ProjectionRow?, val locked: Boolean = false)

/** Starting slots in the order a lineup reads; a label not listed sorts last. */
private val SLOT_ORDER = listOf("QB", "RB", "WR", "TE", "RB/WR", "WR/TE", "RB/WR/TE", "FLEX", "OP", "D/ST", "K")

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
internal fun lineupView(
    team: MyTeam,
    week: Int,
    weekRows: List<ProjectionRow>,
    badges: Map<String, String>,
    /** NFL teams whose game this week has kicked off: their players stay where ESPN has them. */
    started: Set<String> = emptySet(),
): LineupView {
    val byId = weekRows.associateBy { it.playerId }
    val rows = mutableMapOf<String, ProjectionRow>()
    val unlisted = mutableListOf<Unlisted>()
    val lockedLines = mutableListOf<LineupLine>()
    val lockedBench = mutableListOf<ProjectionRow>()
    val open = team.slots.toMutableMap()
    for (p in team.players) {
        val row = p.playerId?.let(byId::get)?.let { outAdjusted(it, badges) }
        when {
            row == null -> unlisted += Unlisted(p.name, matched = p.playerId != null)
            row.team != null && row.team in started -> {
                // His game has started: a starter keeps his slot, anyone else stays on the bench.
                val left = open[p.slot] ?: 0
                if (left > 0) {
                    open[p.slot] = left - 1
                    lockedLines += LineupLine(p.slot, row, locked = true)
                } else {
                    lockedBench += row
                }
            }
            else -> rows[row.playerId] = row
        }
    }
    val best = Lineups.best(open, rows.values.map { LineupCandidate(it.playerId, it.position, it.points) })
    val lines = (lockedLines + best.spots.map { LineupLine(it.slot, it.player?.let { c -> rows.getValue(c.playerId) }) })
        .sortedBy { SLOT_ORDER.indexOf(it.slot).let { i -> if (i < 0) SLOT_ORDER.size else i } }
    val starters = lines.mapNotNull { it.row }
    return LineupView(
        teamName = team.teamName,
        week = week,
        total = starters.sumOf { it.points },
        starters = lines,
        bench = best.bench.map { rows.getValue(it.playerId) } + lockedBench,
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
    /** A suggested FAAB bid ([faabBid]); null when the league doesn't bid or what is left isn't known. */
    val bid: Int? = null,
    /** His points in the league's playoff weeks; null without weekly projections. */
    val playoffPoints: Double? = null,
)

/**
 * A FAAB bid for a pickup that lifts the roster [gain] rest-of-season points, with [left] dollars left: [left] times
 * the gain over [FAAB_FULL_GAIN], at most [FAAB_MAX_SHARE] of it and at least a dollar. A judgment, not a fit: there
 * are no historical bids to fit it on.
 */
internal fun faabBid(gain: Double, left: Int): Int {
    if (left <= 0 || gain <= 0.0) return 0
    val share = minOf(FAAB_MAX_SHARE, gain / FAAB_FULL_GAIN)
    return (left * share).roundToInt().coerceIn(1, left)
}

/** What the rest-of-season adds say about FAAB; null when the league doesn't bid. */
internal fun faabText(team: MyTeam): String? {
    val budget = team.faabBudget ?: return null
    return team.faabLeft?.let { "$$it of $$budget FAAB left. Bids scale with each add's rest-of-season gain, at most half of what's left." }
        ?: "This league bids FAAB ($$budget), but ESPN didn't say what you've spent, so there are no bids."
}

/** A rest-of-season gain of about 4.5 points a week over 13 weeks: a league-changing add. */
internal const val FAAB_FULL_GAIN: Double = 60.0

/** Never more than half of what is left on one player. */
internal const val FAAB_MAX_SHARE: Double = 0.5

/**
 * The rest-of-season adds for [team] ([waiverPickups] on [rosRows], valued week by week when [weekly] has rows), each
 * with a FAAB bid when the league bids and his points in the league's playoff weeks.
 */
internal fun rosAdds(
    team: MyTeam,
    rosRows: List<ProjectionRow>,
    rostered: Set<String>,
    weekly: Map<String, Map<Int, Double>>,
): List<PickupLine> = waiverPickups(team, rosRows, emptyMap(), rostered, weekly = weekly).map { pick ->
    pick.copy(
        bid = team.faabLeft?.let { faabBid(pick.gain, it) },
        playoffPoints = weekly[pick.add.playerId]?.let { w -> team.playoffWeeks.sumOf { w[it] ?: 0.0 } },
    )
}

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
    /** NFL teams whose game has kicked off: their free agents can't play for you this week. */
    started: Set<String> = emptySet(),
    /** Rest of season week by week: when given, each add is valued by [Trades.value] with his drop cut, so byes count. */
    weekly: Map<String, Map<Int, Double>>? = null,
): List<PickupLine> {
    val byId = weekRows.associateBy { it.playerId }
    val own = team.players.mapNotNull { p -> p.playerId?.let(byId::get) }.map { outAdjusted(it, badges) }
    val free = weekRows.filter { it.playerId !in rostered && it.team !in started }.map { outAdjusted(it, badges) }.filter { it.points > 0 }
    val rows = (own + free).associateBy { it.playerId }
    fun candidate(row: ProjectionRow) = LineupCandidate(row.playerId, row.position, row.points, weekly?.get(row.playerId).orEmpty())
    val value = weekly?.takeIf { it.isNotEmpty() }?.let { { roster: List<LineupCandidate> -> Trades.value(team.slots, roster) } }
    return Lineups.pickups(team.slots, own.map(::candidate), free.map(::candidate), value = value).map {
        PickupLine(
            rows.getValue(it.add.playerId), it.gain, it.slot,
            it.replaces?.let { c -> rows.getValue(c.playerId) }, it.drop?.let { c -> rows.getValue(c.playerId) },
            starterOut[it.add.playerId],
        )
    }
}

/** One player in the lineup check: [points] is this week's projection (zero when Out), null with none (a bye, unmatched). */
internal data class CheckPlayer(val playerId: String?, val name: String, val slot: String, val points: Double?)

/**
 * The lineup set in ESPN (as of the last sync) against [best]: its projected [current] total, who to [start] from the
 * bench and who to [sit]. Null when no rostered player sits in a starting slot (nothing set, or an old snapshot).
 */
internal data class LineupCheck(val current: Double, val best: Double, val start: List<CheckPlayer>, val sit: List<CheckPlayer>) {
    val gain: Double get() = best - current
}

private val NOT_STARTING = setOf("BE", "IR")

internal fun lineupCheck(team: MyTeam, best: LineupView, weekRows: List<ProjectionRow>, badges: Map<String, String>): LineupCheck? {
    val byId = weekRows.associateBy { it.playerId }
    fun check(p: dev.gridiron.core.data.live.LeaguePlayer): CheckPlayer {
        val row = p.playerId?.let(byId::get)?.let { outAdjusted(it, badges) }
        return CheckPlayer(p.playerId, row?.name ?: p.name, p.slot, row?.points)
    }
    val set = team.players.filter { it.slot !in NOT_STARTING }.map(::check)
    if (set.isEmpty()) return null
    val bestIds = best.starters.mapNotNull { it.row?.playerId }.toSet()
    val setIds = set.mapNotNull { it.playerId }.toSet()
    return LineupCheck(
        current = set.sumOf { it.points ?: 0.0 },
        best = best.total,
        start = team.players.filter { it.playerId in bestIds && it.playerId !in setIds }.map(::check).sortedByDescending { it.points ?: 0.0 },
        sit = set.filter { it.playerId == null || it.playerId !in bestIds }.sortedBy { it.points ?: -1.0 },
    )
}

/**
 * A starting RB or WR's handcuff: [backup], his teammate at the same position with the most projected carries plus
 * targets, who would score about [ifOut] this week if [starter] sat; [owner] is the league team that has him, "you",
 * or null for a free agent.
 */
internal data class HandcuffLine(val starter: ProjectionRow, val backup: ProjectionRow, val ifOut: Double, val owner: String?)

/**
 * Handcuffs for [team]'s RBs, then WRs, projected [HANDCUFF_MIN_POINTS]+ this week, best first, at most [limit] per
 * position. The backup is the teammate at his position with the most projected carries plus targets. If the starter
 * sits, his carries and targets go to the rest of the team in proportion to their own (the forecast's rule for an Out
 * player): carries across every player's carries, QBs included, targets across every player's targets. So the
 * backup's rushing points grow by the team's carries over its carries without the starter, his receiving points
 * likewise by targets. Backtested on 2022-2025's starters projected 8+ who sat a week after a healthy one, the backup
 * playing: RBs (134) off by +0.4 (±1.4) points on average, WRs (214) by -0.8 (±1.2); moving only the position room's
 * touches over-projected them by 5.0 and 3.7. TEs aren't listed: this rule under-projected a backup TE by 1.9 (±1.6).
 */
internal fun handcuffs(team: MyTeam, weekRows: List<ProjectionRow>, owners: Map<String, String>, limit: Int = 3): List<HandcuffLine> {
    val mine = team.players.mapNotNull { it.playerId }.toSet()
    val byTeam = weekRows.filter { it.team != null && it.usage != null }.groupBy { it.team!! }
    return HANDCUFF_POSITIONS.flatMap { position ->
        weekRows.filter { it.playerId in mine && it.position == position && it.points >= HANDCUFF_MIN_POINTS }
            .sortedByDescending { it.points }
            .take(limit)
            .mapNotNull { starter ->
                val onTeam = byTeam[starter.team].orEmpty()
                val backup = onTeam.filter { it.position == position && it.playerId != starter.playerId }
                    .maxByOrNull { it.usage!!.carries + it.usage.targets } ?: return@mapNotNull null
                val gone = starter.usage ?: return@mapNotNull null
                fun grows(total: Double, his: Double) = if (total - his > 0.0) total / (total - his) else 1.0
                val carries = grows(onTeam.sumOf { it.usage!!.carries }, gone.carries)
                val targets = grows(onTeam.sumOf { it.usage!!.targets }, gone.targets)
                val u = backup.usage!!
                val ifOut = backup.points + u.rushingPoints * (carries - 1) + u.receivingPoints * (targets - 1)
                HandcuffLine(starter, backup, ifOut, if (backup.playerId in mine) "you" else owners[backup.playerId])
            }
    }
}

/** RBs and WRs; the rule under-projects a backup TE (see [handcuffs]). */
private val HANDCUFF_POSITIONS = listOf("RB", "WR")

/** A starter worth insuring: about an RB2's or WR3's week. */
internal const val HANDCUFF_MIN_POINTS: Double = 8.0

/** One slot of a matchup preview: your starter and theirs there, by the lineups' slot order; [edge] is yours minus theirs. */
internal data class SlotEdge(val slot: String, val mine: ProjectionRow?, val theirs: ProjectionRow?) {
    val edge: Double get() = (mine?.points ?: 0.0) - (theirs?.points ?: 0.0)
}

/** The matchup slot by slot ([SlotEdge]) and the starters most likely to swing it: the widest floor-to-ceiling ranges. */
internal data class MatchupPreview(val slots: List<SlotEdge>, val swing: List<ProjectionRow>)

/**
 * Pairs [mine] and [theirs] slot by slot: the nth starter at a slot label against their nth there (both lineups read
 * in the same slot order), and picks the [swingCount] starters on either side with the widest range.
 */
internal fun matchupPreview(mine: LineupView, theirs: LineupView, swingCount: Int = 3): MatchupPreview {
    val theirsBySlot = theirs.starters.groupBy { it.slot }.mapValues { (_, ls) -> ls.toMutableList() }
    val pairs = mine.starters.map { line -> SlotEdge(line.slot, line.row, theirsBySlot[line.slot]?.removeFirstOrNull()?.row) } +
        theirsBySlot.values.flatten().map { SlotEdge(it.slot, null, it.row) }
    val swing = (mine.starters + theirs.starters).mapNotNull { it.row }.filter { !it.out }
        .sortedByDescending { it.ceiling - it.floor }.take(swingCount)
    return MatchupPreview(pairs, swing)
}
