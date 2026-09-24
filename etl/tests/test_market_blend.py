import math

import polars as pl
import pytest

from gridiron_etl import projections


def test_anytime_td_lambda_matches_the_spec_formula():
    # lambda = -ln(1 - p). p=0.40 -> lambda = -ln(0.6) ~= 0.5108
    out = pl.DataFrame({"p": [0.40]}).with_columns(
        lam=projections.anytime_td_to_lambda(pl.col("p"))
    )
    assert out["lam"][0] == pytest.approx(-math.log(0.6), abs=1e-6)


def test_blend_inverse_variance_weights_the_more_certain_source_higher():
    out = pl.DataFrame({"a": [1]}).with_columns(
        blended=projections.blend_inverse_variance(
            model_mean=pl.lit(10.0), model_var=pl.lit(4.0),
            market_mean=pl.lit(6.0), market_var=pl.lit(1.0),
        )
    )
    # market has 1/4 the variance of model -> pulls the blend much closer to 6 than 10.
    assert out["blended"][0] == pytest.approx((10 / 4 + 6 / 1) / (1 / 4 + 1 / 1), abs=1e-6)
    assert out["blended"][0] < 8.0


def test_apply_market_blend_leaves_players_with_no_props_untouched():
    model = pl.DataFrame({
        "player_name": ["No Props Guy"], "mean": [50.0], "variance": [100.0],
    })
    props = pl.DataFrame({
        "player_name": [], "market": [], "line": [], "fair_prob": [],
    }, schema={"player_name": pl.String, "market": pl.String,
               "line": pl.Float64, "fair_prob": pl.Float64})
    out = projections.apply_market_blend(model, props)
    row = out.row(0, named=True)
    assert row["mean"] == pytest.approx(50.0, abs=1e-9)
    assert row["market_blended"] is False


def test_apply_market_blend_handles_mixed_players_with_and_without_props():
    # One player has a matching player_receptions prop, one doesn't -- the
    # realistic case where map_batches sees both real values and nulls in
    # the same column (nulls from the left join for the no-prop player).
    model = pl.DataFrame({
        "player_name": ["Has Prop Guy", "No Props Guy"],
        "mean": [5.0, 50.0],
        "variance": [4.0, 100.0],
    })
    props = pl.DataFrame({
        "player_name": ["Has Prop Guy"],
        "market": ["player_receptions"],
        "line": [5.5],
        "fair_prob": [0.5],
    }, schema={"player_name": pl.String, "market": pl.String,
               "line": pl.Float64, "fair_prob": pl.Float64})

    out = projections.apply_market_blend(model, props)

    rows = {r["player_name"]: r for r in out.iter_rows(named=True)}
    assert rows["No Props Guy"]["mean"] == pytest.approx(50.0, abs=1e-9)
    assert rows["No Props Guy"]["market_blended"] is False
    assert rows["Has Prop Guy"]["market_blended"] is True
    assert rows["Has Prop Guy"]["mean"] != pytest.approx(5.0, abs=1e-9)
