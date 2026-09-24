import polars as pl
import pytest

from gridiron_etl import projections


def test_carryover_weight_decays_linearly_from_week_1_to_6():
    weeks = pl.DataFrame({"week": [1, 3, 6, 7]})
    out = weeks.with_columns(projections.carryover_weight(pl.col("week")).alias("w"))
    vals = out["w"].to_list()
    assert vals[0] == pytest.approx(0.55, abs=1e-9)
    assert vals[2] == pytest.approx(0.0, abs=1e-9)
    assert vals[3] == pytest.approx(0.0, abs=1e-9)
    assert 0.0 < vals[1] < 0.55


def test_cross_season_carryover_blends_prior_final_ewma():
    current = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [1],
        "target_share_ewma": [0.10], "regime_break": [False],
    })
    prior = pl.DataFrame({"player_id": ["P1"], "target_share_ewma_final": [0.30]})
    out = projections.apply_cross_season_carryover(current, prior, "target_share")
    # week 1 weight = 0.55: 0.55*0.30 + 0.45*0.10 = 0.165 + 0.045 = 0.21
    assert out["target_share_ewma"][0] == pytest.approx(0.21, abs=1e-9)


def test_regime_break_zeroes_the_carryover():
    current = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [1],
        "target_share_ewma": [0.10], "regime_break": [True],
    })
    prior = pl.DataFrame({"player_id": ["P1"], "target_share_ewma_final": [0.30]})
    out = projections.apply_cross_season_carryover(current, prior, "target_share")
    assert out["target_share_ewma"][0] == pytest.approx(0.10, abs=1e-9)


def test_volume_cascade_produces_projected_targets():
    weekly = pl.DataFrame({
        "player_id": ["P1", "P1"], "season": [2026, 2026], "week": [1, 2],
        "team": ["AAA", "AAA"],
        "target_share": [0.20, 0.20], "carry_share": [0.0, 0.0],
        "snap_share": [0.80, 0.80],
        "team_targets": [30, 30], "team_carries": [25, 25],
    })
    out = projections.volume_cascade(weekly)
    row = out.filter(pl.col("week") == 2).row(0, named=True)
    assert row["proj_targets"] == pytest.approx(0.20 * 30, abs=0.5)


def test_volume_cascade_null_guard_prevents_null_propagation():
    """Verify that null EWMA values (e.g. rookie week 1) don't propagate into proj_targets."""
    # This simulates a player with no prior signal (EWMA will be null in week 1)
    # The fill_null(0.0) guard should ensure proj_targets is 0, not null.
    weekly = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [1],
        "team": ["AAA"],
        "target_share": [None], "carry_share": [None],
        "snap_share": [0.50],
        "team_targets": [30], "team_carries": [25],
    })
    out = projections.volume_cascade(weekly)
    row = out.row(0, named=True)
    # After EWMA, target_share_ewma could be null (leading row with null input)
    # The fill_null(0.0) guard should make proj_targets = 0, not null
    assert row["proj_targets"] == pytest.approx(0.0, abs=1e-9)
    assert row["proj_carries"] == pytest.approx(0.0, abs=1e-9)
