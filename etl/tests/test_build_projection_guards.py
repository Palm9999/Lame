import math

import polars as pl

from gridiron_etl.build import _drop_nonfinite


def test_drop_nonfinite_removes_nan_and_inf_rows_and_keeps_finite_ones():
    df = pl.DataFrame({
        "player_id": ["P1", "P2", "P3", "P4"],
        "mean": [7.2, float("nan"), 3.0, float("inf")],
        "variance": [4.1, 1.0, float("-inf"), 2.0],
    })

    out = _drop_nonfinite(df, ["mean", "variance"], "player_week_projection")

    assert out["player_id"].to_list() == ["P1"]
    assert math.isfinite(out["mean"][0])
    assert math.isfinite(out["variance"][0])


def test_drop_nonfinite_is_a_noop_when_everything_is_finite():
    df = pl.DataFrame({
        "player_id": ["P1", "P2"],
        "log_multiplier": [0.05, -0.02],
    })

    out = _drop_nonfinite(df, ["log_multiplier"], "player_week_projection_factor")

    assert out.height == 2
