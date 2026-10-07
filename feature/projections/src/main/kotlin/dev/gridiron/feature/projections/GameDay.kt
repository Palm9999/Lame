package dev.gridiron.feature.projections

import dev.gridiron.core.projections.Lineups
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One kickoff time of the week and the user's players in it: [starters] by slot, then the [bench]. */
internal data class GameWindow(val kickoff: Instant, val starters: List<LineupLine>, val bench: List<ProjectionRow>)

/**
 * A Questionable starter at [slot] who kicks off at [kickoff], and the bench players who fit his slot and play at the
 * same time or later ([options], best first): still free to go in once his inactives are posted. Empty when every one
 * has kicked off by then.
 */
internal data class Pivot(val player: ProjectionRow, val slot: String, val kickoff: Instant, val options: List<PivotOption>)

/** A bench player who could replace a Questionable starter, and his own [kickoff]. */
internal data class PivotOption(val row: ProjectionRow, val kickoff: Instant)

/** The week's kickoffs for the user's lineup: each window still to come, and a pivot for each Questionable starter. */
internal data class GameDay(val windows: List<GameWindow>, val pivots: List<Pivot>)

/**
 * [view]'s players by kickoff ([kickoffs]: NFL team to ESPN's kickoff time), windows already under way left out, and
 * for each Questionable starter (ESPN's Q in [badges]) whose inactives aren't posted yet ([posted]: once they are, he
 * is known to play or not), who could replace him late. A
 * player whose team has no kickoff (a bye, no scoreboard) is left out. Null with no kickoff still to come.
 */
internal fun gameDay(
    view: LineupView,
    kickoffs: Map<String, Instant>,
    badges: Map<String, String>,
    now: Instant,
    posted: Set<String> = emptySet(),
): GameDay? {
    fun kickoff(row: ProjectionRow) = row.team?.let(kickoffs::get)?.takeIf { it.isAfter(now) }
    val starters = view.starters.filter { !it.locked && it.row != null && kickoff(it.row) != null }
    val bench = view.bench.filter { kickoff(it) != null }
    val times = (starters.map { kickoff(it.row!!)!! } + bench.map { kickoff(it)!! }).distinct().sorted()
    if (times.isEmpty()) return null
    val windows = times.map { t -> GameWindow(t, starters.filter { kickoff(it.row!!) == t }, bench.filter { kickoff(it) == t }) }
    val pivots = starters.filter { badges[it.row!!.playerId] == "Q" && it.row.team !in posted }.map { line ->
        val row = line.row!!
        val at = kickoff(row)!!
        val options = bench.filter { b ->
            !b.out && badges[b.playerId] !in UNAVAILABLE && Lineups.fits(line.slot, b.position) && !kickoff(b)!!.isBefore(at)
        }.sortedByDescending { it.points }.take(MAX_OPTIONS).map { PivotOption(it, kickoff(it)!!) }
        Pivot(row, line.slot, at, options)
    }
    return GameDay(windows, pivots)
}

private val UNAVAILABLE = setOf("O", "IR", "D", "SUSP")

private const val MAX_OPTIONS = 2

private val KICKOFF = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

/** "Sun 1:00 PM" in [zone]. */
internal fun kickoffText(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String = KICKOFF.format(t.atZone(zone))

/** "Start Name (QB), Name (FLEX) · bench Name": who plays in a window. */
internal fun windowText(w: GameWindow): String = listOfNotNull(
    w.starters.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Start ") { "${it.row!!.name} (${it.slot})" },
    w.bench.takeIf { it.isNotEmpty() }?.joinToString(prefix = "bench ") { it.name },
).joinToString(" · ")

/** What to do about a Questionable starter: who can still go in for him, or that no one can. */
internal fun pivotText(p: Pivot, zone: ZoneId = ZoneId.systemDefault()): String {
    val head = "${p.player.name} (${p.slot}) is Questionable, kicking off ${kickoffText(p.kickoff, zone)}."
    return if (p.options.isEmpty()) {
        "$head No bench player who fits his slot plays then or later: decide before his game, or keep a later one ready."
    } else {
        "$head If he's out, " + p.options.joinToString(" or ") { "${it.row.name} (${kickoffText(it.kickoff, zone)}, ${String.format(Locale.US, "%.1f", it.row.points)})" } +
            " can still go in."
    }
}

