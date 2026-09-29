package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.CARRIES
import dev.gridiron.core.statquery.StatColumn.DST_INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DST_SACKS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FG_ATT
import dev.gridiron.core.statquery.StatColumn.FG_MADE
import dev.gridiron.core.statquery.StatColumn.FG_MADE_50
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.PASSING_TDS
import dev.gridiron.core.statquery.StatColumn.PASSING_YARDS
import dev.gridiron.core.statquery.StatColumn.POINTS_ALLOWED
import dev.gridiron.core.statquery.StatColumn.RECEIVING_TDS
import dev.gridiron.core.statquery.StatColumn.RECEIVING_YARDS
import dev.gridiron.core.statquery.StatColumn.RECEPTIONS
import dev.gridiron.core.statquery.StatColumn.RUSHING_YARDS
import dev.gridiron.core.statquery.StatColumn.TARGETS
import dev.gridiron.core.statquery.StatColumn.XP_MADE
import dev.gridiron.core.statquery.StatColumn.YARDS_ALLOWED

/**
 * The stats on the Player page's game log, one list per position, in one table
 * so they can be tuned without touching the UI. The season line's stats come
 * from [CompareMetricSets]. Fantasy points lead every log, then four stats.
 */
public object PlayerStatSets {
    private val QB = listOf(FANTASY_POINTS, PASSING_YARDS, PASSING_TDS, INTERCEPTIONS, RUSHING_YARDS)
    private val RB = listOf(FANTASY_POINTS, CARRIES, RUSHING_YARDS, RECEPTIONS, RECEIVING_YARDS)
    private val WR_TE = listOf(FANTASY_POINTS, TARGETS, RECEPTIONS, RECEIVING_YARDS, RECEIVING_TDS)
    private val K = listOf(FANTASY_POINTS, FG_MADE, FG_ATT, FG_MADE_50, XP_MADE)
    private val DST = listOf(FANTASY_POINTS, POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS)

    /** An unknown position logs like a receiver, as Compare treats it. */
    public fun logColumns(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> QB
        Position.RB, Position.FB -> RB
        Position.K -> K
        Position.DST -> DST
        else -> WR_TE
    }
}
