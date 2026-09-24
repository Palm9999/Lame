"""The pipeline-level sanity check: build projections, snapshot the final
stage, and score the snapshot against the real outcome. Uses a small
synthetic multi-week frame (not real historical data) to confirm the
*mechanism* — build_projections -> snapshot_projections -> compute_accuracy
— composes end to end and actually exercises the join/scoring arithmetic
against real build_projections output.

This is a self-referential check, not a true walk-forward backtest: week
4's projection is built using week 4's own row as part of the input frame
(build_projections needs at least one row per week it will emit a
"final"-stage projection for), so the scored week is not strictly held out
of training. A true forward-looking backtest — where the training data
never includes the week being scored, run against real historical data
pulled from the built stats.db — is a separate, data-dependent follow-up
(see the plan's Testing Strategy / this task's post-plan note)."""
import math

import polars as pl

from gridiron_etl import projections


def test_walk_forward_snapshot_and_accuracy_round_trip():
    # Two WR players with different volume levels, so week 4's actual
    # `targets` values differ across the group — a single-player group would
    # always have a total sum of squares of 0, forcing r2 to None by
    # construction (see the divide-by-zero guard in compute_accuracy) rather
    # than actually exercising the R^2 arithmetic.
    p1 = pl.DataFrame({
        "player_id": ["P1"] * 4, "player_name": ["P One"] * 4,
        "position": ["WR"] * 4, "season": [2026] * 4, "week": [1, 2, 3, 4],
        "team": ["AAA"] * 4, "opponent": ["BBB"] * 4,
        "target_share": [0.20, 0.21, 0.19, 0.22], "carry_share": [0.0] * 4,
        "snap_share": [0.75, 0.76, 0.74, 0.77],
        "team_targets": [30, 31, 29, 32], "team_carries": [25, 24, 26, 25],
        "x_receiving_tds": [0.3, 0.3, 0.3, 0.3], "targets": [6, 6, 6, 7],
        "regime_break": [False] * 4,
        "total": [47.0] * 4, "spread_home": [-3.0] * 4, "home": [1.0] * 4,
        "wind": [5.0] * 4, "is_outdoor": [True] * 4,
    })
    p2 = pl.DataFrame({
        "player_id": ["P2"] * 4, "player_name": ["P Two"] * 4,
        "position": ["WR"] * 4, "season": [2026] * 4, "week": [1, 2, 3, 4],
        "team": ["AAA"] * 4, "opponent": ["BBB"] * 4,
        "target_share": [0.10, 0.11, 0.09, 0.12], "carry_share": [0.0] * 4,
        "snap_share": [0.55, 0.56, 0.54, 0.57],
        "team_targets": [30, 31, 29, 32], "team_carries": [25, 24, 26, 25],
        "x_receiving_tds": [0.1, 0.1, 0.1, 0.1], "targets": [3, 3, 3, 3],
        "regime_break": [False] * 4,
        "total": [47.0] * 4, "spread_home": [-3.0] * 4, "home": [1.0] * 4,
        "wind": [5.0] * 4, "is_outdoor": [True] * 4,
    })
    weekly = pl.concat([p1, p2], how="vertical")
    context = {
        "odds_props": pl.DataFrame(
            {"player_name": [], "market": [], "line": [], "fair_prob": []},
            schema={"player_name": pl.String, "market": pl.String,
                    "line": pl.Float64, "fair_prob": pl.Float64}),
        "prior_season_final": pl.DataFrame(
            {"player_id": [], "target_share_ewma_final": [],
             "carry_share_ewma_final": [], "snap_share_ewma_final": []},
            schema={"player_id": pl.String, "target_share_ewma_final": pl.Float64,
                    "carry_share_ewma_final": pl.Float64,
                    "snap_share_ewma_final": pl.Float64}),
        "xtd_baseline": pl.DataFrame({"position": ["WR"], "xtd_rate_baseline": [0.05]}),
    }

    # Build projections on the full frame (weeks 1-4), snapshot the final
    # stage, then score the snapshot against week 4's real actual — this
    # exercises compute_accuracy's join + scoring arithmetic against real
    # build_projections output with a non-empty, meaningful result.
    proj, _, _ = projections.build_projections(weekly, context)
    snapshots = projections.snapshot_projections(proj, "2026-09-20T00:00:00Z")
    snapshots_week4 = snapshots.filter(pl.col("week") == 4)

    actual_week4 = weekly.filter(pl.col("week") == 4).select(
        "player_id", "season", "week",
        pl.lit("targets").alias("metric_id"),
        pl.col("targets").cast(pl.Float64).alias("value"),
    )
    positions = weekly.select("player_id", "position").unique()

    acc = projections.compute_accuracy(snapshots_week4, actual_week4, positions)
    assert acc.height > 0
    assert set(acc.columns) == {"position", "season", "metric_id", "baseline",
                                 "sample_n", "mae", "rmse", "bias", "r2"}
    row = acc.row(0, named=True)
    assert row["sample_n"] == 2
    assert math.isfinite(row["mae"])
    assert math.isfinite(row["rmse"])
    assert math.isfinite(row["bias"])
    assert row["r2"] is not None
    assert math.isfinite(row["r2"])
