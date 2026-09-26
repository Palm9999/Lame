package dev.gridiron.core.ingest.db

/** Written into `schema_meta`: the Python ETL's schema 5 plus `player_xref`. */
public const val SCHEMA_VERSION: Int = 6

/**
 * Bump whenever a transform, the schema or an input's meaning changes: a build
 * only copies a season out of a previous database built with the same version.
 */
public const val INGEST_VERSION: Int = 1

internal const val SOURCE_NOTE: String = "nflverse-data (CC BY 4.0); ffopportunity expected points (GPL >= 3)"

/** `etl/gridiron_etl/schema.py`'s DDL, one statement per entry, plus `player_xref`. */
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
    """CREATE TABLE projection_snapshot (
        player_id TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        metric_id TEXT NOT NULL, projected_mean REAL NOT NULL, projected_variance REAL NOT NULL,
        snapshot_at TEXT NOT NULL,
        PRIMARY KEY (player_id, season, week, metric_id, snapshot_at)) WITHOUT ROWID""",
    """CREATE TABLE accuracy_summary (
        position TEXT NOT NULL, season INTEGER NOT NULL, metric_id TEXT NOT NULL,
        baseline TEXT NOT NULL, sample_n INTEGER NOT NULL, mae REAL NOT NULL, rmse REAL NOT NULL,
        bias REAL NOT NULL, r2 REAL,
        PRIMARY KEY (position, season, metric_id, baseline)) WITHOUT ROWID""",
    """CREATE TABLE team_week_defense (
        team TEXT NOT NULL, season INTEGER NOT NULL, week INTEGER NOT NULL,
        points_allowed REAL NOT NULL, yards_allowed REAL NOT NULL, sacks REAL NOT NULL,
        interceptions REAL NOT NULL, fumbles_recovered REAL NOT NULL, defensive_tds REAL NOT NULL,
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
)

/** One covering index for the Grid's metric-over-range reads; see `schema.py` for why only these. */
internal val INDEXES: List<String> = listOf(
    "CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week, value)",
    "CREATE INDEX idx_player_search ON player (search_name)",
    "CREATE INDEX idx_player_position ON player (position)",
)
