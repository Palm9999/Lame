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

SCHEMA_VERSION = 1

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
    hot              INTEGER NOT NULL DEFAULT 0
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
INDEXES = """
CREATE INDEX idx_pws_metric_season_week ON player_week_stat (metric_id, season, week);
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
                               higher_is_better, decimals, hot)
           VALUES (:id, :name, :abbr, :group, :definition, :formula,
                   :positions, :tier, :predicts, :stability,
                   :higher_is_better, :decimals, :hot)""",
        [{**r, "higher_is_better": int(r["higher_is_better"]), "hot": int(r["hot"])}
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


def finalize(conn: sqlite3.Connection, seasons: list[int]) -> None:
    """Index, record provenance, then ANALYZE and VACUUM so the shipped file is
    already optimized and the planner has statistics on first query."""
    conn.executescript(INDEXES)
    conn.executemany(
        "INSERT OR REPLACE INTO schema_meta (key, value) VALUES (?, ?)",
        [
            ("schema_version", str(SCHEMA_VERSION)),
            ("seasons", ",".join(str(s) for s in sorted(seasons))),
            ("source", "nflverse-data (CC BY 4.0)"),
        ],
    )
    conn.commit()
    conn.execute("ANALYZE")
    conn.execute("VACUUM")
    conn.commit()
