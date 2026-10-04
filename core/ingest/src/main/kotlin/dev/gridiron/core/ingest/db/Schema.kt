package dev.gridiron.core.ingest.db

/**
 * Written into `schema_meta`: the Python ETL's schema 5, plus `player_xref`
 * (6), plus `game`, minus the Python ETL's projection bookkeeping tables (7),
 * plus `team_week_defense`'s safeties and kickoff-return TDs (8), plus the
 * pre-aggregated `player_window_stat` and `window_def` (9), plus `player_week_signal` (10).
 */
public const val SCHEMA_VERSION: Int = 10

/**
 * Bump whenever a transform, the schema or an input's meaning changes: a build
 * only copies a season out of a previous database built with the same version.
 */
public const val INGEST_VERSION: Int = 8

internal const val SOURCE_NOTE: String = "nflverse-data (CC BY 4.0); FTN Data via nflverse (CC BY-SA 4.0); ffopportunity expected points (GPL >= 3)"

/** `etl/gridiron_etl/schema.py`'s DDL, one statement per entry, plus `player_xref` and `game`, minus `projection_snapshot` and `accuracy_summary`. */
internal val SCHEMA: List<String> = listOf(
    "PRAGMA journal_mode = OFF",
    "PRAGMA synchronous = OFF",
    "CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    """CREATE TABLE metric (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, abbr TEXT NOT NULL, "group" TEXT NOT NULL,
        definition TEXT NOT NULL, formula TEXT, positions TEXT NOT NULL, tier TEXT NOT NULL,
        predicts TEXT, stability REAL, higher_is_better INTEGER NOT NULL DEFAULT 1,
        decimals INTEGER NOT NULL DEFAULT 1, hot INTEGER NOT NULL DEFAULT 0,
        internal INTEGER NOT NULL DEFAULT 0, computed INTEGER NOT NULL DEFAULT 0,
        dist_family TEXT, zero_inflated INTEGER NOT NULL DEFAULT 0)""",
    """CREATE TABLE player (
        player_id TEXT PRIMARY KEY, full_name TEXT NOT NULL, search_name TEXT NOT NULL,
        position TEXT, team TEXT, pfr_player_id TEXT)""",
    """CREATE TABLE player_week_stat (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL, team TEXT,
        metric_id TEXT NOT NULL, value REAL NOT NULL,
        PRIMARY KEY (player_id, season, week, metric_id)) WITHOUT ROWID""",
    """CREATE TABLE player_week_projection (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        metric_id TEXT NOT NULL, stage TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
        PRIMARY KEY (player_id, season, week, metric_id, stage)) WITHOUT ROWID""",
    """CREATE TABLE player_week_projection_factor (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        factor TEXT NOT NULL, log_multiplier REAL NOT NULL, note TEXT,
        PRIMARY KEY (player_id, season, week, factor)) WITHOUT ROWID""",
    """CREATE TABLE player_ros_projection (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, as_of_week INTEGER NOT NULL,
        metric_id TEXT NOT NULL, mean REAL NOT NULL, variance REAL NOT NULL,
        PRIMARY KEY (player_id, season, as_of_week, metric_id)) WITHOUT ROWID""",
    // The Rising roles signal, entering `week` (from games before it), written by the forecast for RB, WR and TE.
    """CREATE TABLE player_week_signal (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        score REAL NOT NULL, usage_recent REAL NOT NULL, usage_base REAL NOT NULL,
        xp_recent REAL, xp_base REAL, vacated REAL NOT NULL, out_note TEXT,
        PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
    """CREATE TABLE team_week_defense (
        team TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        points_allowed REAL NOT NULL, yards_allowed REAL NOT NULL, sacks REAL NOT NULL,
        interceptions REAL NOT NULL, fumbles_recovered REAL NOT NULL, defensive_tds REAL NOT NULL,
        safeties REAL NOT NULL, kick_return_tds REAL NOT NULL,
        PRIMARY KEY (team, season, week)) WITHOUT ROWID""",
    """CREATE TABLE injury_report (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        team TEXT, name TEXT, position TEXT, status TEXT, injury TEXT, practice TEXT,
        PRIMARY KEY (player_id, season, week)) WITHOUT ROWID""",
    // ESPN athlete id to player id for every player nflverse lists, stats or not:
    // live news and injuries arrive keyed by ESPN id.
    """CREATE TABLE player_xref (
        espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL,
        position TEXT, team TEXT) WITHOUT ROWID""",
    // nflverse's schedule for the built seasons: opponents, results, pre-game lines, starting QBs and coaches.
    """CREATE TABLE game (
        game_id TEXT PRIMARY KEY, season INTEGER NOT NULL, week INTEGER NOT NULL, game_type TEXT NOT NULL,
        home_team TEXT NOT NULL, away_team TEXT NOT NULL, home_score INTEGER, away_score INTEGER,
        spread_line REAL, total_line REAL, roof TEXT, home_qb_id TEXT, away_qb_id TEXT,
        home_coach TEXT, away_coach TEXT) WITHOUT ROWID""",
    // Component sums over a season's whole regular season (`S`) and its last 3, 4, 5 and 8 weeks, built from
    // player_week_stat, keyed metric first so the Grid's read is a primary-key seek and needs no second index;
    // `window_def` holds each window's week bounds so a query can tell when a range matches.
    """CREATE TABLE player_window_stat (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, window TEXT NOT NULL,
        metric_id TEXT NOT NULL, value REAL NOT NULL,
        PRIMARY KEY (metric_id, season, window, player_id)) WITHOUT ROWID""",
    """CREATE TABLE window_def (
        season INTEGER NOT NULL, window TEXT NOT NULL, first_week INTEGER NOT NULL, last_week INTEGER NOT NULL,
        PRIMARY KEY (season, window)) WITHOUT ROWID""",
)

internal const val WINDOW_SEASON: String = "S"

/** The "last N weeks" windows: the Weeks sheet's "Last 4" quick pick, plus the 3, 5 and 8 the product spec names. */
internal val WINDOWS_LAST: List<Int> = listOf(3, 4, 5, 8)

/** The NFL moved from 17 to 18 regular-season weeks in 2021; `WeekRange.lastRegularSeasonWeek` is the query-side twin. */
internal fun lastRegularSeasonWeek(season: Int): Int = if (season >= 2021) 18 else 17

/** One covering index for the Grid's metric-over-range reads; see `schema.py` for why only these. */
internal val INDEXES: List<String> = listOf(
    "CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week, value)",
    "CREATE INDEX idx_player_search ON player (search_name)",
    "CREATE INDEX idx_player_position ON player (position)",
    "CREATE INDEX idx_game_week ON game (season, week)",
    "CREATE INDEX idx_signal_week ON player_week_signal (season, week, score)",
)
