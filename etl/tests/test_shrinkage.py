import polars as pl
import pytest

from gridiron_etl import shrinkage


def test_ewma_matches_hand_computed_recurrence():
    half_life = 4.5
    lam = 1 - 2 ** (-1 / half_life)
    values = [0.20, 0.30, 0.25]
    expected = [values[0]]
    for v in values[1:]:
        expected.append(lam * v + (1 - lam) * expected[-1])

    df = pl.DataFrame({
        "player_id": ["P1", "P1", "P1"],
        "season": [2026, 2026, 2026],
        "week": [1, 2, 3],
        "target_share": values,
    })
    out = shrinkage.apply_ewma(df, "target_share", half_life)
    assert out["target_share_ewma"].to_list() == pytest.approx(expected, abs=1e-9)


def test_ewma_is_independent_per_player():
    df = pl.DataFrame({
        "player_id": ["P1", "P1", "P2", "P2"],
        "season": [2026, 2026, 2026, 2026],
        "week": [1, 2, 1, 2],
        "target_share": [0.10, 0.40, 0.30, 0.30],
    })
    out = shrinkage.apply_ewma(df, "target_share", 4.5)
    p2 = out.filter(pl.col("player_id") == "P2")["target_share_ewma"].to_list()
    # P2's constant 0.30 signal must stay 0.30 regardless of P1's swing.
    assert p2 == pytest.approx([0.30, 0.30], abs=1e-9)


def test_ewma_ignores_nulls_instead_of_propagating_them():
    df = pl.DataFrame({
        "player_id": ["P1", "P1", "P1"],
        "season": [2026, 2026, 2026],
        "week": [1, 2, 3],
        "target_share": [0.20, None, 0.30],
    })
    out = shrinkage.apply_ewma(df, "target_share", 4.5)
    assert out["target_share_ewma"].null_count() == 0
