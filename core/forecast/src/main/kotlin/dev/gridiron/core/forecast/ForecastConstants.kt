package dev.gridiron.core.forecast

/**
 * Bump whenever a constant below, a layer's formula or what gets stored
 * changes: a refresh only copies a season's projections out of a previous
 * database built with the same version.
 */
public const val FORECAST_VERSION: Int = 1

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
    const val CARRYOVER_START = 0.55
    const val CARRYOVER_LAST_WEEK = 6

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

    // Storage: past weeks keep only players the model gave at least this many reference points.
    const val PAST_WEEK_MIN_POINTS = 1.0
    // The upcoming week and rest of season keep players above this.
    const val UPCOMING_MIN_POINTS = 0.1
}
