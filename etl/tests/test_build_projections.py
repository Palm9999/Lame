import polars as pl
import pytest

from gridiron_etl import projections


def _minimal_weekly() -> pl.DataFrame:
    return pl.DataFrame({
        "player_id": ["P1", "P1"], "player_name": ["P One", "P One"],
        "position": ["WR", "WR"], "season": [2026, 2026], "week": [1, 2],
        "team": ["AAA", "AAA"], "opponent": ["BBB", "BBB"],
        "target_share": [0.20, 0.22], "carry_share": [0.0, 0.0],
        "snap_share": [0.75, 0.78],
        "team_targets": [30, 32], "team_carries": [25, 24],
        "x_receiving_tds": [0.3, 0.4], "targets": [6, 7],
        "regime_break": [False, False],
        "total": [47.0, 47.0], "spread_home": [-3.0, -3.0], "home": [1.0, 1.0],
        "wind": [5.0, 5.0], "is_outdoor": [True, True],
    })


def _empty_prior_season_final() -> pl.DataFrame:
    return pl.DataFrame(
        {"player_id": [], "target_share_ewma_final": [],
         "carry_share_ewma_final": [], "snap_share_ewma_final": []},
        schema={"player_id": pl.String, "target_share_ewma_final": pl.Float64,
                "carry_share_ewma_final": pl.Float64,
                "snap_share_ewma_final": pl.Float64},
    )


def _context(prior_season_final: pl.DataFrame) -> dict:
    return {
        "odds_props": pl.DataFrame(
            {"player_name": [], "market": [], "line": [], "fair_prob": []},
            schema={"player_name": pl.String, "market": pl.String,
                    "line": pl.Float64, "fair_prob": pl.Float64},
        ),
        "prior_season_final": prior_season_final,
        "xtd_baseline": pl.DataFrame({"position": ["WR"], "xtd_rate_baseline": [0.05]}),
    }


def test_build_projections_produces_baseline_and_final_stage_rows():
    weekly = _minimal_weekly()
    proj, factors, ros = projections.build_projections(
        weekly, context=_context(_empty_prior_season_final())
    )

    stages = proj.filter(pl.col("player_id") == "P1")["stage"].unique().sort().to_list()
    assert stages == ["baseline", "final"]
    assert proj.height > 0
    assert set(proj.columns) == {"player_id", "season", "week", "metric_id", "stage",
                                  "mean", "variance"}
    assert set(factors.columns) == {"player_id", "season", "week", "factor",
                                     "log_multiplier", "note"}
    assert set(ros.columns) == {"player_id", "season", "as_of_week", "metric_id",
                                 "mean", "variance"}


def test_build_projections_recomputes_proj_targets_from_carryover_adjusted_ewma():
    """Regression test for the volume_cascade/carryover ordering bug: without
    recomputing proj_targets from the carryover-adjusted *_ewma columns after
    the carryover loop, a prior-season signal wildly different from the
    player's current-season share would have zero effect on the baseline
    `targets` projection. This proves cross-season carryover actually reaches
    the output, not just the intermediate `*_ewma` columns."""
    weekly = _minimal_weekly()

    no_carryover_proj, _, _ = projections.build_projections(
        weekly, context=_context(_empty_prior_season_final())
    )

    prior = pl.DataFrame({
        "player_id": ["P1"],
        "target_share_ewma_final": [0.90],  # far above the ~0.20 current-season share
        "carry_share_ewma_final": [0.0],
        "snap_share_ewma_final": [0.75],
    })
    with_carryover_proj, _, _ = projections.build_projections(
        weekly, context=_context(prior)
    )

    def baseline_targets_week1(proj: pl.DataFrame) -> float:
        row = proj.filter(
            (pl.col("player_id") == "P1") & (pl.col("week") == 1)
            & (pl.col("stage") == "baseline") & (pl.col("metric_id") == "targets")
        )
        return row["mean"][0]

    no_carryover = baseline_targets_week1(no_carryover_proj)
    with_carryover = baseline_targets_week1(with_carryover_proj)

    # Week 1 carryover weight is 0.55 (see carryover_weight), so a prior
    # target_share_ewma_final of 0.90 must pull proj_targets meaningfully
    # above the no-carryover baseline. If proj_targets were computed before
    # the carryover loop runs (the bug), these two values would be identical.
    assert with_carryover > no_carryover * 1.5
