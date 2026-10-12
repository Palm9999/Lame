package dev.gridiron.feature.projections

import dev.gridiron.core.data.live.MyTeam
import java.util.Locale

/**
 * What to do if a starter (by his ESPN slot) with a designation misses the week: [bench], who moves into the best
 * lineup in his place (null when no one on the roster can), and [pickup], the free agent who'd lift that lineup most.
 */
internal data class ReplacementPlan(val playerId: String, val bench: ProjectionRow?, val pickup: PickupLine?)

private val MAY_MISS = setOf("O", "D", "Q", "IR", "SUSP")
private val NOT_STARTING = setOf("BE", "IR")

/**
 * A plan for each of [team]'s starters ESPN lists Out, Doubtful, Questionable, IR or suspended: the best lineup with
 * him healthy against the one with him out names who replaces him, and [waiverPickups] on the lineup without him the
 * best add ([rostered] null leaves adds out).
 */
internal fun replacementPlans(
    team: MyTeam,
    week: Int,
    weekRows: List<ProjectionRow>,
    badges: Map<String, String>,
    rostered: Set<String>?,
    started: Set<String> = emptySet(),
): List<ReplacementPlan> = team.players.filter { it.slot !in NOT_STARTING && badges[it.playerId] in MAY_MISS }.mapNotNull { p ->
    val id = p.playerId ?: return@mapNotNull null
    val healthy = lineupView(team, week, weekRows, badges - id, started).starters.mapNotNull { it.row?.playerId }.toSet()
    val out = badges + (id to "O")
    val bench = lineupView(team, week, weekRows, out, started).starters.mapNotNull { it.row }.filter { it.playerId !in healthy }.maxByOrNull { it.points }
    ReplacementPlan(id, bench, rostered?.let { waiverPickups(team, weekRows, out, it, started = started).firstOrNull() })
}

/** "If he sits: start Bench Back (8.2) · or add Free Back (+2.4)", or that no one on the roster fits. */
internal fun planText(plan: ReplacementPlan): String =
    "If he sits: " + listOfNotNull(
        plan.bench?.let { "start ${it.name} (${one(it.points)})" } ?: "no one on your bench fits",
        plan.pickup?.let { "${if (plan.bench != null) "or " else ""}add ${it.add.name} (+${one(it.gain)})" },
    ).joinToString(" · ")

/** A free kicker or D/ST beating the user's this week by [gain]; [next] is his next week, null when not projected. */
internal data class StreamLine(val row: ProjectionRow, val next: Double?, val gain: Double)

/**
 * The best free agents at K and D/ST this week ([perPosition] each), when the league starts one and he beats the
 * user's best there; a team whose game has [started] is left out. [rosWeekly] gives the week after.
 */
internal fun streams(
    team: MyTeam,
    week: Int,
    weekRows: List<ProjectionRow>,
    rosWeekly: Map<String, Map<Int, Double>>,
    badges: Map<String, String>,
    rostered: Set<String>,
    started: Set<String> = emptySet(),
    perPosition: Int = 2,
): List<StreamLine> {
    val mine = team.players.mapNotNull { it.playerId }.toSet()
    return listOf("K" to "K", "DST" to "D/ST").filter { (_, slot) -> (team.slots[slot] ?: 0) > 0 }.flatMap { (position, _) ->
        val rows = weekRows.filter { it.position == position }.map { outAdjusted(it, badges) }
        val best = rows.filter { it.playerId in mine }.maxOfOrNull { it.points } ?: 0.0
        rows.filter { it.playerId !in rostered && it.playerId !in mine && it.team !in started && it.points > best }
            .sortedByDescending { it.points }
            .take(perPosition)
            .map { StreamLine(it, rosWeekly[it.playerId]?.get(week + 1), it.points - best) }
    }
}

/** "9.1 pts (+2.9 on yours) · next week 8.4". */
internal fun streamText(s: StreamLine): String =
    "${one(s.row.points)} pts (+${one(s.gain)} on yours)" + (s.next?.let { " · next week ${one(it)}" } ?: "")

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
