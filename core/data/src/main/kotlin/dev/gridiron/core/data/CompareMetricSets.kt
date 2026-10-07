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
import dev.gridiron.core.statquery.StatColumn.DST_FUMBLE_RECOVERIES
import dev.gridiron.core.statquery.StatColumn.DST_INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DST_SACKS
import dev.gridiron.core.statquery.StatColumn.DST_SAFETIES
import dev.gridiron.core.statquery.StatColumn.DST_TDS
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.EZ_TARGETS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FG_ATT
import dev.gridiron.core.statquery.StatColumn.FG_MADE
import dev.gridiron.core.statquery.StatColumn.FG_MADE_50
import dev.gridiron.core.statquery.StatColumn.FPOE
import dev.gridiron.core.statquery.StatColumn.FTN_BLITZ_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CATCHABLE_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CONTESTED_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CREATED_REC
import dev.gridiron.core.statquery.StatColumn.FTN_DROPS
import dev.gridiron.core.statquery.StatColumn.FTN_DROP_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_INT_WORTHY_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_OUT_OF_POCKET_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_PLAY_ACTION_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_THROWAWAY_RATE
import dev.gridiron.core.statquery.StatColumn.GL_CARRIES
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
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
import dev.gridiron.core.statquery.StatColumn.OFFENSE_SNAPS
import dev.gridiron.core.statquery.StatColumn.PASSING_TDS
import dev.gridiron.core.statquery.StatColumn.PASSING_YARDS
import dev.gridiron.core.statquery.StatColumn.POINTS_ALLOWED
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
import dev.gridiron.core.statquery.StatColumn.XP_ATT
import dev.gridiron.core.statquery.StatColumn.XP_MADE
import dev.gridiron.core.statquery.StatColumn.YAC
import dev.gridiron.core.statquery.StatColumn.YARDS_ALLOWED
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
        CompareGroup.EFFICIENCY to listOf(
            EPA_PER_DROPBACK, CPOE, SACKS_TAKEN, INTERCEPTIONS, NGS_TIME_TO_THROW, NGS_INTENDED_AIR_YARDS, NGS_AGGRESSIVENESS,
            FTN_PLAY_ACTION_RATE, FTN_BLITZ_RATE, FTN_OUT_OF_POCKET_RATE, FTN_THROWAWAY_RATE, FTN_INT_WORTHY_RATE,
        ),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, PASSING_TDS, RUSHING_TDS),
        CompareGroup.CONTEXT to listOf(PASSING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val RB_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(CARRIES, CARRY_SHARE, TARGETS, TARGET_SHARE, WEIGHTED_OPPORTUNITIES, RZ_CARRIES, GL_CARRIES, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(
            RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, CATCH_RATE, NGS_RYOE, NGS_RYOE_PER_ATT, NGS_RUSH_EFFICIENCY, NGS_STACKED_BOX_PCT,
        ),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RUSHING_TDS, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RUSHING_YARDS, RECEIVING_YARDS, OFFENSE_SNAPS, TOTAL_EPA),
    )
    private val WR_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(TARGETS, TARGET_SHARE, AIR_YARDS_SHARE, WOPR, RZ_TARGETS, EZ_TARGETS, SNAP_SHARE),
        CompareGroup.EFFICIENCY to listOf(
            ADOT, RACR, CATCH_RATE, YAC, NGS_SEPARATION, NGS_CUSHION, NGS_YAC_OVER_EXPECTED,
            FTN_CATCHABLE_RATE, FTN_DROP_RATE, FTN_CONTESTED_RATE,
        ),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RECEIVING_TDS),
        CompareGroup.CONTEXT to listOf(RECEIVING_YARDS, RECEPTIONS, OFFENSE_SNAPS, TOTAL_EPA, FTN_DROPS, FTN_CREATED_REC),
    )

    // Kickers and defenses have no expected points, and a group with no stats is simply absent.
    private val K_SET = mapOf(
        CompareGroup.OPPORTUNITY to listOf(FG_ATT, XP_ATT),
        CompareGroup.EFFICIENCY to listOf(FG_MADE_50),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, FG_MADE, XP_MADE),
    )
    private val DST_SET = mapOf(
        CompareGroup.EFFICIENCY to listOf(POINTS_ALLOWED, YARDS_ALLOWED),
        CompareGroup.SCORING to listOf(FANTASY_POINTS, DST_TDS, DST_SAFETIES, StatColumn.DST_BLOCKED_KICKS),
        CompareGroup.CONTEXT to listOf(DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES),
    )

    /**
     * Next Gen Stats and FTN charting: published for some seasons and players only, so a missing value is a dash, never a
     * zero. Compare and the Player page's season line show them, the line only when the player has the stat.
     */
    public val CHARTED: Set<StatColumn> = setOf(
        NGS_AGGRESSIVENESS, NGS_CUSHION, NGS_INTENDED_AIR_YARDS, NGS_RUSH_EFFICIENCY, NGS_RYOE, NGS_RYOE_PER_ATT,
        NGS_SEPARATION, NGS_STACKED_BOX_PCT, NGS_TIME_TO_THROW, NGS_YAC_OVER_EXPECTED,
        FTN_BLITZ_RATE, FTN_CATCHABLE_RATE, FTN_CONTESTED_RATE, FTN_CREATED_REC, FTN_DROPS, FTN_DROP_RATE,
        FTN_INT_WORTHY_RATE, FTN_OUT_OF_POCKET_RATE, FTN_PLAY_ACTION_RATE, FTN_THROWAWAY_RATE,
    )

    public fun groupsFor(position: Position?): Map<CompareGroup, List<StatColumn>> = when (position) {
        Position.QB -> QB_SET
        Position.RB, Position.FB -> RB_SET
        Position.K -> K_SET
        Position.DST -> DST_SET
        else -> WR_SET
    }

    /** The compared positions' stats per group, in group order; a group nobody has rows for is left out. */
    public fun union(positions: List<Position?>): List<Pair<CompareGroup, List<StatColumn>>> =
        CompareGroup.entries
            .map { g -> g to positions.flatMap { groupsFor(it)[g].orEmpty() }.distinct() }
            .filter { (_, columns) -> columns.isNotEmpty() }

    public fun radarAxes(position: Position?): List<StatColumn> = when (position) {
        Position.QB -> listOf(EPA_PER_DROPBACK, CPOE, DROPBACKS, CARRIES, PASSING_TDS, FPOE)
        Position.RB, Position.FB -> listOf(CARRY_SHARE, TARGET_SHARE, RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY, GL_CARRIES, SNAP_SHARE, FPOE)
        Position.K -> listOf(FG_ATT, FG_MADE, FG_MADE_50, XP_MADE, FANTASY_POINTS)
        Position.DST -> listOf(POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, FANTASY_POINTS)
        else -> listOf(TARGET_SHARE, AIR_YARDS_SHARE, ADOT, RACR, YAC, RZ_TARGETS, FPOE)
    }

    /** Who is ranked at each position: the spec's population qualifiers. Every team's defense is ranked. */
    public fun qualifier(position: Position?): StatColumn = when (position) {
        Position.QB -> DROPBACKS
        Position.RB, Position.FB -> CARRIES
        Position.K -> FG_ATT
        Position.DST -> POINTS_ALLOWED
        else -> TARGETS
    }
}
