import polars as pl
import pytest

from gridiron_etl import projections


def test_implied_totals_match_the_worked_example():
    # total 47, home favored by 6 -> home 26.5, away 20.5 (spec worked example).
    df = pl.DataFrame({"total": [47.0], "spread_home": [-6.0]})
    out = projections.implied_totals(df)
    row = out.row(0, named=True)
    assert row["implied_total_home"] == pytest.approx(26.5, abs=1e-9)
    assert row["implied_total_away"] == pytest.approx(20.5, abs=1e-9)


def test_pass_rate_shift_is_proportional_to_spread():
    out = pl.DataFrame({"spread_team": [10.0, -10.0, 0.0]}).with_columns(
        shift=projections.pass_rate_shift(pl.col("spread_team"), kappa=0.6)
    )
    vals = out["shift"].to_list()
    assert vals[0] == pytest.approx(6.0, abs=1e-9)   # big underdog -> passes more
    assert vals[1] == pytest.approx(-6.0, abs=1e-9)  # big favorite -> passes less
    assert vals[2] == pytest.approx(0.0, abs=1e-9)


def test_wind_multiplier_is_neutral_below_12mph():
    out = pl.DataFrame({"wind": [5.0], "outdoor": [True]}).with_columns(
        m=projections.wind_multiplier(pl.col("wind"), pl.col("outdoor"))
    )
    assert out["m"][0] == pytest.approx(1.0, abs=1e-9)


def test_wind_multiplier_drops_above_12mph_when_outdoor():
    out = pl.DataFrame({"wind": [20.0], "outdoor": [True]}).with_columns(
        m=projections.wind_multiplier(pl.col("wind"), pl.col("outdoor"))
    )
    assert out["m"][0] < 1.0
    assert out["m"][0] >= 0.80  # clamp floor from the research doc


def test_wind_multiplier_is_gated_off_for_a_dome():
    # A high wind value on a dome game must be fully zeroed out (multiplier stays 1.0),
    # per the design spec's dome-hard-gate and this plan's Review Focus.
    out = pl.DataFrame({"wind": [30.0], "outdoor": [False]}).with_columns(
        m=projections.wind_multiplier(pl.col("wind"), pl.col("outdoor"))
    )
    assert out["m"][0] == pytest.approx(1.0, abs=1e-9)
