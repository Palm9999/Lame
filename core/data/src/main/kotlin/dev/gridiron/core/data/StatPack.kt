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
import dev.gridiron.core.statquery.StatColumn.EPA_PER_DROPBACK
import dev.gridiron.core.statquery.StatColumn.EZ_TARGETS
import dev.gridiron.core.statquery.StatColumn.GL_CARRIES
import dev.gridiron.core.statquery.StatColumn.GZ_CARRIES
import dev.gridiron.core.statquery.StatColumn.INTERCEPTIONS
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
    OPPORTUNITY(
        "Opportunity",
        listOf(WOPR, TARGET_SHARE, AIR_YARDS_SHARE, TARGETS, ADOT, SNAP_SHARE, RZ_TARGETS, EZ_TARGETS),
        WOPR,
        TARGETS,
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
}

public enum class PositionFilter(public val label: String, public val positions: Set<Position>) {
    ALL("All", emptySet()),
    QB("QB", setOf(Position.QB)),
    RB("RB", setOf(Position.RB)),
    WR("WR", setOf(Position.WR)),
    TE("TE", setOf(Position.TE)),
    FLEX("FLEX", Position.FLEX),
}
