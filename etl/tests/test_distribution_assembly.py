import polars as pl
import pytest

from gridiron_etl import projections


def test_component_variance_is_sublinear_in_mean():
    out = pl.DataFrame({"mu": [10.0, 40.0]}).with_columns(
        var=projections.component_variance(pl.col("mu"), cv=0.6, b=0.75)
    )
    sigma = out["var"].sqrt().to_list()
    cv_at_10 = sigma[0] / 10.0
    cv_at_40 = sigma[1] / 40.0
    # Sub-linear sigma means CV shrinks as volume grows — bigger projections
    # are proportionally safer, per research doc §2.1.
    assert cv_at_40 < cv_at_10


def test_rest_of_season_sums_weekly_mean_and_variance():
    weekly = pl.DataFrame({
        "player_id": ["P1", "P1", "P2"],
        "metric_id": ["targets", "targets", "targets"],
        "week": [4, 5, 4],
        "mean": [7.0, 6.0, 5.0],
        "variance": [4.0, 3.0, 2.0],
    })
    out = projections.rest_of_season(weekly)
    p1 = out.filter(pl.col("player_id") == "P1").row(0, named=True)
    assert p1["mean"] == pytest.approx(13.0, abs=1e-9)
    assert p1["variance"] == pytest.approx(7.0, abs=1e-9)


def test_kicker_projection_scales_with_implied_total_and_wind():
    calm = projections.kicker_projection(
        pl.DataFrame({"team_implied_total": [24.0], "wind_mult": [1.0]})
    )
    windy = projections.kicker_projection(
        pl.DataFrame({"team_implied_total": [24.0], "wind_mult": [0.85]})
    )
    assert windy["mean"][0] < calm["mean"][0]


def test_dst_projection_rewards_a_weak_opponent_offense():
    weak_opp = projections.dst_projection(
        pl.DataFrame({"opponent_off_rating": [-3.0], "pressure_rate": [0.30]})
    )
    strong_opp = projections.dst_projection(
        pl.DataFrame({"opponent_off_rating": [3.0], "pressure_rate": [0.30]})
    )
    assert weak_opp["mean"][0] > strong_opp["mean"][0]
