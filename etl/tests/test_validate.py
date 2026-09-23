"""Validation must fail loudly on data that is wrong but inserts cleanly."""

import polars as pl
import pytest

from gridiron_etl import schema, validate
from gridiron_etl.metrics import metric_rows


def _db(tmp_path, facts: list[tuple]):
    conn = schema.create(tmp_path / "t.db")
    schema.load_metrics(conn, metric_rows())
    conn.execute("INSERT INTO player (player_id, full_name, search_name, position, team) "
                 "VALUES ('p1', 'Test Player', 'test player', 'WR', 'AAA')")
    rows = [("p1", 2025, wk, "AAA", mid, val) for wk, mid, val in facts]
    conn.executemany("INSERT INTO player_week_stat VALUES (?, ?, ?, ?, ?, ?)", rows)
    return conn


def test_computed_metric_with_facts_fails(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2), (1, "fantasy_points", 12.0)])
    problems = validate.validate(conn, strict=False)
    assert any("computed" in p for p in problems)


def test_long_td_counts_must_nest(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2),
                          (1, "receiving_tds", 1), (1, "receiving_tds_40", 1), (1, "receiving_tds_50", 2)])
    problems = validate.validate(conn, strict=False)
    assert any("50+ receiving" in p for p in problems)


def test_consistent_scoring_inputs_pass(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2), (1, "receptions", 3),
                          (1, "receiving_tds", 2), (1, "receiving_tds_40", 1),
                          (1, "receiving_first_downs", 2)])
    assert validate.validate(conn, strict=False) == []


def test_carries_eff_exceeding_carries_fails(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2),
                          (1, "carries", 1), (1, "carries_eff", 2), (1, "team_carries", 2)])
    problems = validate.validate(conn, strict=False)
    assert any("efficiency carries within carries" in p for p in problems)


def test_carries_eff_within_carries_and_team_carries_passes(tmp_path):
    conn = _db(tmp_path, [(1, "g", 1), (1, "target_share", 0.2),
                          (1, "carries", 2), (1, "carries_eff", 1), (1, "team_carries", 3)])
    assert validate.validate(conn, strict=False) == []
