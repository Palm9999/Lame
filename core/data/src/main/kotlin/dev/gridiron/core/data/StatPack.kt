package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.ADOT
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS
import dev.gridiron.core.statquery.StatColumn.AIR_YARDS_SHARE
import dev.gridiron.core.statquery.StatColumn.ATTEMPTS
import dev.gridiron.core.statquery.StatColumn.CARRIES
import dev.gridiron.core.statquery.StatColumn.CARRY_SHARE
import dev.gridiron.core.statquery.StatColumn.CATCH_RATE
import dev.gridiron.core.statquery.StatColumn.COMPLETIONS
import dev.gridiron.core.statquery.StatColumn.CPOE
import dev.gridiron.core.statquery.StatColumn.DST_FUMBLE_RECOVERIES
import dev.gridiron.core.statquery.StatColumn.DST_INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DST_SACKS
import dev.gridiron.core.statquery.StatColumn.DST_SAFETIES
import dev.gridiron.core.statquery.StatColumn.DST_TDS
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.DYNASTY_VALUE
import dev.gridiron.core.statquery.StatColumn.EXPECTED_FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.EZ_TARGETS
import dev.gridiron.core.statquery.StatColumn.FANTASY_POINTS
import dev.gridiron.core.statquery.StatColumn.FG_ATT
import dev.gridiron.core.statquery.StatColumn.FG_MADE
import dev.gridiron.core.statquery.StatColumn.FG_MADE_50
import dev.gridiron.core.statquery.StatColumn.FPOE
import dev.gridiron.core.statquery.StatColumn.GL_CARRIES
import dev.gridiron.core.statquery.StatColumn.GZ_CARRIES
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
import dev.gridiron.core.statquery.StatColumn.DROPBACKS
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
import dev.gridiron.core.statquery.StatColumn.RISING_ROLES
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

/**
 * The Grid's primary navigation: a curated column set, never all 39 at once.
 * Opportunity leads because opportunity is the stickiest signal there is.
 *
 * @property population The volume stat that decides who is ranked in this
 *   pack (receiving packs rank pass catchers by targets, rushing by carries).
 *   Null means everyone who played; a rate sort brings its own.
 */
public enum class StatPack(
    public val label: String,
    public val columns: List<StatColumn>,
    public val defaultSort: StatColumn,
    public val population: StatColumn?,
) {
    FANTASY(
        "Fantasy",
        listOf(FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, TARGETS, CARRIES, TARGET_SHARE, CARRY_SHARE, SNAP_SHARE),
        FANTASY_POINTS,
        OFFENSE_SNAPS,
    ),
    OPPORTUNITY(
        "Opportunity",
        listOf(WOPR, TARGET_SHARE, AIR_YARDS_SHARE, TARGETS, ADOT, SNAP_SHARE, RZ_TARGETS, EZ_TARGETS),
        WOPR,
        TARGETS,
    ),
    RISING(
        "Rising roles",
        listOf(RISING_ROLES, TARGET_SHARE, CARRY_SHARE, WOPR, TARGETS, CARRIES, SNAP_SHARE, WEIGHTED_OPPORTUNITIES),
        RISING_ROLES,
        null,
    ),
    DYNASTY(
        "Dynasty value",
        listOf(DYNASTY_VALUE, FANTASY_POINTS, EXPECTED_FANTASY_POINTS, FPOE, RISING_ROLES, SNAP_SHARE, TARGET_SHARE, CARRY_SHARE),
        DYNASTY_VALUE,
        null,
    ),
    RECEIVING(
        "Receiving",
        listOf(RECEIVING_YARDS, RECEPTIONS, TARGETS, RECEIVING_TDS, CATCH_RATE, YAC, AIR_YARDS, RACR),
        RECEIVING_YARDS,
        TARGETS,
    ),
    RUSHING(
        "Rushing",
        listOf(
            RUSHING_YARDS, CARRIES, RUSHING_TDS, CARRY_SHARE, RUSH_SUCCESS_RATE,
            RUSH_EPA_PER_CARRY, WEIGHTED_OPPORTUNITIES, SNAP_SHARE,
        ),
        RUSHING_YARDS,
        CARRIES,
    ),
    RED_ZONE(
        "Red zone",
        listOf(GZ_CARRIES, GL_CARRIES, RZ_CARRIES, RZ_TARGETS, EZ_TARGETS, RUSHING_TDS, RECEIVING_TDS),
        GZ_CARRIES,
        null,
    ),
    PASSING(
        "Passing",
        listOf(
            PASSING_YARDS, ATTEMPTS, COMPLETIONS, PASSING_TDS, INTERCEPTIONS,
            SACKS_TAKEN, EPA_PER_DROPBACK, CPOE, QB_RUSH_INSIDE_5,
        ),
        PASSING_YARDS,
        ATTEMPTS,
    ),
    EFFICIENCY(
        "Efficiency",
        listOf(TOTAL_EPA, EPA_PER_DROPBACK, CPOE, RUSH_EPA_PER_CARRY, RUSH_SUCCESS_RATE, ADOT, RACR, CATCH_RATE),
        TOTAL_EPA,
        null,
    ),
    NGS_PASSING(
        "NGS Passing",
        listOf(NGS_INTENDED_AIR_YARDS, NGS_TIME_TO_THROW, NGS_AGGRESSIVENESS, ATTEMPTS),
        NGS_INTENDED_AIR_YARDS,
        ATTEMPTS,
    ),
    NGS_RUSHING(
        "NGS Rushing",
        listOf(NGS_RYOE, NGS_RYOE_PER_ATT, NGS_RUSH_EFFICIENCY, NGS_STACKED_BOX_PCT, CARRIES),
        NGS_RYOE,
        CARRIES,
    ),
    NGS_RECEIVING(
        "NGS Receiving",
        listOf(NGS_SEPARATION, NGS_CUSHION, NGS_YAC_OVER_EXPECTED, TARGETS),
        NGS_SEPARATION,
        TARGETS,
    ),
    FTN_PASSING(
        "FTN Passing",
        listOf(
            FTN_PLAY_ACTION_RATE, FTN_BLITZ_RATE, FTN_OUT_OF_POCKET_RATE, FTN_THROWAWAY_RATE, FTN_INT_WORTHY_RATE,
            StatColumn.FTN_SCREEN_RATE, StatColumn.FTN_RPO_RATE, StatColumn.FTN_MOTION_RATE, StatColumn.FTN_NO_HUDDLE_RATE, DROPBACKS,
        ),
        FTN_PLAY_ACTION_RATE,
        DROPBACKS,
    ),
    FTN_RECEIVING(
        "FTN Receiving",
        listOf(
            FTN_CATCHABLE_RATE, FTN_DROP_RATE, FTN_CONTESTED_RATE, FTN_DROPS, FTN_CREATED_REC,
            StatColumn.FTN_SCREEN_TARGET_RATE, StatColumn.FTN_MOTION_TARGET_RATE, TARGETS,
        ),
        FTN_CATCHABLE_RATE,
        TARGETS,
    ),
    FTN_RUSHING(
        "FTN Rushing",
        listOf(StatColumn.FTN_AVG_BOX, CARRIES),
        StatColumn.FTN_AVG_BOX,
        CARRIES,
    ),
    KICKING(
        "Kicking",
        listOf(FANTASY_POINTS, FG_MADE, FG_ATT, FG_MADE_50, XP_MADE, XP_ATT),
        FANTASY_POINTS,
        FG_ATT,
    ),
    DEFENSE(
        "Defense",
        listOf(FANTASY_POINTS, POINTS_ALLOWED, YARDS_ALLOWED, DST_SACKS, DST_INTERCEPTIONS, DST_FUMBLE_RECOVERIES, DST_TDS, DST_SAFETIES, StatColumn.DST_BLOCKED_KICKS),
        FANTASY_POINTS,
        null,
    ),
    ;

    /** The chip a kicker or D/ST pack belongs to; null for the offense's packs. */
    public val unit: PositionFilter?
        get() = when (this) {
            KICKING -> PositionFilter.K
            DEFENSE -> PositionFilter.DST
            else -> null
        }
}

/** The Grid's position chips. All and the offense's chips never list kickers or D/STs; K and D/ST list only theirs. */
public enum class PositionFilter(public val label: String, public val positions: Set<Position>) {
    ALL("All", emptySet()),
    QB("QB", setOf(Position.QB)),
    RB("RB", setOf(Position.RB)),
    WR("WR", setOf(Position.WR)),
    TE("TE", setOf(Position.TE)),
    FLEX("FLEX", Position.FLEX),
    K("K", setOf(Position.K)),
    DST("D/ST", setOf(Position.DST)),
    ;

    /** The packs this chip offers: kickers and D/STs have their own, and every other chip shares the offense's. */
    public val packs: List<StatPack>
        get() = StatPack.entries.filter { it.unit == this || (it.unit == null && this != K && this != DST) }
}
