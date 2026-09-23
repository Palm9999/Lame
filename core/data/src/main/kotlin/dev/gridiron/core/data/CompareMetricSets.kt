package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn.ADOT
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS_SHARE
import dev.gridiron.core.statquery.StatColumn.ATTEMPTS
import dev.gridiron.core.statquery.StatColumn.CARRIES
import dev.gridiron.core.statquery.StatColumn.CARRY_SHARE
import dev.gridiron.core.statquery.StatColumn.CATCH_RATE
import dev.gridiron.core.statquery.StatColumn.CPOE
import dev.gridiron.core.statquery.StatColumn.DROPBACKS
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.EZ_TARGETS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FPOE
import dev.gridiron.core.statquery.StatColumn.GL_CARRIES
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.OFFENSE_SNAPS
import dev.gridiron.core.statquery.StatColumn.PASSING_TDS
import dev.gridiron.core.statquery.StatColumn.PASSING_YARDS
import dev.gridiron.core.statquery.StatColumn.QB_RUSH_INSIDE_5
import dev.gridiron.core.statquery.StatColumn.RACR
import dev.gridiron.core.statquery.StatColumn.RECEIVING_TDS
import dev.gridiron.core.statquery.StatColumn.RECEIVING_YARDS
import dev.gridiron.core.statquery.StatColumn.RECEPTIONS
import dev.gridiron.core.statquery.StatColumn.RUSHING_TDS
import dev.gridiron.core.statquery.StatColumn.RUSHING_YARDS
import dev.gridiron.core.statquery.StatColumn.RUSH_EPA_PER_CARRY
import dev.gridiron.core.statquery.StatColumn.RUSH_SUCCESS_RATE
import dev.gridiron.core.statquery.StatColumn.RZ_CARRIES
import dev.gridiron.core.statquery.StatColumn.RZ_TARGETS
import dev.gridiron.core.statquery.StatColumn.SACKS_TAKEN
import dev.gridiron.core.statquery.StatColumn.SNAP_SHARE
import dev.gridiron.core.statquery.StatColumn.TARGETS
import dev.gridiron.core.statquery.StatColumn.TARGET_SHARE
import dev.gridiron.core.statquery.StatColumn.TOTAL_EPA
import dev.gridiron.core.statquery.StatColumn.WEIGHTED_OPPORTUNITIES
import dev.gridiron.core.statquery.StatColumn.WOPR
import dev.gridiron.core.statquery.StatColumn.YAC
import dev.gridiron.core.statquery.StatColumn

public enum class CompareGroup(public val label: String) {
    OPPORTUNITY("Opportunity"),
    EFFICIENCY("Efficiency"),
    SCORING("Scoring"),
    CONTEXT("Context"),
}

/**
 * Which stats Compare shows per position, in one table so they can be tuned
 * without touching the UI. From the design spec.
 */
public object CompareMetricSets {
    private val QB_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(DROPBACKS, ATTEMPTS, CARRIES, QB_RUSH_INSIDE_5, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(EPA_PER_DROPBACK, CPOE, SACKS_TAKEN, INTERCEPTIONS),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, PASSING_TDS, RUSHING_TDS),
        CompareGroup.CONTEXT to listOf(PASSING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val RB_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(CARRIES, CARRY_SHARE, TARGETS, TARGET_SHARE, WEIGHTED_OPPORTUNITIES, RZ_CARRIES, GL_CARRIES, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, CATCH_RATE),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RUSHING_TDS, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RUSHING_YARDS, RECEIVING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val WR_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(TARGETS, TARGET_SHARE, AIR_YARDS_SHARE, WOPR, RZ_TARGETS, EZ_TARGETS, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(ADOT, RACR, CATCH_RATE, YAC),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RECEIVING_YARDS, RECEPTIONS, OFFENSE_SNAPS, TOTAL_EPA),
    )

    public fun groupsFor(position: Position?): Map<CompareGroup, List<StatColumn>> = when (position) {
        Position.QB -> QB_SET
        Position.RB, Position.FB -> RB_SET
        else -> WR_SET
    }

    public fun union(positions: List<Position?>): List<Pair<CompareGroup, List<StatColumn>>> =
        CompareGroup.entries.map { g -> g to positions.flatMap { groupsFor(it).getValue(g) }.distinct() }

    public fun radarAxes(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> listOf(EPA_PER_DROPBACK, CPOE, DROPBACKS, CARRIES, PASSING_TDS, FPOE)
        Position.RB, Position.FB -> listOf(CARRY_SHARE, TARGET_SHARE, RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, GL_CARRIES, SNAP_SHARE, FPOE)
        else -> listOf(TARGET_SHARE, AIR_YARDS_SHARE, ADOT, RACR, YAC, RZ_TARGETS, FPOE)
    }

    /** Who is ranked at each position: the spec's population qualifiers. */
    public fun qualifier(position: Position?): StatColumn = when (position) {
        Position.QB -> DROPBACKS
        Position.RB, Position.FB -> CARRIES
        else -> TARGETS
    }
}
