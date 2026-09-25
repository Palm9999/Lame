import polars as pl
import pytest

from gridiron_etl import projections


def test_xtd_baseline_is_positional_rate():
    history = pl.DataFrame({
        "position": ["WR", "WR", "RB"],
        "x_receiving_tds": [4.0, 6.0, 1.0],
        "targets": [80, 120, 10],
    })
    out = projections.xtd_baseline(history, "x_receiving_tds", "targets")
    wr = out.filter(pl.col("position") == "WR").row(0, named=True)
    assert wr["xtd_rate_baseline"] == pytest.approx(10 / 200, abs=1e-9)


def test_project_xtd_shrinks_low_sample_player_to_baseline():
    df = pl.DataFrame({
        "player_id": ["ROOKIE"], "position": ["WR"],
        "x_receiving_tds": [2.0], "targets": [3], "proj_targets": [8.0],
    })
    baseline = pl.DataFrame({"position": ["WR"], "xtd_rate_baseline": [0.05]})
    out = projections.project_xtd(df, baseline, "x_receiving_tds", "targets")
    row = out.row(0, named=True)
    # n=3 << k=200: shrunk rate must sit far closer to 0.05 than to 2/3.
    assert row["xtd_rate_shrunk"] < 0.10
    assert row["proj_tds"] == pytest.approx(row["xtd_rate_shrunk"] * 8.0, abs=1e-9)


def test_project_xtd_no_baseline_match_degrades_to_zero():
    df = pl.DataFrame({
        "player_id": ["TE"], "position": ["TE"],
        "x_receiving_tds": [1.0], "targets": [5], "proj_targets": [10.0],
    })
    baseline = pl.DataFrame({"position": ["WR"], "xtd_rate_baseline": [0.05]})
    out = projections.project_xtd(df, baseline, "x_receiving_tds", "targets")
    row = out.row(0, named=True)
    # No baseline row for TE position: xtd_rate_shrunk is null before fill,
    # but fill_null(0.0) makes proj_tds = 0.0 * 10.0 = 0.0 (not null).
    assert row["proj_tds"] == 0.0
    assert row["proj_tds"] is not None
