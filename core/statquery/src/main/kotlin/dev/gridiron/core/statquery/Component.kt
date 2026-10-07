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
    public val CARRIES_EFF: Component = Component("carries_eff")
    public val TEAM_OFFENSE_SNAPS: Component = Component("team_offense_snaps")
    public val RUSH_SUCCESSES: Component = Component("rush_successes")
    public val RUSH_EPA: Component = Component("rush_epa")
    public val PASS_EPA: Component = Component("pass_epa")
    public val CPOE_SUM: Component = Component("cpoe_sum")
    public val CPOE_N: Component = Component("cpoe_n")

    // Next Gen Stats. Each weekly average is stored as average x weight beside
    // its weight (NGS's own attempts, carries, targets or receptions), so a
    // range recomputes as a weighted average. All are sparse: absent means no NGS row.
    public val NGS_ATTEMPTS: Component = Component("ngs_attempts")
    public val NGS_CARRIES: Component = Component("ngs_carries")
    public val NGS_RUSH_YARDS: Component = Component("ngs_rush_yards")
    public val NGS_TARGETS: Component = Component("ngs_targets")
    public val NGS_RECEPTIONS: Component = Component("ngs_receptions")
    public val NGS_TTT_W: Component = Component("ngs_ttt_w")
    public val NGS_AGGR_W: Component = Component("ngs_aggr_w")
    public val NGS_IAY_W: Component = Component("ngs_iay_w")
    public val NGS_EFF_W: Component = Component("ngs_eff_w")
    public val NGS_BOX_W: Component = Component("ngs_box_w")
    public val NGS_SEP_W: Component = Component("ngs_sep_w")
    public val NGS_CUSH_W: Component = Component("ngs_cush_w")
    public val NGS_YACOE_W: Component = Component("ngs_yacoe_w")
    public val NGS_RYOE: Component = Component("ngs_ryoe")

    // FTN charting. Counts beside FTN's own denominators (plays FTN charted), so a range
    // recomputes as a ratio of sums. None is sparse: a week with no drops is a real zero.
    public val FTN_TARGETS: Component = Component("ftn_targets")
    public val FTN_CATCHABLE: Component = Component("ftn_catchable")
    public val FTN_CONTESTED: Component = Component("ftn_contested")
    public val FTN_DROPS: Component = Component("ftn_drops")
    public val FTN_CREATED_REC: Component = Component("ftn_created_rec")
    public val FTN_DROPBACKS: Component = Component("ftn_dropbacks")
    public val FTN_ATTEMPTS: Component = Component("ftn_attempts")
    public val FTN_PA_DB: Component = Component("ftn_pa_db")
    public val FTN_BLITZ_DB: Component = Component("ftn_blitz_db")
    public val FTN_OOP_DB: Component = Component("ftn_oop_db")
    public val FTN_THROWAWAY: Component = Component("ftn_throwaway")
    public val FTN_INT_WORTHY: Component = Component("ftn_int_worthy")
    public val FTN_SCREEN_TARGETS: Component = Component("ftn_screen_targets")
    public val FTN_MOTION_TARGETS: Component = Component("ftn_motion_targets")
    public val FTN_SCREEN_DB: Component = Component("ftn_screen_db")
    public val FTN_RPO_DB: Component = Component("ftn_rpo_db")
    public val FTN_NO_HUDDLE_DB: Component = Component("ftn_no_huddle_db")
    public val FTN_MOTION_DB: Component = Component("ftn_motion_db")
    public val FTN_BOX_CARRIES: Component = Component("ftn_box_carries")
    public val FTN_BOX_SUM: Component = Component("ftn_box_sum")
    public val FTN_SHOTGUN_DB: Component = Component("ftn_shotgun_db")
    public val FTN_RUSHERS_DB: Component = Component("ftn_rushers_db")
    public val FTN_RUSHERS_SUM: Component = Component("ftn_rushers_sum")
    public val FTN_READ_ATT: Component = Component("ftn_read_att")
    public val FTN_FIRST_READ: Component = Component("ftn_first_read")

    // Scoring inputs. Internal and sparse (absent means zero); read only by
    // the scoring step, which applies the spec's profile per player-week.
    public val PASSING_FIRST_DOWNS: Component = Component("passing_first_downs")
    public val RUSHING_FIRST_DOWNS: Component = Component("rushing_first_downs")
    public val RECEIVING_FIRST_DOWNS: Component = Component("receiving_first_downs")
    public val PASSING_2PT: Component = Component("passing_2pt")
    public val RUSHING_2PT: Component = Component("rushing_2pt")
    public val RECEIVING_2PT: Component = Component("receiving_2pt")
    public val FUMBLES_LOST: Component = Component("fumbles_lost")
    public val PASSING_TDS_40: Component = Component("passing_tds_40")
    public val PASSING_TDS_50: Component = Component("passing_tds_50")
    public val RUSHING_TDS_40: Component = Component("rushing_tds_40")
    public val RUSHING_TDS_50: Component = Component("rushing_tds_50")
    public val RECEIVING_TDS_40: Component = Component("receiving_tds_40")
    public val RECEIVING_TDS_50: Component = Component("receiving_tds_50")

    // The opportunity model's expectations for the same player-week.
    public val X_COMPLETIONS: Component = Component("x_completions")
    public val X_RECEPTIONS: Component = Component("x_receptions")
    public val X_PASSING_YARDS: Component = Component("x_passing_yards")
    public val X_RUSHING_YARDS: Component = Component("x_rushing_yards")
    public val X_RECEIVING_YARDS: Component = Component("x_receiving_yards")
    public val X_PASSING_TDS: Component = Component("x_passing_tds")
    public val X_RUSHING_TDS: Component = Component("x_rushing_tds")
    public val X_RECEIVING_TDS: Component = Component("x_receiving_tds")
    public val X_PASSING_2PT: Component = Component("x_passing_2pt")
    public val X_RUSHING_2PT: Component = Component("x_rushing_2pt")
    public val X_RECEIVING_2PT: Component = Component("x_receiving_2pt")
    public val X_PASSING_FIRST_DOWNS: Component = Component("x_passing_first_downs")
    public val X_RUSHING_FIRST_DOWNS: Component = Component("x_rushing_first_downs")
    public val X_RECEIVING_FIRST_DOWNS: Component = Component("x_receiving_first_downs")
    public val X_INTERCEPTIONS: Component = Component("x_interceptions")

    // Kicking: the kicker's scoring inputs. Internal and sparse, except the
    // 50+ makes and extra points made, which the Grid's Kicking pack shows too.
    public val FG_MADE_0_39: Component = Component("fg_made_0_39")
    public val FG_MADE_40_49: Component = Component("fg_made_40_49")
    public val FG_MADE_50: Component = Component("fg_made_50")
    public val FG_MISSED: Component = Component("fg_missed")
    public val XP_MADE: Component = Component("xp_made")
    public val XP_MISSED: Component = Component("xp_missed")
    // Totals the Grid's Kicking pack shows; no rule reads them.
    public val FG_ATT: Component = Component("fg_att")
    public val FG_MADE: Component = Component("fg_made")
    public val XP_ATT: Component = Component("xp_att")

    // Team defense and special teams (D/ST pseudo-players). Sparse, except
    // points and yards allowed, whose zero is a shutout; the profile's tiers score them.
    public val DST_SACKS: Component = Component("dst_sacks")
    public val DST_INTERCEPTIONS: Component = Component("dst_interceptions")
    public val DST_FUMBLE_RECOVERIES: Component = Component("dst_fumble_recoveries")
    public val DST_TDS: Component = Component("dst_tds")
    public val DST_SAFETIES: Component = Component("dst_safeties")
    public val DST_BLOCKED_KICKS: Component = Component("dst_blocked_kicks")
    public val POINTS_ALLOWED: Component = Component("points_allowed")
    public val YARDS_ALLOWED: Component = Component("yards_allowed")
}
