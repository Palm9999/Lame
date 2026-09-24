import polars as pl
import pytest

from gridiron_etl import shrinkage
from gridiron_etl.shrinkage import SHRINKAGE_K


def test_positional_baseline_is_sample_weighted_mean():
    df = pl.DataFrame({
        "position": ["WR", "WR", "RB"],
        "catch_rate": [0.80, 0.60, 0.50],
        "targets": [10, 5, 8],
    })
    out = shrinkage.positional_baseline(df, "position", "catch_rate", "targets")
    wr = out.filter(pl.col("position") == "WR").row(0, named=True)
    # (0.80*10 + 0.60*5) / 15 = 11/15
    assert wr["catch_rate_baseline"] == pytest.approx(11 / 15, abs=1e-9)


def test_shrink_efficiency_pulls_low_n_player_toward_baseline():
    df = pl.DataFrame({
        "player_id": ["P1", "P2"], "position": ["WR", "WR"],
        "catch_rate": [1.00, 0.70], "targets": [2, 200],
    })
    out = shrinkage.shrink_efficiency(df, "catch_rate", "targets")
    p1 = out.filter(pl.col("player_id") == "P1").row(0, named=True)
    p2 = out.filter(pl.col("player_id") == "P2").row(0, named=True)
    k = SHRINKAGE_K["catch_rate"]
    # P1: n=2, heavily shrunk toward the field's baseline, must land far from 1.00.
    assert p1["catch_rate_shrunk"] < 0.85
    # P2: n=200 >> k, should stay close to its own observed rate.
    assert p2["catch_rate_shrunk"] == pytest.approx(0.70, abs=0.05)


def test_shrink_td_rate_gives_a_rookie_the_positional_baseline():
    df = pl.DataFrame({
        "player_id": ["ROOKIE"], "position": ["WR"],
        "x_receiving_tds": [0.0], "targets": [0],
    })
    baseline = pl.DataFrame({"position": ["WR"], "xtd_rate_baseline": [0.045]})
    out = shrinkage.shrink_td_rate(df, baseline)
    row = out.row(0, named=True)
    assert row["xtd_rate_shrunk"] == pytest.approx(0.045, abs=1e-9)
