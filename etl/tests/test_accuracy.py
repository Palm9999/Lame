import polars as pl
import pytest

from gridiron_etl import projections


def test_snapshot_projections_reshapes_final_stage_rows():
    proj = pl.DataFrame({
        "player_id": ["P1", "P1"], "season": [2026, 2026], "week": [3, 3],
        "metric_id": ["targets", "receiving_tds"], "stage": ["final", "final"],
        "mean": [7.2, 0.3], "variance": [4.0, 0.1],
    })
    out = projections.snapshot_projections(proj, "2026-09-20T12:00:00Z")
    assert out.height == 2
    row = out.row(0, named=True)
    assert row["projected_mean"] == 7.2
    assert row["snapshot_at"] == "2026-09-20T12:00:00Z"


def test_compute_accuracy_matches_hand_computed_mae():
    snapshots = pl.DataFrame({
        "player_id": ["P1", "P2"], "season": [2026, 2026], "week": [3, 3],
        "metric_id": ["targets", "targets"],
        "projected_mean": [7.0, 5.0], "projected_variance": [4.0, 4.0],
        "snapshot_at": ["t1", "t1"],
    })
    actuals = pl.DataFrame({
        "player_id": ["P1", "P2"], "season": [2026, 2026], "week": [3, 3],
        "metric_id": ["targets", "targets"], "value": [9.0, 5.0],
    })
    positions = pl.DataFrame({"player_id": ["P1", "P2"], "position": ["WR", "WR"]})
    out = projections.compute_accuracy(snapshots, actuals, positions)
    row = out.row(0, named=True)
    # |9-7| = 2, |5-5| = 0 -> MAE = 1.0
    assert row["mae"] == pytest.approx(1.0, abs=1e-9)
    assert row["baseline"] == "model"
    assert row["sample_n"] == 2
