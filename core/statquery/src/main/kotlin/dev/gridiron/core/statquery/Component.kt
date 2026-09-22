package dev.gridiron.core.statquery

/**
 * A metric stored per player-week in `player_week_stat`, summed over a week range.
 *
 * Components are the raw material; a [StatColumn] is computed from them. Every
 * id must match a row in the ETL's `metric` table, which a contract test checks
 * against a real database.
 */
@JvmInline
public value class Component(public val id: String) {
    override fun toString(): String = id
}

/** Every component the builder reads. */
public object Components {
    /** 1 per week with a recorded play; sums to games played. */
    public val GAMES: Component = Component("g")

    public val TARGETS: Component = Component("targets")
    public val RECEPTIONS: Component = Component("receptions")
    public val RECEIVING_YARDS: Component = Component("receiving_yards")
    public val RECEIVING_TDS: Component = Component("receiving_tds")
    public val AIR_YARDS: Component = Component("air_yards")
    public val YAC: Component = Component("yac")
    public val RZ_TARGETS: Component = Component("rz_targets")
    public val EZ_TARGETS: Component = Component("ez_targets")

    public val CARRIES: Component = Component("carries")
    public val RUSHING_YARDS: Component = Component("rushing_yards")
    public val RUSHING_TDS: Component = Component("rushing_tds")
    public val RZ_CARRIES: Component = Component("rz_carries")
    public val GZ_CARRIES: Component = Component("gz_carries")
    public val GL_CARRIES: Component = Component("gl_carries")
    public val QB_RUSH_INSIDE_5: Component = Component("qb_rush_inside_5")
    public val WEIGHTED_OPPORTUNITIES: Component = Component("weighted_opportunities")

    public val ATTEMPTS: Component = Component("attempts")
    public val COMPLETIONS: Component = Component("completions")
    public val PASSING_YARDS: Component = Component("passing_yards")
    public val PASSING_TDS: Component = Component("passing_tds")
    public val INTERCEPTIONS: Component = Component("interceptions")
    public val SACKS_TAKEN: Component = Component("sacks_taken")
    public val DROPBACKS: Component = Component("dropbacks")

    public val OFFENSE_SNAPS: Component = Component("offense_snaps")
    public val TOTAL_EPA: Component = Component("total_epa")

    // Internal denominators and sums. Never displayed; stored so that rates can
    // be recomputed over a range instead of averaging weekly rates.
    public val TEAM_TARGETS: Component = Component("team_targets")
    public val TEAM_AIR_YARDS: Component = Component("team_air_yards")
    public val TEAM_CARRIES: Component = Component("team_carries")
    public val TEAM_OFFENSE_SNAPS: Component = Component("team_offense_snaps")
    public val RUSH_SUCCESSES: Component = Component("rush_successes")
    public val RUSH_EPA: Component = Component("rush_epa")
    public val PASS_EPA: Component = Component("pass_epa")
    public val CPOE_SUM: Component = Component("cpoe_sum")
    public val CPOE_N: Component = Component("cpoe_n")
}
