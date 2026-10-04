package dev.gridiron.core.forecast

/**
 * Bump whenever a constant below, a layer's formula or what gets stored
 * changes: a refresh only copies a season's projections out of a previous
 * database built with the same version.
 */
public const val FORECAST_VERSION: Int = 12

/**
 * Every tuning number the model uses. Sources: the Python ETL's
 * `shrinkage.py` and `projections.py` (deleted in Task 12; their values are
 * carried here verbatim) and `docs/research/research-prediction-models.md`.
 */
internal object K {
    // Layer 1, team volume: play counts are sticky, so a 4-game half-life.
    const val TEAM_HALF_LIFE = 4.0

    // Layer 2, player share (shrinkage.py HALF_LIVES and SHRINKAGE_K; k in games).
    const val SHARE_HALF_LIFE = 4.5
    const val SHARE_K_GAMES = 5.0

    // Layer 2b: an RB, WR or TE's baseline moves this far toward his per-game stats this season. On the same
    // player-weeks, PPR MAE fell at RB, WR and TE in 2024 (-0.084 pooled, ±0.021 at 2 SE) and 2025 (-0.036,
    // ±0.019); a QB blend gained nothing. 0.25 gained more in 2024 but no more in 2025, and pulls a rising
    // player harder toward his early, smaller games.
    const val SEASON_FORM_WEIGHT = 0.15

    // Layer 2 amendment: an RB, WR or TE's target and carry shares move this far toward what his snap share over
    // his last SNAP_GAMES games implies (the position's share per unit of snap share). Fitted on 2022-2023 and on
    // 2024-2025 separately: 0.20-0.30 for targets and carries in both.
    const val SNAP_SHARE_WEIGHT = 0.25
    const val SNAP_GAMES = 3

    // Layer 7: how far a QB, RB, WR or TE's final projection moves toward ESPN's for the same week. Fitted leaving
    // one season out on 2022-2025 (points level): QB 0.55-0.60, RB 0.60-0.70, WR 0.65-0.75, TE 0.45-0.55.
    val ESPN_WEIGHT: Map<String, Double> = mapOf("QB" to 0.55, "RB" to 0.6, "WR" to 0.65, "TE" to 0.5)

    // Layer 2c: a starting QB's baseline keeps this share of its distance from the league's typical starter.
    // Fitted leaving one season out on 2022-2025 (built from 2021): 0.60-0.65 in every fold.
    const val QB_SPREAD = 0.65
    // Layer 2 amendment (spec, 2026-09-26): shrink toward the player's own last season; newcomers
    // toward half the position's average share; the starting QB toward a starter's share. Only
    // players who played for their team in one of its last ACTIVE_WINDOW games are projected.
    const val NEWCOMER_SHARE_FACTOR = 0.5
    const val STARTER_PASS_SHARE = 0.97
    const val ACTIVE_WINDOW = 2

    // An RB, WR or TE outside the active window (back from injury, or sat the last games of last season) is
    // projected again once ESPN projects him for this many PPR points. On 2022-2025 (built from 2021) ESPN's 3+
    // plays about 87%; it adds 374 played weeks and teammates' PPR MAE falls 0.008 (±0.003). 5 and 8 add fewer
    // and gain less. Treating a player ESPN stops projecting as out was tested and cost 0.019: not used.
    const val RETURN_MIN_ESPN_POINTS = 3.0

    // Layer 3, efficiency (shrinkage.py: half-life 10, catch_rate k = 15 games, int_rate k = 150 attempts).
    const val EFFICIENCY_HALF_LIFE = 10.0
    const val EFFICIENCY_K_GAMES = 15.0
    const val INT_K_ATTEMPTS = 150.0

    // Layer 4, touchdowns from expected TDs (shrinkage.py td_rate, k in opportunities).
    const val TD_K_OPPORTUNITIES = 200.0

    // Layer 5, matchup: ridge penalty, extra shrinkage by games rated, minimum rows to rate at all, caps.
    const val RIDGE_LAMBDA = 4.0
    const val MATCHUP_K_GAMES = 6.0
    const val MIN_MATCHUP_ROWS = 16
    const val CAP_EFFICIENCY = 0.15
    const val CAP_VOLUME = 0.05
    const val CAP_TD = 0.20

    // Layer 6, game script (research doc §1.6): implied points move TDs most, yards less, attempts least.
    const val ELASTICITY_TD = 1.0
    const val ELASTICITY_YARDS = 0.5
    const val ELASTICITY_ATTEMPTS = 0.25
    const val IMPLIED_RATIO_MIN = 0.6
    const val IMPLIED_RATIO_MAX = 1.5
    const val LEAGUE_IMPLIED_DEFAULT = 22.0
    const val PASS_RATE_PER_POINT = 0.006
    const val PASS_RATE_MIN = 0.30
    const val PASS_RATE_MAX = 0.80

    // Layer 7, spread: sigma = a * mu^0.75, CV at mu = 10 by position (projections.py EMPIRICAL_CV; K and DST from spec §6).
    const val VARIANCE_EXPONENT = 0.75
    val EMPIRICAL_CV: Map<String, Double> = mapOf("QB" to 0.40, "RB" to 0.57, "WR" to 0.70, "TE" to 0.77, "K" to 0.52, "DST" to 0.85)

    // Props (spec §5). Props can't be backtested (no historical props), so these are judgments, not fits.
    // The market's variance is this share of layer 7's for its mean: markets are sharper than the model, so
    // with equal means the market gets 1 / (1 + 0.5) = two thirds of the weight.
    const val MARKET_VARIANCE_RATIO = 0.5
    // A book that offers only Yes on an anytime TD can't be de-vigged; its implied chance is divided by this.
    const val ONE_SIDED_OVERROUND = 1.08

    // K model (spec §6). Judgments, not fits: field goal accuracy is noisy, so a kicker's own mix and
    // make rates need many kicks to move off the league's. The accuracy page measures the result.
    // Fewer lined team-games than this and field goal and extra point tries are flat averages.
    const val KICK_MIN_FIT_ROWS = 64
    const val KICK_MIX_K = 20.0 // field goal tries
    const val KICK_MAKE_K = 30.0 // tries in the bucket
    const val XP_MAKE_K = 60.0 // extra point tries
    // Without a line, a team's expected points: its recent scoring, shrunk this many games toward the league's.
    const val TEAM_POINTS_K_GAMES = 4.0

    // D/ST model (spec §6). Judgments, not fits, measured by the accuracy page. A unit's own per-game
    // rates are recency-weighted and shrunk toward the league's by games: rarer, noisier events harder.
    const val DST_HALF_LIFE = 6.0
    val DST_K: Map<String, Double> = mapOf(
        "dst_sacks" to 6.0, "dst_interceptions" to 12.0, "dst_fumble_recoveries" to 20.0,
        "dst_tds" to 30.0, "dst_safeties" to 60.0,
    )
    const val DST_PA_K = 6.0
    // What defenses got against an offense, shrunk this many games, and capped to 1 ± DST_CAP of the league's.
    const val DST_OPP_K = 8.0
    const val DST_CAP = 0.30
    // Yards allowed (net): a defense's own rate is shrunk this many games toward the league's. The opponent's yards
    // use DST_OPP_K and DST_CAP. A line scales them by (its ratio to expected points) ^ DST_YA_SCRIPT_ELASTICITY,
    // since yards move about half as far as points do. The spread is DST_YA_CV of the mean: real games' residual
    // around each team-season's own average was 0.239 of the league mean (2024-2025, 1,140 team-games); there is no
    // posted yards line to measure misses against.
    const val DST_YA_K = 6.0
    const val DST_YA_SCRIPT_ELASTICITY = 0.5
    const val DST_YA_CV = 0.25
    // Points allowed are about normal around their mean. Its spread, stored as the projection's variance,
    // is real team scores' spread around their implied points, measured once this many lined team-games
    // are in; about 10 points before that.
    const val PA_SD_DEFAULT = 10.0
    const val PA_SD_MIN_GAMES = 100

    // Rising roles (Breakout.kt). Judgments, not fits: the 2024 and 2025 backtest found these three signals each
    // lift the chance a role keeps growing, but with so few cases the weights are plain choices. A role is
    // "recent" over the last BREAKOUT_RECENT games and "base" over the BREAKOUT_BASE before them.
    const val BREAKOUT_RECENT = 4
    const val BREAKOUT_BASE = 8
    const val BREAKOUT_MIN_BASE = 4
    // Usage a game must average (RB: carries + targets; WR, TE: targets) for a player to be scored at all.
    const val BREAKOUT_MIN_USAGE_RB = 8.0
    const val BREAKOUT_MIN_USAGE_WR = 3.0
    const val BREAKOUT_MIN_USAGE_TE = 2.5
    // A 60% rise in usage, or in expected points, scores a full 1.
    const val BREAKOUT_FULL_RISE = 0.6
    const val BREAKOUT_W_USAGE = 0.45
    const val BREAKOUT_W_EXPECTED = 0.40
    const val BREAKOUT_W_VACATED = 0.15

    // Storage: past weeks keep only players the model gave at least this many reference points.
    const val PAST_WEEK_MIN_POINTS = 1.0
    // The upcoming week and rest of season keep players above this.
    const val UPCOMING_MIN_POINTS = 0.1
}
