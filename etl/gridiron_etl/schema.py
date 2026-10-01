"""SQLite schema and load.

Two rules from the spec are enforced structurally here:

  * No table or column name contains a year. A new season is data, not schema.
  * This file builds `stats.db` only — the server-derived, fully reconstructible
    database. User state (presets, rosters, draft boards) lives in a separate
    `user.db` that is never destructively migrated.

The database ships prebuilt, pre-indexed and pre-ANALYZEd. Inserting JSON on the
device costs 20-90s on first launch; copying a .db file costs 1-3s.
"""

from __future__ import annotations

import logging
import sqlite3
from pathlib import Path

import polars as pl

log = logging.getLogger(__name__)

SCHEMA_VERSION = 7

DDL = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE schema_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- Metric registry. Drives info sheets, column search, onboarding and empty
-- states, so adding a metric never requires a UI change.
CREATE TABLE metric (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    abbr             TEXT NOT NULL,
    "group"          TEXT NOT NULL,
    definition       TEXT NOT NULL,
    formula          TEXT,
    positions        TEXT NOT NULL,
    tier             TEXT NOT NULL,
    predicts         TEXT,
    stability        REAL,
    higher_is_better INTEGER NOT NULL DEFAULT 1,
    decimals         INTEGER NOT NULL DEFAULT 1,
    hot              INTEGER NOT NULL DEFAULT 0,
    -- Range-aggregation components; never offered as a visible column.
    internal         INTEGER NOT NULL DEFAULT 0,
    -- Computed on the device (fantasy points); display metadata only, no facts.
    computed         INTEGER NOT NULL DEFAULT 0,
    -- Distribution family for Monte Carlo / percentile reconstruction on-device:
    -- 'negbinom' | 'binomial' | 'gamma' | 'poisson'. Null for non-projected metrics.
    dist_family      TEXT,
    zero_inflated    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE player (
    player_id    TEXT PRIMARY KEY,
    full_name    TEXT NOT NULL,
    -- Lowercased, punctuation-stripped. Indexed for the prefix-range search
    -- trick, which beats LIKE '%x%' and costs no FTS table.
    search_name  TEXT NOT NULL,
    position     TEXT,
    team         TEXT,
    pfr_player_id TEXT
);

-- The long/narrow fact table. Adding a metric is an INSERT, not a migration.
CREATE TABLE player_week_stat (
    player_id TEXT NOT NULL,
    season    INTEGER NOT NULL,
    week      INTEGER NOT NULL,
    team      TEXT,
    metric_id TEXT NOT NULL,
    value     REAL NOT NULL,
    PRIMARY KEY (player_id, season, week, metric_id)
) WITHOUT ROWID;

-- One row per player/week/component/stage: the projected mean of a raw stat component.
-- 'baseline' = post volume-cascade + shrinkage, before matchup/script/market.
-- 'final'    = fully adjusted. See PRODUCT_SPEC-adjacent design doc §3 for why both ship.
CREATE TABLE player_week_projection (
    player_id TEXT NOT NULL,
    season    INTEGER NOT NULL,
    week      INTEGER NOT NULL,
    metric_id TEXT NOT NULL,
    stage     TEXT NOT NULL,
    mean      REAL NOT NULL,
    variance  REAL NOT NULL,
    PRIMARY KEY (player_id, season, week, metric_id, stage)
) WITHOUT ROWID;

-- One row per player/week/factor: log-space attribution multiplier for one stage's
-- adjustment. Scoring-profile-independent by construction.
CREATE TABLE player_week_projection_factor (
    player_id      TEXT NOT NULL,
    season         INTEGER NOT NULL,
    week           INTEGER NOT NULL,
    factor         TEXT NOT NULL,
    log_multiplier REAL NOT NULL,
    note           TEXT,
    PRIMARY KEY (player_id, season, week, factor)
) WITHOUT ROWID;

-- Rest-of-season aggregate: summed weekly means/variances, no per-week detail.
CREATE TABLE player_ros_projection (
    player_id  TEXT NOT NULL,
    season     INTEGER NOT NULL,
    as_of_week INTEGER NOT NULL,
    metric_id  TEXT NOT NULL,
    mean       REAL NOT NULL,
    variance   REAL NOT NULL,
    PRIMARY KEY (player_id, season, as_of_week, metric_id)
) WITHOUT ROWID;

-- Frozen at projection time; never overwritten. Joined against player_week_stat
-- once actuals land to compute accuracy.
CREATE TABLE projection_snapshot (
    player_id          TEXT NOT NULL,
    season              INTEGER NOT NULL,
    week                INTEGER NOT NULL,
    metric_id           TEXT NOT NULL,
    projected_mean      REAL NOT NULL,
    projected_variance  REAL NOT NULL,
    snapshot_at         TEXT NOT NULL,
    PRIMARY KEY (player_id, season, week, metric_id, snapshot_at)
) WITHOUT ROWID;

-- Precomputed accuracy, refreshed each ETL run.
CREATE TABLE accuracy_summary (
    position  TEXT NOT NULL,
    season    INTEGER NOT NULL,
    metric_id TEXT NOT NULL,
    baseline  TEXT NOT NULL,
    sample_n  INTEGER NOT NULL,
    mae       REAL NOT NULL,
    rmse      REAL NOT NULL,
    bias      REAL NOT NULL,
    r2        REAL,
    PRIMARY KEY (position, season, metric_id, baseline)
) WITHOUT ROWID;

-- Team defense per week, straight from play-by-play.
CREATE TABLE team_week_defense (
    team              TEXT NOT NULL,
    season            INTEGER NOT NULL,
    week              INTEGER NOT NULL,
    points_allowed    REAL NOT NULL,
    yards_allowed     REAL NOT NULL,
    sacks             REAL NOT NULL,
    interceptions     REAL NOT NULL,
    fumbles_recovered REAL NOT NULL,
    defensive_tds     REAL NOT NULL,
    safeties          REAL NOT NULL,
    kick_return_tds   REAL NOT NULL,
    PRIMARY KEY (team, season, week)
) WITHOUT ROWID;

-- Weekly injury report. Not tied to `player`: injured players may have no stats.
CREATE TABLE injury_report (
    player_id TEXT NOT NULL,
    season    INTEGER NOT NULL,
    week      INTEGER NOT NULL,
    team      TEXT,
    name      TEXT,
    position  TEXT,
    status    TEXT,
    injury    TEXT,
    practice  TEXT,
    PRIMARY KEY (player_id, season, week)
) WITHOUT ROWID;

-- Component sums over a season's whole regular season (`S`) and its last 3, 4,
-- 5 and 8 weeks, built from player_week_stat by write_windows(), keyed metric
-- first so the Grid's read is a primary-key seek with no second index. window_def
-- holds each window's week bounds so a query can tell when a range matches.
CREATE TABLE player_window_stat (
    player_id TEXT NOT NULL,
    season    INTEGER NOT NULL,
    window    TEXT NOT NULL,
    metric_id TEXT NOT NULL,
    value     REAL NOT NULL,
    PRIMARY KEY (metric_id, season, window, player_id)
) WITHOUT ROWID;

CREATE TABLE window_def (
    season     INTEGER NOT NULL,
    window     TEXT NOT NULL,
    first_week INTEGER NOT NULL,
    last_week  INTEGER NOT NULL,
    PRIMARY KEY (season, window)
) WITHOUT ROWID;
"""

WINDOWS_LAST = (3, 4, 5, 8)

# Index budget matters here. The fact table is WITHOUT ROWID with a 4-column
# text primary key, so every secondary index stores that whole key as its row
# locator and ends up nearly the size of the table itself.
#
# Only one secondary index on the fact table is justified by real query shapes:
#   * Grid sorted by a metric over a week range -> (metric_id, season, week)
#   * Player detail                              -> primary key (player_id, ...)
# A standalone (season, week) index was measured at 29% of the file and serves
# no query the app issues. Don't re-add it without a query that needs it.
#
# `value` is carried in the index so the Grid's aggregation is a covering-index
# read and never touches the table. Measured on a full-season, 12-component
# query: 139 ms -> 54 ms, for +1 MB gzipped.
INDEXES = """
CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week, value);
CREATE INDEX idx_player_search          ON player (search_name);
CREATE INDEX idx_player_position        ON player (position);
"""


def create(db_path: Path) -> sqlite3.Connection:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(db_path)
    conn.executescript(DDL)
    return conn


def load_metrics(conn: sqlite3.Connection, rows: list[dict]) -> None:
    conn.executemany(
        """INSERT INTO metric (id, name, abbr, "group", definition, formula,
                               positions, tier, predicts, stability,
                               higher_is_better, decimals, hot, internal, computed,
                               dist_family, zero_inflated)
           VALUES (:id, :name, :abbr, :group, :definition, :formula,
                   :positions, :tier, :predicts, :stability,
                   :higher_is_better, :decimals, :hot, :internal, :computed,
                   :dist_family, :zero_inflated)""",
        [{**r, "higher_is_better": int(r["higher_is_better"]), "hot": int(r["hot"]),
          "internal": int(r["internal"]), "computed": int(r["computed"]),
          "zero_inflated": int(r.get("zero_inflated", False))}
         for r in rows],
    )
    conn.commit()


def load_players(conn: sqlite3.Connection, df: pl.DataFrame) -> None:
    conn.executemany(
        """INSERT OR REPLACE INTO player
           (player_id, full_name, search_name, position, team, pfr_player_id)
           VALUES (?, ?, ?, ?, ?, ?)""",
        df.select(["player_id", "full_name", "search_name",
                   "position", "team", "pfr_player_id"]).rows(),
    )
    conn.commit()


def load_facts(conn: sqlite3.Connection, long: pl.DataFrame, chunk: int = 100_000) -> int:
    rows = long.select(["player_id", "season", "week", "team", "metric_id", "value"]).rows()
    stmt = ("INSERT OR REPLACE INTO player_week_stat "
            "(player_id, season, week, team, metric_id, value) VALUES (?, ?, ?, ?, ?, ?)")
    for i in range(0, len(rows), chunk):
        conn.executemany(stmt, rows[i:i + chunk])
    conn.commit()
    return len(rows)


def _last_regular_season_week(season: int) -> int:
    # The NFL moved from 17 to 18 regular-season weeks in 2021.
    return 18 if season >= 2021 else 17


def write_windows(conn: sqlite3.Connection) -> None:
    """Fill window_def and player_window_stat from player_week_stat.

    `S` is weeks 1 through the last regular-season week played (the newest `g`
    row, as the Grid's season list reads it); `L<N>` is the N weeks ending
    there, clipped at week 1. Playoff weeks are outside every window."""
    played = conn.execute(
        "SELECT season, MAX(week) FROM player_week_stat WHERE metric_id = 'g' GROUP BY season"
    ).fetchall()
    for season, newest in played:
        last = min(newest, _last_regular_season_week(season))
        windows = [("S", 1)] + [(f"L{n}", max(1, last - n + 1)) for n in WINDOWS_LAST]
        for window, first in windows:
            conn.execute("INSERT INTO window_def VALUES (?, ?, ?, ?)", (season, window, first, last))
            conn.execute(
                """INSERT INTO player_window_stat (player_id, season, window, metric_id, value)
                   SELECT player_id, season, ?, metric_id, SUM(value) FROM player_week_stat
                   WHERE season = ? AND week BETWEEN ? AND ? GROUP BY player_id, metric_id""",
                (window, season, first, last),
            )
    conn.commit()


def finalize(conn: sqlite3.Connection, seasons: list[int],
             extra_meta: dict[str, str] | None = None) -> None:
    """Index, record provenance, then ANALYZE and VACUUM so the shipped file is
    already optimized and the planner has statistics on first query.

    `extra_meta` adds build-specific provenance, such as
    `expected_through_week:<season>`."""
    write_windows(conn)
    conn.executescript(INDEXES)
    conn.executemany(
        "INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)",
        [
            ("schema_version", str(SCHEMA_VERSION)),
            ("seasons", ",".join(str(s) for s in sorted(seasons))),
            ("source", "nflverse-data (CC BY 4.0); "
                       "ffopportunity expected points (GPL >= 3)"),
            *sorted((extra_meta or {}).items()),
        ],
    )
    conn.commit()
    conn.execute("ANALYZE")
    conn.execute("VACUUM")
    conn.commit()


def _load_chunked(conn: sqlite3.Connection, table: str, cols: list[str],
                   df: pl.DataFrame, chunk: int = 100_000, commit: bool = True) -> int:
    """`commit=False` leaves the insert(s) in the connection's open
    transaction without committing, so a caller running several
    `_load_chunked`-backed loaders as one all-or-nothing unit can
    `conn.commit()` once all of them succeed, or `conn.rollback()` if any
    of them raises."""
    placeholders = ", ".join("?" for _ in cols)
    stmt = f"INSERT OR REPLACE INTO {table} ({', '.join(cols)}) VALUES ({placeholders})"
    rows = df.select(cols).rows()
    for i in range(0, len(rows), chunk):
        conn.executemany(stmt, rows[i:i + chunk])
    if commit:
        conn.commit()
    return len(rows)


def load_team_defense(conn: sqlite3.Connection, df: pl.DataFrame) -> int:
    return _load_chunked(conn, "team_week_defense",
                          ["team", "season", "week", "points_allowed", "yards_allowed",
                           "sacks", "interceptions", "fumbles_recovered", "defensive_tds",
                           "safeties", "kick_return_tds"], df)


def load_injuries(conn: sqlite3.Connection, df: pl.DataFrame) -> int:
    return _load_chunked(conn, "injury_report",
                          ["player_id", "season", "week", "team", "name", "position",
                           "status", "injury", "practice"], df)
