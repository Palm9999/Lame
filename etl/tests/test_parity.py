"""The parity script is the gate between the Python ETL and the Kotlin port."""

import sqlite3

from tools.parity import compare


def _db(path, value: float, team: str = "AAA"):
    conn = sqlite3.connect(path)
    conn.executescript(
        """
        CREATE TABLE schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE player_week_stat (player_id TEXT, season INTEGER, week INTEGER, team TEXT,
                                       metric_id TEXT, value REAL);
        CREATE TABLE player (player_id TEXT, full_name TEXT, search_name TEXT, position TEXT,
                             team TEXT, pfr_player_id TEXT);
        CREATE TABLE metric (id TEXT, name TEXT, abbr TEXT, "group" TEXT, definition TEXT,
                             formula TEXT, positions TEXT, tier TEXT, predicts TEXT, stability REAL,
                             higher_is_better INTEGER, decimals INTEGER, hot INTEGER, internal INTEGER,
                             computed INTEGER, dist_family TEXT, zero_inflated INTEGER);
        CREATE TABLE team_week_defense (team TEXT, season INTEGER, week INTEGER, points_allowed REAL,
                                        yards_allowed REAL, sacks REAL, interceptions REAL,
                                        fumbles_recovered REAL, defensive_tds REAL);
        CREATE TABLE injury_report (player_id TEXT, season INTEGER, week INTEGER, team TEXT, name TEXT,
                                    position TEXT, status TEXT, injury TEXT, practice TEXT);
        INSERT INTO schema_meta VALUES ('seasons', '2025');
        INSERT INTO player VALUES ('p1', 'P One', 'p one', 'WR', 'AAA', NULL);
        """
    )
    conn.execute("INSERT INTO player_week_stat VALUES ('p1', 2025, 1, ?, 'target_share', ?)", (team, value))
    conn.commit()
    conn.close()


def test_identical_databases_pass(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25 + 1e-12)
    assert compare(str(tmp_path / "a.db"), str(tmp_path / "b.db")) == []


def test_a_differing_value_is_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.26)
    problems = compare(str(tmp_path / "a.db"), str(tmp_path / "b.db"))
    assert any("player_week_stat" in p and "differ" in p for p in problems)


def test_a_differing_team_is_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25, team="BBB")
    assert compare(str(tmp_path / "a.db"), str(tmp_path / "b.db")) != []


def test_missing_rows_are_reported(tmp_path):
    _db(tmp_path / "a.db", 0.25)
    _db(tmp_path / "b.db", 0.25)
    conn = sqlite3.connect(tmp_path / "b.db")
    conn.execute("DELETE FROM player_week_stat")
    conn.commit()
    conn.close()
    problems = compare(str(tmp_path / "a.db"), str(tmp_path / "b.db"))
    assert any("only in Python" in p for p in problems)
