"""Next Gen Stats: nflverse's weekly tracking averages, one row per player-week.

Twin of core/ingest's Ngs.kt; the parity job holds the two to identical facts.

NGS publishes averages, so each is stored as average x weight beside its
weight (NGS's own attempts, carries, targets or receptions). A range then
recomputes as sum(avg x weight) / sum(weight), never a mean of weekly means.
The weekly average is stored too, under the metric's own id. A missing
average or a weight of 0 stores nothing: absent means no NGS data.
"""

from __future__ import annotations

import logging
from pathlib import Path

import polars as pl

from . import sources

log = logging.getLogger(__name__)

KEY = ["season", "week", "team_abbr", "player_gsis_id"]

# (weight column, weight component, {average column: (component = average x weight, visible id = the average)},
#  {total column: (component stored as is, visible id = total per weight)})
PASSING = ("attempts", "ngs_attempts",
           {"avg_time_to_throw": ("ngs_ttt_w", "ngs_time_to_throw"),
            "aggressiveness": ("ngs_aggr_w", "ngs_aggressiveness"),
            "avg_intended_air_yards": ("ngs_iay_w", "ngs_intended_air_yards")}, {})
RUSHING = ("rush_attempts", "ngs_carries",
           {"efficiency": ("ngs_eff_w", "ngs_rush_efficiency"),
            "percent_attempts_gte_eight_defenders": ("ngs_box_w", "ngs_stacked_box_pct")},
           {"rush_yards_over_expected": ("ngs_ryoe", "ngs_ryoe_per_att")})
# Separation and cushion are per target, YAC over expected per reception.
RECEIVING_TARGETS = ("targets", "ngs_targets",
                     {"avg_cushion": ("ngs_cush_w", "ngs_cushion"),
                      "avg_separation": ("ngs_sep_w", "ngs_separation")}, {})
RECEIVING_RECEPTIONS = ("receptions", "ngs_receptions",
                        {"avg_yac_above_expectation": ("ngs_yacoe_w", "ngs_yac_over_expected")}, {})

COMPONENTS = [
    "ngs_attempts", "ngs_carries", "ngs_targets", "ngs_receptions",
    "ngs_ttt_w", "ngs_aggr_w", "ngs_iay_w", "ngs_eff_w", "ngs_box_w",
    "ngs_sep_w", "ngs_cush_w", "ngs_yacoe_w", "ngs_ryoe",
    "ngs_time_to_throw", "ngs_aggressiveness", "ngs_intended_air_yards", "ngs_rush_efficiency",
    "ngs_stacked_box_pct", "ngs_separation", "ngs_cushion", "ngs_yac_over_expected", "ngs_ryoe_per_att",
]

# component sum, visible id, weight component, is-impossible test on the recovered average.
# Mirrors core/ingest's NgsChecks.kt: an impossible average removes the sum and the weekly average.
_IMPOSSIBLE = [
    ("ngs_ttt_w", "ngs_time_to_throw", "ngs_attempts", lambda a: (a <= 0) | (a > 10)),
    ("ngs_aggr_w", "ngs_aggressiveness", "ngs_attempts", lambda a: (a < 0) | (a > 100)),
    ("ngs_iay_w", "ngs_intended_air_yards", "ngs_attempts", lambda a: (a < -30) | (a > 60)),
    ("ngs_eff_w", "ngs_rush_efficiency", "ngs_carries", lambda a: a <= 0),
    ("ngs_box_w", "ngs_stacked_box_pct", "ngs_carries", lambda a: (a < 0) | (a > 100)),
    ("ngs_sep_w", "ngs_separation", "ngs_targets", lambda a: (a < 0) | (a > 20)),
    ("ngs_cush_w", "ngs_cushion", "ngs_targets", lambda a: (a < 0) | (a > 40)),
    # A one-catch week can be huge (an 80-yard screen the model expected to gain 5 on): no bound.
    ("ngs_yacoe_w", "ngs_yac_over_expected", "ngs_receptions", lambda a: pl.lit(False)),
]


def _group(df: pl.DataFrame, weights: list[tuple]) -> pl.DataFrame:
    """One file's rows as components; rows with no components are dropped, week 0 (the season aggregate) too."""
    exprs = []
    for weight_col, weight_comp, averages, direct in weights:
        weight = pl.when(pl.col(weight_col) > 0).then(pl.col(weight_col).cast(pl.Float64))
        exprs.append(weight.alias(weight_comp))
        for col, (comp, visible) in averages.items():
            exprs.append((pl.col(col).cast(pl.Float64) * weight).alias(comp))
            exprs.append(pl.when(weight.is_not_null()).then(pl.col(col).cast(pl.Float64)).alias(visible))
        for col, (comp, per_weight) in direct.items():
            exprs.append(pl.when(weight.is_not_null()).then(pl.col(col).cast(pl.Float64)).alias(comp))
            exprs.append((pl.col(col).cast(pl.Float64) / weight).alias(per_weight))
    comps = [e.meta.output_name() for e in exprs]
    return (
        df.filter((pl.col("week") >= 1) & pl.col("player_gsis_id").is_not_null() & pl.col("season").is_not_null())
        .select(
            pl.col("player_gsis_id").alias("player_id"),
            pl.col("season").cast(pl.Int64),
            pl.col("week").cast(pl.Int64),
            pl.col("team_abbr").alias("team"),
            *exprs,
        )
        .filter(pl.any_horizontal(pl.col(c).is_not_null() for c in comps))
    )


def remap_postseason_weeks(df: pl.DataFrame) -> pl.DataFrame:
    """NGS numbers the Super Bowl week 23, play-by-play 22. A season with no NGS week 22 has its 23 become 22."""
    has_22 = set(df.filter(pl.col("week") == 22)["season"].to_list())
    return df.with_columns(
        pl.when((pl.col("week") == 23) & ~pl.col("season").is_in(list(has_22)))
        .then(22).otherwise(pl.col("week")).alias("week")
    )


def components(passing: pl.DataFrame, rushing: pl.DataFrame, receiving: pl.DataFrame) -> pl.DataFrame:
    """The three files merged to one row per (season, week, player), with every NGS component column."""
    frames = [
        _group(passing, [PASSING]),
        _group(rushing, [RUSHING]),
        _group(receiving, [RECEIVING_TARGETS, RECEIVING_RECEPTIONS]),
    ]
    merged = remap_postseason_weeks(pl.concat(frames, how="diagonal_relaxed"))
    return (
        merged.group_by(["season", "week", "player_id"], maintain_order=True)
        .agg(
            pl.col("team").first(),
            *[pl.col(c).drop_nulls().last() for c in COMPONENTS if c in merged.columns],
        )
        .select(["player_id", "season", "week", "team",
                 *[c for c in COMPONENTS if c in merged.columns]])
    )


def drop_impossible(df: pl.DataFrame) -> pl.DataFrame:
    """Null out a sum whose recovered average (sum / weight) is impossible, like NgsChecks.kt."""
    out = df
    for comp, visible, weight, bad in _IMPOSSIBLE:
        if comp in out.columns and weight in out.columns:
            impossible = bad(pl.col(comp) / pl.col(weight))
            out = out.with_columns(
                pl.when(impossible).then(None).otherwise(pl.col(c)).alias(c) for c in (comp, visible)
            )
    return out


def load(cache_dir: Path | None = None, force: bool = False) -> pl.DataFrame:
    """Download the three all-seasons files (cached) and return their merged components."""
    frames = {}
    for key in ("ngs_passing", "ngs_rushing", "ngs_receiving"):
        path = sources.fetch(key, cache_dir=cache_dir, force=force)
        frames[key] = pl.read_csv(path, infer_schema_length=10_000)
    return drop_impossible(components(frames["ngs_passing"], frames["ngs_rushing"], frames["ngs_receiving"]))
