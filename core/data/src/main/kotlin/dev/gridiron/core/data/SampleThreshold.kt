package dev.gridiron.core.data

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlin.math.ceil

/**
 * Who qualifies to be ranked, which the spec requires for rate leaderboards:
 * sorted by catch rate with no floor, the top of the list is a lineman who
 * caught his only target. It also gives percentiles a meaningful population,
 * so a starter isn't flattered by comparison with backups.
 *
 * The qualifying stat is the sort column's sample (targets behind target
 * share, carries behind success rate), else the pack's population. The bar
 * scales with weeks actually played, so an in-progress season isn't held to a
 * full-season total.
 */
public data class SampleThreshold(
    val qualifier: Filter,
    val minGames: Int,
    /** Shown to the user, so the filter is never invisible: "min 3 targets per game, 4+ games". */
    val description: String,
) {
    public companion object {
        private val PER_WEEK: Map<StatColumn, Int> = mapOf(
            StatColumn.TARGETS to 3,
            StatColumn.CARRIES to 6,
            StatColumn.DROPBACKS to 14,
            StatColumn.ATTEMPTS to 12,
            StatColumn.OFFENSE_SNAPS to 15,
        )

        private val NOUN: Map<StatColumn, String> = mapOf(
            StatColumn.TARGETS to "targets",
            StatColumn.CARRIES to "carries",
            StatColumn.DROPBACKS to "dropbacks",
            StatColumn.ATTEMPTS to "attempts",
            StatColumn.OFFENSE_SNAPS to "snaps",
        )

        public fun forRequest(sort: StatColumn, pack: StatPack, playedWeeks: Int, perGame: Boolean): SampleThreshold? {
            val sample = sort.sample ?: pack.population ?: return null
            val perWeek = PER_WEEK[sample] ?: return null
            val noun = NOUN.getValue(sample)
            val weeks = playedWeeks.coerceAtLeast(1)
            return if (perGame) {
                // Filters apply to displayed values, which here are per game;
                // also require a reasonable share of the weeks to have been played.
                val games = ceil(weeks / 2.0).toInt().coerceAtLeast(1)
                SampleThreshold(
                    Filter(sample, Condition.AtLeast(perWeek.toDouble())),
                    games,
                    "min $perWeek $noun per game" + if (games > 1) ", $games+ games" else "",
                )
            } else {
                val total = perWeek * weeks
                SampleThreshold(Filter(sample, Condition.AtLeast(total.toDouble())), 1, "min $total $noun")
            }
        }
    }
}
