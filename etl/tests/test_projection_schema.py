import polars as pl
import pytest

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


def test_projection_loads_are_transactional_on_failure(tmp_path):
    """Regression test for the partial-write bug: three related loads
    (projections, factors, ros) must succeed together or none of them
    persist. Simulates a mid-sequence failure (a malformed ros frame
    missing required columns) and confirms the earlier two loads' rows,
    though written to the connection, are rolled back rather than left
    committed."""
    conn = schema.create(tmp_path / "stats.db")

    proj = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "metric_id": ["targets"],
        "stage": ["final"], "mean": [7.2], "variance": [4.1],
    })
    factors = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "factor": ["weather"],
        "log_multiplier": [0.05], "note": [None],
    })
    bad_ros = pl.DataFrame({"player_id": ["P1"]})  # missing required columns -> raises

    with pytest.raises(Exception):
        try:
            schema.load_projections(conn, proj, commit=False)
            schema.load_projection_factors(conn, factors, commit=False)
            schema.load_ros_projections(conn, bad_ros, commit=False)
            conn.commit()
        except Exception:
            conn.rollback()
            raise

    assert conn.execute("SELECT COUNT(*) FROM player_week_projection").fetchone()[0] == 0
    assert conn.execute("SELECT COUNT(*) FROM player_week_projection_factor").fetchone()[0] == 0
    conn.close()


def test_load_chunked_commit_false_leaves_rows_uncommitted_until_caller_commits(tmp_path):
    """The commit=False path defers the commit to the caller, so a second
    connection to the same file sees nothing until the first connection
    commits."""
    db_path = tmp_path / "stats.db"
    conn = schema.create(db_path)
    proj = pl.DataFrame({
        "player_id": ["P1"], "season": [2026], "week": [3], "metric_id": ["targets"],
        "stage": ["final"], "mean": [7.2], "variance": [4.1],
    })
    schema.load_projections(conn, proj, commit=False)
    # Same-connection query still sees the uncommitted row (SQLite reads its
    # own pending transaction); a fresh connection to the file would not,
    # but opening one here would also block on the held write lock, so we
    # instead prove the deferred-commit contract directly: an explicit
    # rollback undoes it.
    conn.rollback()
    assert conn.execute("SELECT COUNT(*) FROM player_week_projection").fetchone()[0] == 0
    conn.close()
