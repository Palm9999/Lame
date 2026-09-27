package dev.gridiron.core.forecast

/**
 * Bump whenever a constant below, a layer's formula or what gets stored
 * changes: a refresh only copies a season's projections out of a previous
 * database built with the same version.
 */
public const val FORECAST_VERSION: Int = 3

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
    // Layer 2 amendment (spec, 2026-09-26): shrink toward the player's own last season; newcomers
    // toward half the position's average share; the starting QB toward a starter's share. Only
    // players who played for their team in one of its last ACTIVE_WINDOW games are projected.
    const val NEWCOMER_SHARE_FACTOR = 0.5
    const val STARTER_PASS_SHARE = 0.97
    const val ACTIVE_WINDOW = 2

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

    // Layer 7, spread: sigma = a * mu^0.75, CV at mu = 10 by position (projections.py EMPIRICAL_CV).
    const val VARIANCE_EXPONENT = 0.75
    val EMPIRICAL_CV: Map<String, Double> = mapOf("QB" to 0.40, "RB" to 0.57, "WR" to 0.70, "TE" to 0.77)

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
    // Points allowed are about normal around their mean. Its spread, stored as the projection's variance,
    // is real team scores' spread around their implied points, measured once this many lined team-games
    // are in; about 10 points before that.
    const val PA_SD_DEFAULT = 10.0
    const val PA_SD_MIN_GAMES = 100

    // Storage: past weeks keep only players the model gave at least this many reference points.
    const val PAST_WEEK_MIN_POINTS = 1.0
    // The upcoming week and rest of season keep players above this.
    const val UPCOMING_MIN_POINTS = 0.1
}
