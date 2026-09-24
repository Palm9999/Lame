"""Time-weighting (EWMA) and small-sample shrinkage (James-Stein), shared by
every stage of the projections pipeline that needs either.

Half-lives and shrinkage `k`s are ETL constants — they change slowly (fit
offline from historical split-half reliability) and are not re-fit live per
build, matching research-prediction-models.md §1.3-1.4.
"""

from __future__ import annotations

import polars as pl

# Games until a signal is 50% weighted toward its most recent value. Role
# changes fast; efficiency is mostly noise and should barely move; TD rate
# isn't EWMA'd at all (see shrink_td_rate in the shrinkage stage instead).
HALF_LIVES: dict[str, float] = {
    "snap_share": 2.5,
    "target_share": 4.5,
    "carry_share": 4.5,
    "adot": 10.0,
    "catch_rate": 10.0,
    "racr": 10.0,
    "rush_success_rate": 10.0,
    "cpoe": 10.0,
}

# n* = k: the sample size at which a player's own data carries 50% of the
# weight. Small for volume/role signals (they stabilize fast), large for
# efficiency, very large for TD rate (research doc §1.3: "TD rate does not
# stabilize within a season at all").
SHRINKAGE_K: dict[str, float] = {
    "target_share": 5.0,
    "carry_share": 5.0,
    "catch_rate": 15.0,
    "rush_success_rate": 20.0,
    "cpoe": 15.0,
    "td_rate": 200.0,
    "int_rate": 150.0,
}


def apply_ewma(df: pl.DataFrame, signal_col: str, half_life: float,
                group_cols: list[str] | None = None) -> pl.DataFrame:
    """Time-weight `signal_col` with an EWMA of the given half-life in games.

    `df` must already be sorted by `group_cols + ["season", "week"]` — this
    function does not sort, so callers control ordering once for the whole
    pipeline rather than paying for a re-sort per signal.
    """
    group_cols = group_cols or ["player_id"]
    return df.with_columns(
        pl.col(signal_col)
        .ewm_mean(half_life=half_life, adjust=False, ignore_nulls=True)
        .forward_fill()
        .over(group_cols)
        .alias(f"{signal_col}_ewma")
    )


def shrink(observed: pl.Expr, n: pl.Expr, baseline: pl.Expr, k: float) -> pl.Expr:
    """James-Stein blend: w = n / (n + k); shrunk = w*observed + (1-w)*baseline.

    n=0 (a rookie, a just-signed player) yields w=0, i.e. the baseline alone —
    never a divide-by-zero, never a null.
    """
    w = n / (n + k)
    return w * observed + (1 - w) * baseline
