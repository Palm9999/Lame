package dev.gridiron.core.data

import dev.gridiron.core.model.WeekRange

/**
 * One row's trend: the sorted stat for each week of [weeks], null where the
 * player didn't play. [labels] are the same values formatted for a screen reader.
 */
public data class Sparkline(val weeks: IntRange, val values: List<Double?>, val labels: List<String>) {
    public val drawable: Boolean get() = values.count { it != null } >= 2
}

/**
 * The last six played weeks inside [weeks], or null when fewer than two have
 * been played, since one point is not a trend.
 */
public fun sparklineWeeks(season: SeasonInfo, weeks: WeekRange): IntRange? {
    val last = minOf(weeks.last, season.lastWeek)
    val first = maxOf(weeks.first, last - 5)
    return if (last - first >= 1) first..last else null
}
