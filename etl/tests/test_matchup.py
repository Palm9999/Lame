import numpy as np
import polars as pl
import pytest

from gridiron_etl import projections


def test_fit_ridge_ratings_recovers_known_offsets_with_no_penalty():
    # A tiny fully-crossed 3-team round robin with a deterministic offset per
    # team and zero noise. With lam≈0 the ridge fit should recover the true
    # offense offsets up to the model's identifiability constant.
    teams = ["AAA", "BBB", "CCC"]
    true_off = {"AAA": 2.0, "BBB": -1.0, "CCC": 0.0}
    true_def = {"AAA": 0.5, "BBB": 0.0, "CCC": -0.5}
    rows = []
    for o in teams:
        for d in teams:
            if o == d:
                continue
            rows.append({"offense": o, "defense": d, "home": 0.0,
                         "y": true_off[o] + true_def[d]})
    df = pl.DataFrame(rows)
    out = projections.fit_ridge_ratings(df, "y", lam=1e-6)
    off = dict(zip(out["team"], out["off_rating"]))
    # Ratings are identified up to a constant shift between off/def; check
    # *differences* between teams, which are invariant to that shift.
    assert (off["AAA"] - off["BBB"]) == pytest.approx(3.0, abs=0.05)
    assert (off["AAA"] - off["CCC"]) == pytest.approx(2.0, abs=0.05)


def test_fit_ridge_ratings_shrinks_toward_zero_with_thin_data():
    # A single game, heavy penalty: the fitted ratings must stay small, not
    # extrapolate a whole league's ratings from one observation.
    df = pl.DataFrame({"offense": ["AAA"], "defense": ["BBB"], "home": [0.0], "y": [10.0]})
    out = projections.fit_ridge_ratings(df, "y", lam=50.0)
    assert out["off_rating"].abs().max() < 1.0


def test_matchup_multiplier_is_capped():
    expr_high = projections.matchup_multiplier(pl.lit(5.0), league_mean=0.0, cap=0.15)
    expr_low = projections.matchup_multiplier(pl.lit(-5.0), league_mean=0.0, cap=0.15)
    out = pl.DataFrame({"x": [1]}).with_columns(
        hi=expr_high, lo=expr_low,
    )
    assert out["hi"][0] == pytest.approx(1.15, abs=1e-9)
    assert out["lo"][0] == pytest.approx(0.85, abs=1e-9)
