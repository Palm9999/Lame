package dev.gridiron.core.data.live

import dev.gridiron.core.model.normalCdf
import dev.gridiron.core.projections.LineupCandidate
import dev.gridiron.core.projections.Lineups
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Last week's matchup by ESPN's scores. */
public data class LastResult(val week: Int, val mine: Double, val theirs: Double, val opponent: String)

/** The coming week's opponent and the chance the user's best lineup outscores theirs. */
public data class NextMatch(val week: Int, val opponent: String, val winChance: Double)

/** The Tuesday notification: last week's result, the report card's overall place and the coming week's win chance. */
public object WeeklySummary {
    private const val FROM_HOUR = 9

    /** True on a Tuesday from 9 local when the summary for [key] (season and week) hasn't gone out. */
    public fun due(now: ZonedDateTime, lastSent: String?, key: String): Boolean =
        now.dayOfWeek == DayOfWeek.TUESDAY && now.hour >= FROM_HOUR && lastSent != key

    /** The pieces that are known, joined; null when none is. */
    public fun text(last: LastResult?, place: Pair<Int, Int>?, next: NextMatch?): String? = listOfNotNull(
        last?.let {
            val word = when {
                it.mine > it.theirs -> "won"
                it.mine < it.theirs -> "lost"
                else -> "tied"
            }
            "Week ${it.week}: $word ${one(it.mine)}–${one(it.theirs)} vs ${it.opponent}"
        },
        place?.let { (p, of) -> "report card ${ordinal(p)} of $of" },
        next?.let { "Week ${it.week} vs ${it.opponent}: ${(it.winChance * 100).roundToInt().coerceIn(1, 99)}% to win" },
    ).joinToString(" · ").ifEmpty { null }

    /** The best lineup's projected total on [slots] and its spread, the starters' [sd]s combined as if independent. */
    public fun strength(slots: Map<String, Int>, roster: List<LineupCandidate>, sd: Map<String, Double>): Pair<Double, Double> {
        val starters = Lineups.best(slots, roster).spots.mapNotNull { it.player }
        return starters.sumOf { it.points } to sqrt(starters.sumOf { (sd[it.playerId] ?: 0.0).let { s -> s * s } })
    }

    /** The chance [mine] (total, spread) outscores [theirs], both normal and independent, as My lineup reads it. */
    public fun winChance(mine: Pair<Double, Double>, theirs: Pair<Double, Double>): Double {
        val spread = sqrt(mine.second * mine.second + theirs.second * theirs.second)
        val margin = mine.first - theirs.first
        if (spread == 0.0) return if (margin > 0) 1.0 else if (margin < 0) 0.0 else 0.5
        return normalCdf(margin / spread)
    }

    private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
