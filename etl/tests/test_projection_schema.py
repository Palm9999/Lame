import polars as pl

from gridiron_etl import schema


def test_new_tables_exist_and_round_trip(tmp_path):
    conn = schema.create(tmp_path / "stats.db")
    schema.load_metrics(conn, [
        {"id": "targets", "name": "Targets", "abbr": "TGT", "group": "volume",
         "definition": "d", "formula": None, "positions": "WR,TE,RB", "tier": "A",
         "predicts": None, "stability": 0.7, "higher_is_better": True, "decimals": 0,
         "hot": True, "internal": False, "computed": False,
         "dist_family": "negbinom", "zero_inflated": False},
    ])

    proj = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "metric_id": ["targets"],
        "stage": ["final"], "mean": [7.2], "variance": [4.1],
    })
    factors = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "factor": ["matchup"],
        "log_multiplier": [0.05], "note": ["28th vs slot WRs"],
    })
    ros = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "as_of_week": [3], "metric_id": ["targets"],
        "mean": [98.0], "variance": [30.0],
    })
    snap = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "metric_id": ["targets"],
        "projected_mean": [7.2], "projected_variance": [4.1],
        "snapshot_at": ["2026-09-20T12:00:00Z"],
    })
    acc = pl.DataFrame({
        "position": ["WR"], "season": [2026], "metric_id": ["fantasy_points"],
        "baseline": ["model"], "sample_n": [40], "mae": [4.9], "rmse": [6.1],
        "bias": [-0.1], "r2": [0.18],
    })

    assert schema.load_projections(conn, proj) == 1
    assert schema.load_projection_factors(conn, factors) == 1
    assert schema.load_ros_projections(conn, ros) == 1
    assert schema.load_snapshots(conn, snap) == 1
    assert schema.load_accuracy_summary(conn, acc) == 1

    row = conn.execute(
        "SELECT dist_family, zero_inflated FROM metric WHERE id = 'targets'"
    ).fetchone()
    assert row == ("negbinom", 0)

    row = conn.execute(
        "SELECT mean, variance FROM player_week_projection "
        "WHERE player_id='P1' AND metric_id='targets' AND stage='final'"
    ).fetchone()
    assert row == (7.2, 4.1)
    conn.close()
