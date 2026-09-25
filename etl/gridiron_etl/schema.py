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

SCHEMA_VERSION = 4

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
"""

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


def finalize(conn: sqlite3.Connection, seasons: list[int],
             extra_meta: dict[str, str] | None = None) -> None:
    """Index, record provenance, then ANALYZE and VACUUM so the shipped file is
    already optimized and the planner has statistics on first query.

    `extra_meta` adds build-specific provenance, such as
    `expected_through_week:<season>`."""
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
    `_load_chunked`-backed loaders as one all-or-nothing unit (see
    build.py's projections stage) can `conn.commit()` once all of them
    succeed, or `conn.rollback()` if any of them raises — undoing every
    chunk already written by this call and any earlier sibling call in the
    same transaction, not just this one's own rows."""
    placeholders = ", ".join("?" for _ in cols)
    stmt = f"INSERT OR REPLACE INTO {table} ({', '.join(cols)}) VALUES ({placeholders})"
    rows = df.select(cols).rows()
    for i in range(0, len(rows), chunk):
        conn.executemany(stmt, rows[i:i + chunk])
    if commit:
        conn.commit()
    return len(rows)


def load_projections(conn: sqlite3.Connection, df: pl.DataFrame, commit: bool = True) -> int:
    return _load_chunked(conn, "player_week_projection",
                          ["player_id", "season", "week", "metric_id", "stage",
                           "mean", "variance"], df, commit=commit)


def load_projection_factors(conn: sqlite3.Connection, df: pl.DataFrame, commit: bool = True) -> int:
    return _load_chunked(conn, "player_week_projection_factor",
                          ["player_id", "season", "week", "factor",
                           "log_multiplier", "note"], df, commit=commit)


def load_ros_projections(conn: sqlite3.Connection, df: pl.DataFrame, commit: bool = True) -> int:
    return _load_chunked(conn, "player_ros_projection",
                          ["player_id", "season", "as_of_week", "metric_id",
                           "mean", "variance"], df, commit=commit)


def load_snapshots(conn: sqlite3.Connection, df: pl.DataFrame) -> int:
    return _load_chunked(conn, "projection_snapshot",
                          ["player_id", "season", "week", "metric_id",
                           "projected_mean", "projected_variance", "snapshot_at"], df)


def load_accuracy_summary(conn: sqlite3.Connection, df: pl.DataFrame) -> int:
    return _load_chunked(conn, "accuracy_summary",
                          ["position", "season", "metric_id", "baseline", "sample_n",
                           "mae", "rmse", "bias", "r2"], df)
