package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.ADOT
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS_SHARE
import dev.gridiron.core.statquery.StatColumn.CARRY_SHARE
import dev.gridiron.core.statquery.StatColumn.CATCH_RATE
import dev.gridiron.core.statquery.StatColumn.CPOE
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.RACR
import dev.gridiron.core.statquery.StatColumn.RUSH_EPA_PER_CARRY
import dev.gridiron.core.statquery.StatColumn.RUSH_SUCCESS_RATE
import dev.gridiron.core.statquery.StatColumn.SNAP_SHARE
import dev.gridiron.core.statquery.StatColumn.TARGET_SHARE
import dev.gridiron.core.statquery.StatColumn.TOTAL_EPA
import dev.gridiron.core.statquery.StatColumn.WOPR
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/**
 * Turns values into display strings. Runs in the repository, off the main
 * thread: formatting inside table cells is the most common source of scroll
 * jank, and a Grid holds thousands of them.
 */
public class StatFormat(private val locale: Locale = Locale.getDefault()) {

    public fun format(column: StatColumn, value: Double?, perGame: Boolean): String {
        if (value == null || !value.isFinite()) return MISSING
        return when {
            column in PERCENT -> fixed(value * 100, 1) + "%"
            column in DECIMALS -> fixed(value, DECIMALS.getValue(column))
            // Counting stats: whole numbers as totals, one decimal per game.
            else -> fixed(value, if (perGame) 1 else 0)
        }
    }

    /** Rounds first so a value like -0.04 never renders as "-0.0". */
    private fun fixed(value: Double, decimals: Int): String {
        val scale = 10.0.pow(decimals)
        val rounded = Math.round(value * scale) / scale
        val clean = if (abs(rounded) < 0.5 / scale) 0.0 else rounded
        return String.format(locale, "%.${decimals}f", clean)
    }

    public companion object {
        public const val MISSING: String = "–"

        private val PERCENT: Set<StatColumn> =
            setOf(TARGET_SHARE, AIR_YARDS_SHARE, CARRY_SHARE, SNAP_SHARE, CATCH_RATE, RUSH_SUCCESS_RATE)

        private val DECIMALS: Map<StatColumn, Int> = mapOf(
            WOPR to 2,
            ADOT to 1,
            RACR to 2,
            EPA_PER_DROPBACK to 2,
            RUSH_EPA_PER_CARRY to 2,
            // Already in percentage points.
            CPOE to 1,
            TOTAL_EPA to 1,
        )
    }
}
