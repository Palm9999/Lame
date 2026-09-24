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
