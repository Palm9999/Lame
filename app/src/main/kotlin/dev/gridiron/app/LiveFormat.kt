package dev.gridiron.app

import dev.gridiron.core.data.InjuryRow
import dev.gridiron.core.data.live.LiveInjury
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Sep 25, 7:01 PM", in the phone's time zone. */
fun formatWhen(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).withZone(zone).format(instant)

/** One player on the live report, with their latest official practice participation if nflverse has one. */
data class InjuryLine(val injury: LiveInjury, val practice: String?)

data class InjuryGroup(val team: String, val lines: List<InjuryLine>)

private const val NO_TEAM = "—"

/**
 * ESPN's live list by team (alphabetical, players without a team last). Each
 * player keeps ESPN's order within the team and gains their latest official
 * practice status from nflverse, e.g. "Limited · Wk 3".
 */
fun injuryReport(live: List<LiveInjury>, official: List<InjuryRow>): List<InjuryGroup> {
    val practice = official.filter { it.practice != null }.groupBy { it.playerId }.mapValues { (_, rows) -> rows.maxBy { it.week } }
    return live.groupBy { it.team ?: NO_TEAM }.toSortedMap().map { (team, rows) ->
        InjuryGroup(
            team,
            rows.map { i -> InjuryLine(i, i.playerId?.let(practice::get)?.let { "${it.practice} · Wk ${it.week}" }) },
        )
    }
}
