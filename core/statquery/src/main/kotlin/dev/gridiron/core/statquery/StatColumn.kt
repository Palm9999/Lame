package dev.gridiron.core.statquery

import dev.gridiron.core.statquery.Aggregate.ClampedWeightedSum
import dev.gridiron.core.statquery.Aggregate.ClampedWeightedSum.Term
import dev.gridiron.core.statquery.Aggregate.Ratio
import dev.gridiron.core.statquery.Aggregate.Scored
import dev.gridiron.core.statquery.Aggregate.Total
import dev.gridiron.core.statquery.Components as C

/**
 * The closed set of columns the Grid can show, sort or filter by.
 *
 * This enum is the only route by which anything column-shaped reaches SQL, and
 * even then only as generated aliases: identifiers are never built from input.
 * [metricId] matches the ETL `metric` table, which supplies display metadata
 * (name, definition, decimals).
 */
public enum class StatColumn(
    public val metricId: String,
    public val aggregate: Aggregate,
    public val higherIsBetter: Boolean = true,
) {
    // Receiving
    TARGETS("targets", Total(C.TARGETS)),
    RECEPTIONS("receptions", Total(C.RECEPTIONS)),
    RECEIVING_YARDS("receiving_yards", Total(C.RECEIVING_YARDS)),
    RECEIVING_TDS("receiving_tds", Total(C.RECEIVING_TDS)),
    AIR_YARDS("air_yards", Total(C.AIR_YARDS)),
    YAC("yac", Total(C.YAC)),
    RZ_TARGETS("rz_targets", Total(C.RZ_TARGETS)),
    EZ_TARGETS("ez_targets", Total(C.EZ_TARGETS)),
    TARGET_SHARE("target_share", Ratio(C.TARGETS, C.TEAM_TARGETS)),
    AIR_YARDS_SHARE("air_yards_share", Ratio(C.AIR_YARDS, C.TEAM_AIR_YARDS)),
    WOPR(
        "wopr",
        ClampedWeightedSum(
            listOf(
                Term(1.5, Ratio(C.TARGETS, C.TEAM_TARGETS)),
                Term(0.7, Ratio(C.AIR_YARDS, C.TEAM_AIR_YARDS)),
            ),
        ),
    ),
    ADOT("adot", Ratio(C.AIR_YARDS, C.TARGETS)),
    RACR("racr", Ratio(C.RECEIVING_YARDS, C.AIR_YARDS)),
    CATCH_RATE("catch_rate", Ratio(C.RECEPTIONS, C.TARGETS)),

    // Rushing
    CARRIES("carries", Total(C.CARRIES)),
    RUSHING_YARDS("rushing_yards", Total(C.RUSHING_YARDS)),
    RUSHING_TDS("rushing_tds", Total(C.RUSHING_TDS)),
    RZ_CARRIES("rz_carries", Total(C.RZ_CARRIES)),
    GZ_CARRIES("gz_carries", Total(C.GZ_CARRIES)),
    GL_CARRIES("gl_carries", Total(C.GL_CARRIES)),
    // `carries_eff` (kneels/spikes excluded) is these three ratios' true
    // denominator, matching the ETL: `carries` itself (the visible column,
    // box-score-complete) now includes kneels, so it no longer agrees with
    // the stored weekly carry_share/rush_success_rate/rush_epa_per_carry.
    CARRY_SHARE("carry_share", Ratio(C.CARRIES_EFF, C.TEAM_CARRIES)),
    WEIGHTED_OPPORTUNITIES("weighted_opportunities", Total(C.WEIGHTED_OPPORTUNITIES)),
    RUSH_SUCCESS_RATE("rush_success_rate", Ratio(C.RUSH_SUCCESSES, C.CARRIES_EFF)),
    RUSH_EPA_PER_CARRY("rush_epa_per_carry", Ratio(C.RUSH_EPA, C.CARRIES_EFF)),

    // Passing
    ATTEMPTS("attempts", Total(C.ATTEMPTS)),
    COMPLETIONS("completions", Total(C.COMPLETIONS)),
    PASSING_YARDS("passing_yards", Total(C.PASSING_YARDS)),
    PASSING_TDS("passing_tds", Total(C.PASSING_TDS)),
    INTERCEPTIONS("interceptions", Total(C.INTERCEPTIONS), higherIsBetter = false),
    SACKS_TAKEN("sacks_taken", Total(C.SACKS_TAKEN), higherIsBetter = false),
    DROPBACKS("dropbacks", Total(C.DROPBACKS)),
    EPA_PER_DROPBACK("epa_per_dropback", Ratio(C.PASS_EPA, C.DROPBACKS)),
    CPOE("cpoe", Ratio(C.CPOE_SUM, C.CPOE_N)),
    QB_RUSH_INSIDE_5("qb_rush_inside_5", Total(C.QB_RUSH_INSIDE_5)),

    // Usage
    OFFENSE_SNAPS("offense_snaps", Total(C.OFFENSE_SNAPS)),
    SNAP_SHARE("snap_share", Ratio(C.OFFENSE_SNAPS, C.TEAM_OFFENSE_SNAPS)),
    TOTAL_EPA("total_epa", Total(C.TOTAL_EPA)),

    // Fantasy: scored per player-week from the spec's profile.
    FANTASY_POINTS("fantasy_points", Scored(ScoredOutput.FANTASY_POINTS)),
    EXPECTED_FANTASY_POINTS("expected_fantasy_points", Scored(ScoredOutput.EXPECTED_FANTASY_POINTS)),
    FPOE("fpoe", Scored(ScoredOutput.OVER_EXPECTED)),
    ;

    /**
     * The column holding the sample size behind this rate, if it is one. The UI
     * shows it in-row and uses it for minimum-opportunity thresholds, which the
     * spec makes mandatory on efficiency leaderboards.
     */
    public val sample: StatColumn?
        get() = when (this) {
            TARGET_SHARE, AIR_YARDS_SHARE, WOPR, ADOT, RACR, CATCH_RATE -> TARGETS
            CARRY_SHARE, RUSH_SUCCESS_RATE, RUSH_EPA_PER_CARRY -> CARRIES
            EPA_PER_DROPBACK -> DROPBACKS
            CPOE -> ATTEMPTS
            SNAP_SHARE -> OFFENSE_SNAPS
            else -> null
        }

    /** Computed from the spec's scoring profile rather than stored components. */
    public val isFantasy: Boolean get() = aggregate is Scored

    public companion object {
        public fun fromMetricId(id: String): StatColumn? = entries.firstOrNull { it.metricId == id }
    }
}
