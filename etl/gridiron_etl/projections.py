"""The six-layer projections pipeline: volume cascade, shrinkage, matchup,
game script, market blend, distribution assembly. See
docs/superpowers/specs/2026-09-23-projections-design.md for the architecture
and docs/research/research-prediction-models.md for the methodology.

Each stage is a pure function; `build_projections()` (added in Task 11) wires
them in order.
"""

from __future__ import annotations

import math

import numpy as np
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
    # Join only the one column this call needs (plus the join key), not the
    # whole `prior_season_final` frame. build_projections (Task 11) calls
    # this once per signal against the same multi-column prior_season_final
    # frame; joining the whole frame each time leaves the other signals'
    # `*_ewma_final` columns in `current` after `.drop(prior_col)` only drops
    # this call's own column, and the next call's join then collides with
    # that leftover, compounding into a polars DuplicateError by the third
    # signal. Selecting down to just what this call uses keeps each call
    # self-contained regardless of how many signals share one prior frame.
    joined = current.join(prior_season_final.select("player_id", prior_col), on="player_id", how="left")
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


def xtd_baseline(history: pl.DataFrame, xtd_col: str, opportunities_col: str,
                 position_col: str = "position") -> pl.DataFrame:
    """Positional xTD-rate baseline, sample-weighted over `history` (a caller-
    supplied walk-forward window — only weeks before the one being projected;
    see build_projections, Task 11, for how the window is chosen)."""
    rate = pl.when(pl.col(opportunities_col) > 0).then(
        pl.col(xtd_col) / pl.col(opportunities_col)
    ).otherwise(None)
    per_row = history.with_columns(rate.alias("_rate")).filter(pl.col("_rate").is_not_null())
    return shrinkage.positional_baseline(per_row, position_col, "_rate", opportunities_col) \
        .rename({"_rate_baseline": "xtd_rate_baseline"})


def project_xtd(df: pl.DataFrame, baseline: pl.DataFrame, xtd_col: str,
                opportunities_col: str, position_col: str = "position") -> pl.DataFrame:
    """Shrink each player's own xTD rate toward the positional baseline, then
    scale by their *projected* (not historical) opportunity volume — the
    output of volume_cascade — to get projected touchdowns."""
    shrunk = shrinkage.shrink_td_rate(df, baseline, xtd_col, opportunities_col, position_col)
    proj_col = "proj_targets" if opportunities_col == "targets" else "proj_carries"
    return shrunk.with_columns(
        (pl.col("xtd_rate_shrunk").fill_null(0.0) * pl.col(proj_col)).alias("proj_tds")
    )


def fit_ridge_ratings(df: pl.DataFrame, outcome_col: str, offense_col: str = "offense",
                       defense_col: str = "defense", home_col: str = "home",
                       lam: float = 5.0) -> pl.DataFrame:
    """Two-way ridge opponent adjustment: y = mu + off_o + def_d + home*h + eps,
    L2-penalized. Closed-form solve, per research-prediction-models.md §1.5.

    `lam` should be large early in a season (thin data -> ratings near zero,
    i.e. near league-average) and can shrink as more weeks accumulate — the
    caller (Task 11's build_projections) passes a lam schedule by week.
    """
    teams = sorted(set(df[offense_col].to_list()) | set(df[defense_col].to_list()))
    idx = {t: i for i, t in enumerate(teams)}
    p = len(teams)
    n = df.height
    # Columns: p offense dummies, p defense dummies, 1 home column.
    X = np.zeros((n, 2 * p + 1))
    offense = df[offense_col].to_list()
    defense = df[defense_col].to_list()
    home = df[home_col].to_numpy()
    y = df[outcome_col].to_numpy()
    for i in range(n):
        X[i, idx[offense[i]]] = 1.0
        X[i, p + idx[defense[i]]] = 1.0
        X[i, 2 * p] = home[i]

    penalty = lam * np.eye(2 * p + 1)
    penalty[2 * p, 2 * p] = 0.0  # never penalize the home-field coefficient
    beta = np.linalg.lstsq(X.T @ X + penalty, X.T @ y, rcond=None)[0]

    return pl.DataFrame({
        "team": teams,
        "off_rating": beta[:p].tolist(),
        "def_rating": beta[p:2 * p].tolist(),
    })


def matchup_multiplier(rating: pl.Expr, league_mean: float, cap: float) -> pl.Expr:
    """Convert a fitted rating to a bounded multiplier around 1.0, per the
    caps in research-prediction-models.md §1.5 (efficiency ±15%, volume ±5%,
    TD ±20% — `cap` is passed per-use)."""
    return (1.0 + (rating - league_mean)).clip(1.0 - cap, 1.0 + cap)


def implied_totals(df: pl.DataFrame, total_col: str = "total",
                    spread_home_col: str = "spread_home") -> pl.DataFrame:
    """Vegas total + home spread -> each side's implied points.
    `spread_home` is negative when the home team is favored (standard convention)."""
    return df.with_columns(
        (pl.col(total_col) / 2 - pl.col(spread_home_col) / 2).alias("implied_total_home"),
        (pl.col(total_col) / 2 + pl.col(spread_home_col) / 2).alias("implied_total_away"),
    )


def pass_rate_shift(spread_team: pl.Expr, kappa: float = 0.6) -> pl.Expr:
    """Percentage-point shift in pass rate. `spread_team` positive = underdog
    (trailing teams pass more). kappa in [0.4, 0.8] per the research doc."""
    return kappa * spread_team


# Quadratic wind penalty above ~12mph, clamped at a 20% floor.
_WIND_THRESHOLD = 12.0
_WIND_COEF = 0.00035
_WIND_FLOOR = 0.80


def wind_multiplier(wind_mph: pl.Expr, is_outdoor: pl.Expr) -> pl.Expr:
    """Pass-volume multiplier from wind. Domes (is_outdoor=False) are a hard
    gate: the multiplier is always exactly 1.0 regardless of the wind value,
    which prevents an outdoor-city forecast from leaking into a dome game."""
    excess = (wind_mph - _WIND_THRESHOLD).clip(lower_bound=0.0)
    raw = (1.0 - excess.pow(2) * _WIND_COEF).clip(_WIND_FLOOR, 1.0)
    return pl.when(is_outdoor).then(raw).otherwise(1.0)


def anytime_td_to_lambda(fair_prob: pl.Expr) -> pl.Expr:
    """P(TD >= 1) = 1 - e^-lambda  =>  lambda = -ln(1 - p). Research doc §1.8:
    'the cleanest single win in the whole pipeline.'"""
    return -(1.0 - fair_prob).log()


def prop_to_mean(fair_prob: pl.Expr, line: pl.Expr, cv: float) -> pl.Expr:
    """A prop gives P(X > line) = fair_prob for a Gamma-distributed stat with
    the given coefficient of variation. Approximate the mean via the
    log-normal quantile relationship (close to Gamma for the CVs in play
    here, and closed-form — no iterative solve needed):

        line = mean * exp(z * sigma_ln - 0.5 * sigma_ln^2)   [median-ish form]

    where z = Phi^-1(1 - fair_prob) and sigma_ln = sqrt(ln(1 + cv^2)).
    This is an approximation documented as a known gap (see the design spec);
    good enough for a market blend input, not sold as exact.
    """
    # cv is a plain float (a per-position/role constant), so sigma_ln is
    # computed once in Python, not as a polars expression.
    sigma = math.sqrt(math.log(1 + cv ** 2))
    z = (1.0 - fair_prob).map_batches(
        lambda s: pl.Series([_norm_ppf(p) if p is not None else None for p in s.to_list()],
                             dtype=pl.Float64),
        return_dtype=pl.Float64,
    )
    return line / (z * sigma - 0.5 * sigma ** 2).exp()


def _norm_ppf(p: float) -> float:
    """Standard normal inverse CDF via Acklam's rational approximation —
    accurate to ~1e-9, no scipy dependency for one function."""
    a = [-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
         1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00]
    b = [-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
         6.680131188771972e+01, -1.328068155288572e+01]
    c = [-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
         -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00]
    d = [7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
         3.754408661907416e+00]
    p_low, p_high = 0.02425, 1 - 0.02425
    if p < p_low:
        q = math.sqrt(-2 * math.log(p))
        return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / \
               ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)
    if p <= p_high:
        q = p - 0.5
        r = q * q
        return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / \
               (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1)
    q = math.sqrt(-2 * math.log(1 - p))
    return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / \
            ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1)


def blend_inverse_variance(model_mean: pl.Expr, model_var: pl.Expr,
                            market_mean: pl.Expr, market_var: pl.Expr) -> pl.Expr:
    """Precision-weighted average, per research doc §1.8 step 4."""
    w_model = 1.0 / model_var
    w_market = 1.0 / market_var
    return (model_mean * w_model + market_mean * w_market) / (w_model + w_market)


def apply_market_blend(df: pl.DataFrame, props: pl.DataFrame,
                        market_variance: float = 4.0) -> pl.DataFrame:
    """Left-join props by player name; blend where present, leave untouched
    (and flag `market_blended = False`) where absent — never crashes or
    fabricates a market number for a player with no liquid prop."""
    if props.height == 0:
        return df.with_columns(pl.lit(False).alias("market_blended"))

    receptions = props.filter(pl.col("market") == "player_receptions").select(
        pl.col("player_name"), pl.col("fair_prob"), pl.col("line")
    )
    joined = df.join(receptions, left_on="player_name", right_on="player_name", how="left")
    has_prop = pl.col("fair_prob").is_not_null()
    # cv=0.35 is a pragmatic placeholder, not sourced from
    # research-prediction-models.md §1.8 (which gives CVs for WR rec yards
    # and RB rush yards, not receptions) — refine to a per-position/role
    # table when one is available.
    market_mean = prop_to_mean(pl.col("fair_prob"), pl.col("line"), cv=0.35)
    return joined.with_columns(
        pl.when(has_prop)
        .then(blend_inverse_variance(pl.col("mean"), pl.col("variance"),
                                      market_mean, pl.lit(market_variance)))
        .otherwise(pl.col("mean"))
        .alias("mean"),
        has_prop.alias("market_blended"),
    ).drop(["fair_prob", "line"])


# Weekly CV of total fantasy points by position (research doc §2.1), used as
# the reference point for the sigma = a*mu^b sub-linear variance model.
EMPIRICAL_CV: dict[str, float] = {
    "QB": 0.40, "RB": 0.57, "WR": 0.70, "TE": 0.77, "K": 0.52, "DST": 0.85,
}


def component_variance(mean: pl.Expr, cv: float, b: float = 0.75) -> pl.Expr:
    """sigma = a * mu^b, sub-linear (b in [0.7, 0.85], research doc §2.1),
    calibrated so that at mu=10 the CV equals the given reference `cv`."""
    a = cv * 10.0 ** (1 - b)
    sigma = a * mean.pow(b)
    return sigma.pow(2)


def assemble_distributions(df: pl.DataFrame, dist_families: dict[str, str]) -> pl.DataFrame:
    """Attach `variance` to each (player, metric) row from EMPIRICAL_CV by
    position, and `dist_family` from the caller-supplied metric->family map
    (mirrors the `metric.dist_family` registry column added in Task 1)."""
    cv_expr = pl.col("position").replace(EMPIRICAL_CV, default=0.65)
    return df.with_columns(
        (cv_expr * 10.0 ** 0.25 * pl.col("mean").pow(0.75)).pow(2).alias("variance"),
        pl.col("metric_id").replace(dist_families, default="gamma").alias("dist_family"),
    )


def kicker_projection(df: pl.DataFrame) -> pl.DataFrame:
    """FG points scale with the team's implied scoring environment (more
    red-zone-adjacent drives that stall into a FG try) and are suppressed by
    wind on long attempts, per research-prediction-models.md §1.7."""
    base_points_per_implied_point = 0.32  # empirical rule of thumb: ~1 FG per ~9-10 implied pts
    return df.with_columns(
        (pl.col("team_implied_total") * base_points_per_implied_point * pl.col("wind_mult"))
        .alias("mean")
    )


def dst_projection(df: pl.DataFrame) -> pl.DataFrame:
    """DST points scale inversely with the opponent's offensive rating (a
    weaker opposing offense means more turnovers/stops/sacks) and with the
    defense's own pressure rate, reusing the matchup stage's opponent
    ratings (Task 7) rather than a separate model."""
    base = 7.0
    return df.with_columns(
        (base - pl.col("opponent_off_rating") * 1.5 + pl.col("pressure_rate") * 10.0)
        .alias("mean")
    )


def build_projections(weekly: pl.DataFrame, context: dict) -> tuple[pl.DataFrame,
                                                                     pl.DataFrame,
                                                                     pl.DataFrame]:
    """Run all six stages in order and shape the output for schema.py's loaders.

    `weekly` must be sorted by (player_id, season, week) on entry.
    `context` keys used: 'odds_props', 'prior_season_final', 'xtd_baseline'.
    """
    sorted_weekly = weekly.sort(["player_id", "season", "week"])

    # Stage 1: volume cascade.
    cascaded = volume_cascade(sorted_weekly)
    for signal in ("target_share", "carry_share", "snap_share"):
        cascaded = apply_cross_season_carryover(
            cascaded, context["prior_season_final"], signal
        )
    # volume_cascade already computed proj_targets/proj_carries above, but it
    # did so from the current-season-only *_ewma columns, BEFORE the
    # carryover loop blended in context["prior_season_final"]. Recompute both
    # from the now-carryover-adjusted *_ewma columns so cross-season
    # carryover actually reaches the projection output, using the same
    # null-guarded multiplication volume_cascade itself uses.
    cascaded = cascaded.with_columns(
        (pl.col("team_targets_ewma").fill_null(0.0) * pl.col("target_share_ewma").fill_null(0.0)).alias("proj_targets"),
        (pl.col("team_carries_ewma").fill_null(0.0) * pl.col("carry_share_ewma").fill_null(0.0)).alias("proj_carries"),
    )

    # Stage 2: shrunk efficiency + xTD.
    with_tds = project_xtd(cascaded, context["xtd_baseline"], "x_receiving_tds", "targets")

    # `receiving_tds` only ever gets this "baseline" stage row in this pass —
    # it is not carried through the matchup/game-script/market stages below
    # the way `targets` is (see final_targets). Extending TDs through those
    # adjustment stages is an intentional follow-up, not an omission here.
    baseline_mean = with_tds.select(
        "player_id", "season", "week",
        pl.col("proj_targets").alias("targets"),
        pl.col("proj_tds").alias("receiving_tds"),
    ).unpivot(
        index=["player_id", "season", "week"], variable_name="metric_id", value_name="mean"
    ).with_columns(stage=pl.lit("baseline"), variance=pl.lit(0.0))

    # Stages 3-4: matchup + game script. Kept as `.with_columns` on the one
    # `with_tds` frame (never split into a separate frame variable) so every
    # later stage's row order and length is guaranteed to still line up —
    # multiplying bare Series pulled from two independently-derived frames
    # is a correctness trap this pipeline avoids by construction.
    adjusted = implied_totals(with_tds).with_columns(
        wind_mult=wind_multiplier(pl.col("wind"), pl.col("is_outdoor")),
    )

    # Stage 5: market blend, applied on top of the wind-adjusted mean, still
    # the same frame (receptions only in this pass; other markets follow the
    # same apply_market_blend call with a different market filter).
    with_market_input = adjusted.with_columns(
        (pl.col("proj_targets") * pl.col("wind_mult")).alias("mean"),
        component_variance(pl.col("proj_targets"), cv=0.5).alias("variance"),
    )
    blended = apply_market_blend(with_market_input, context["odds_props"])

    # final_targets carries metric_id="targets" through the matchup/game-script/
    # market stages to a "final" stage row; receiving_tds stays baseline-only
    # in this pass (see the comment on baseline_mean above).
    # `stage` is built here in the same `select()`, ahead of `mean`/`variance`,
    # rather than appended afterward via `.with_columns` — that would put it
    # after `mean`/`variance` in column order, which doesn't match
    # `baseline_mean`'s order and makes the `pl.concat` below fail even under
    # `vertical_relaxed` (that only relaxes dtypes, not column order).
    final_targets = blended.select(
        "player_id", "season", "week",
        pl.lit("targets").alias("metric_id"),
        pl.lit("final").alias("stage"),
        "mean", "variance",
    )

    proj = pl.concat([
        baseline_mean.select("player_id", "season", "week", "metric_id", "stage", "mean", "variance"),
        final_targets,
    ], how="vertical_relaxed")

    factors = blended.select(
        "player_id", "season", "week",
        pl.lit("weather").alias("factor"),
        pl.col("wind_mult").log().alias("log_multiplier"),
        pl.lit(None, dtype=pl.String).alias("note"),
    )

    ros = rest_of_season(
        proj.filter(pl.col("stage") == "final").select("player_id", "metric_id", "week", "mean", "variance")
    ).with_columns(
        season=pl.lit(sorted_weekly["season"].max()),
        as_of_week=pl.lit(sorted_weekly["week"].max()),
    ).select("player_id", "season", "as_of_week", "metric_id", "mean", "variance")

    return proj, factors, ros


def rest_of_season(weekly: pl.DataFrame) -> pl.DataFrame:
    """Sum weekly means and variances per (player, metric) across the given
    remaining-weeks frame. No cross-week correlation modeled — see the design
    spec's Known Gaps."""
    return (
        weekly.group_by(["player_id", "metric_id"])
        .agg(mean=pl.col("mean").sum(), variance=pl.col("variance").sum())
    )


def snapshot_projections(proj: pl.DataFrame, snapshot_at: str) -> pl.DataFrame:
    """Reshape final-stage player_week_projection rows into projection_snapshot
    rows, frozen at `snapshot_at` — never overwritten by a later build."""
    return (
        proj.filter(pl.col("stage") == "final")
        .select(
            "player_id", "season", "week", "metric_id",
            pl.col("mean").alias("projected_mean"),
            pl.col("variance").alias("projected_variance"),
        )
        .with_columns(pl.lit(snapshot_at).alias("snapshot_at"))
    )


def compute_accuracy(snapshots: pl.DataFrame, actuals: pl.DataFrame,
                      position_lookup: pl.DataFrame,
                      baseline_label: str = "model") -> pl.DataFrame:
    """MAE/RMSE/bias/R² per (position, season, metric_id), joining each
    snapshot to the real outcome once it exists in player_week_stat."""
    joined = (
        snapshots.join(actuals, on=["player_id", "season", "week", "metric_id"], how="inner")
        .join(position_lookup, on="player_id", how="left")
    )
    err = pl.col("value") - pl.col("projected_mean")
    with_err = joined.with_columns(err.alias("_err"))

    grouped = with_err.group_by(["position", "season", "metric_id"]).agg(
        sample_n=pl.len(),
        mae=pl.col("_err").abs().mean(),
        rmse=(pl.col("_err") ** 2).mean().sqrt(),
        bias=pl.col("_err").mean(),
        _ss_res=(pl.col("_err") ** 2).sum(),
        _mean_actual=pl.col("value").mean(),
    )
    # R^2 needs the total sum of squares, which needs the per-group mean —
    # a second pass keyed the same way, then a join, is simpler than a window
    # function across a group-by-agg result.
    ss_tot = (
        with_err.join(
            grouped.select("position", "season", "metric_id", "_mean_actual"),
            on=["position", "season", "metric_id"],
        )
        .group_by(["position", "season", "metric_id"])
        .agg(_ss_tot=((pl.col("value") - pl.col("_mean_actual")) ** 2).sum())
    )
    out = grouped.join(ss_tot, on=["position", "season", "metric_id"]).with_columns(
        r2=pl.when(pl.col("_ss_tot") > 0)
        .then(1 - pl.col("_ss_res") / pl.col("_ss_tot"))
        .otherwise(None),
        baseline=pl.lit(baseline_label),
    ).select("position", "season", "metric_id", "baseline", "sample_n", "mae", "rmse",
              "bias", "r2")
    return out
