package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.ADOT
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS_SHARE
import dev.gridiron.core.statquery.StatColumn.CARRY_SHARE
import dev.gridiron.core.statquery.StatColumn.CATCH_RATE
import dev.gridiron.core.statquery.StatColumn.CPOE
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FPOE
import dev.gridiron.core.statquery.StatColumn.FTN_BLITZ_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CATCHABLE_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CONTESTED_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_DROP_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_INT_WORTHY_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_OUT_OF_POCKET_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_PLAY_ACTION_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_THROWAWAY_RATE
import dev.gridiron.core.statquery.StatColumn.NGS_AGGRESSIVENESS
import dev.gridiron.core.statquery.StatColumn.NGS_CUSHION
import dev.gridiron.core.statquery.StatColumn.NGS_INTENDED_AIR_YARDS
import dev.gridiron.core.statquery.StatColumn.NGS_RUSH_EFFICIENCY
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE_PER_ATT
import dev.gridiron.core.statquery.StatColumn.NGS_SEPARATION
import dev.gridiron.core.statquery.StatColumn.NGS_STACKED_BOX_PCT
import dev.gridiron.core.statquery.StatColumn.NGS_TIME_TO_THROW
import dev.gridiron.core.statquery.StatColumn.NGS_YAC_OVER_EXPECTED
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

        /** Shares and rates shown as percentages, which filters take as percentages too. */
        public fun isPercent(column: StatColumn): Boolean = column in PERCENT

        private val PERCENT: Set<StatColumn> =
            setOf(
                TARGET_SHARE, AIR_YARDS_SHARE, CARRY_SHARE, SNAP_SHARE, CATCH_RATE, RUSH_SUCCESS_RATE,
                FTN_CATCHABLE_RATE, FTN_DROP_RATE, FTN_CONTESTED_RATE, FTN_PLAY_ACTION_RATE, FTN_BLITZ_RATE,
                FTN_OUT_OF_POCKET_RATE, FTN_THROWAWAY_RATE, FTN_INT_WORTHY_RATE,
                StatColumn.FTN_SCREEN_TARGET_RATE, StatColumn.FTN_MOTION_TARGET_RATE, StatColumn.FTN_SCREEN_RATE,
                StatColumn.FTN_RPO_RATE, StatColumn.FTN_NO_HUDDLE_RATE, StatColumn.FTN_MOTION_RATE,
                StatColumn.FTN_SHOTGUN_RATE, StatColumn.FTN_FIRST_READ_RATE,
            )

        private val DECIMALS: Map<StatColumn, Int> = mapOf(
            WOPR to 2,
            ADOT to 1,
            RACR to 2,
            EPA_PER_DROPBACK to 2,
            RUSH_EPA_PER_CARRY to 2,
            // Already in percentage points.
            CPOE to 1,
            TOTAL_EPA to 1,
            // NGS percentages are already in points, so they stay out of PERCENT.
            NGS_TIME_TO_THROW to 2,
            StatColumn.FTN_AVG_BOX to 2,
            StatColumn.FTN_AVG_RUSHERS to 2,
            NGS_AGGRESSIVENESS to 1,
            NGS_INTENDED_AIR_YARDS to 1,
            NGS_RYOE to 1,
            NGS_RYOE_PER_ATT to 2,
            NGS_RUSH_EFFICIENCY to 2,
            NGS_STACKED_BOX_PCT to 1,
            NGS_SEPARATION to 2,
            NGS_CUSHION to 2,
            NGS_YAC_OVER_EXPECTED to 2,
            FANTASY_POINTS to 1,
            EXPECTED_FANTASY_POINTS to 1,
            FPOE to 1,
        )
    }
}
