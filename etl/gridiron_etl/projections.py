"""The six-layer projections pipeline: volume cascade, shrinkage, matchup,
game script, market blend, distribution assembly. See
docs/superpowers/specs/2026-09-23-projections-design.md for the architecture
and docs/research/research-prediction-models.md for the methodology.

Each stage is a pure function; `build_projections()` (added in Task 11) wires
them in order.
"""

from __future__ import annotations

import polars as pl

from . import shrinkage

# Weight given to last season's final EWMA entering week 1, per
# research-prediction-models.md §1.4: "~0.5-0.6... decaying to irrelevance by
# ~week 6."
_CARRYOVER_START = 0.55
_CARRYOVER_LAST_WEEK = 6


def carryover_weight(week: pl.Expr) -> pl.Expr:
    """Linear decay from _CARRYOVER_START at week 1 to 0 at week _CARRYOVER_LAST_WEEK."""
    span = _CARRYOVER_LAST_WEEK - 1
    raw = _CARRYOVER_START * (_CARRYOVER_LAST_WEEK - week) / span
    return pl.when(week <= _CARRYOVER_LAST_WEEK).then(raw.clip(0.0, _CARRYOVER_START)).otherwise(0.0)


def apply_cross_season_carryover(current: pl.DataFrame, prior_season_final: pl.DataFrame,
                                  signal_col: str,
                                  regime_break_col: str = "regime_break") -> pl.DataFrame:
    """Blend `f"{signal_col}_ewma"` with last season's final value, discounted
    by `carryover_weight` and zeroed entirely for a regime-break player (new
    team, new OC, new starting QB)."""
    prior_col = f"{signal_col}_ewma_final"
    ewma_col = f"{signal_col}_ewma"
    joined = current.join(prior_season_final, on="player_id", how="left")
    w = pl.when(pl.col(regime_break_col)).then(0.0).otherwise(carryover_weight(pl.col("week")))
    prior_value = pl.col(prior_col).fill_null(pl.col(ewma_col))
    return joined.with_columns(
        (w * prior_value + (1 - w) * pl.col(ewma_col)).alias(ewma_col)
    ).drop(prior_col)


def volume_cascade(weekly: pl.DataFrame) -> pl.DataFrame:
    """Stage 1: EWMA-weight role/share signals, project targets and carries
    from team-level opportunity times the player's EWMA'd share.

    `weekly` must be sorted by (player_id, season, week) — callers sort once
    for the whole pipeline (see build_projections, Task 11).
    """
    df = weekly
    for signal in ("target_share", "carry_share", "snap_share"):
        if signal in df.columns:
            df = shrinkage.apply_ewma(df, signal, shrinkage.HALF_LIVES[signal])
    for team_signal in ("team_targets", "team_carries"):
        if team_signal in df.columns:
            df = shrinkage.apply_ewma(df, team_signal, 5.0, group_cols=["team"])

    df = df.with_columns(
        (pl.col("team_targets_ewma").fill_null(0.0) * pl.col("target_share_ewma").fill_null(0.0)).alias("proj_targets"),
        (pl.col("team_carries_ewma").fill_null(0.0) * pl.col("carry_share_ewma").fill_null(0.0)).alias("proj_carries"),
    )
    return df
