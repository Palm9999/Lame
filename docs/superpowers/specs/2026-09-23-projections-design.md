# Projections: design

Date: 2026-09-23 · Status: draft · Implements PRODUCT_SPEC §5 (Prediction engine), §9 Phase 5

## Intent

Phase 5 of the build order: the prediction engine. Everything downstream (start/sit,
trade analyzer, waiver/FAAB, draft tools — Phase 6) depends on this existing first, the
same way Phase 3 (the Grid) depended on Phase 2 (the data pipeline).

Full scope, per the user's decisions during design:

- All six layers of the pipeline (PRODUCT_SPEC §5.1), including market/prop anchoring.
- QB, RB, WR, TE, K, and DST (not IDP — no formulas exist yet for it, and it depends on
  league settings the app doesn't support).
- Weekly and rest-of-season (ROS) projections.
- The Android side: payload consumption, on-device scoring, explainability waterfall.
- Accuracy tracking, from week 1.

**Out of scope (deferred to Phase 6):** correlated multi-player Monte Carlo (H2H win
probability, season simulation, optimal lineup) and everything built on it — start/sit
ΔWP, trade analyzer, waiver/FAAB, draft tools (VONA, auction values, tiers). Single-player
distribution simulation (floor/ceiling, boom/bust) *is* in scope — it needs no
cross-player correlation and is cheap on-device (§4.2 of the research doc: <1ms for 10k
draws of one player).

Full methodology and formulas: [`research/research-prediction-models.md`](../../research/research-prediction-models.md).
This spec doesn't re-derive that research; it says where each piece of it lives in the
codebase and how the pieces connect.

## What already exists

- `:core:statquery`'s `player_week_stat` long/narrow fact table and `metric` registry
  (`etl/gridiron_etl/schema.py`), with a `stability` field on `Metric` already earmarked
  for shrinkage.
- `ScoringProfile` (`core/model/.../Scoring.kt`) and the SQL-generated scoring logic in
  `StatQueryBuilder.scoring()`/`points()` (`core/statquery/.../StatQueryBuilder.kt`).
- `Component`/`Components` (`core/statquery/.../Component.kt`) and `StatColumn`
  (`core/statquery/.../StatColumn.kt`) naming conventions, shared between ETL metric ids
  and Kotlin.
- The `StatsRepository`/`CompareRepository` request→page repository pattern
  (`core/data/.../StatsRepository.kt`, `CompareRepository.kt`).
- `Sparkline` (`core/data/.../Sparkline.kt`, `core/charts/.../Sparkline.kt`) — the closest
  prior art for a per-player time-series shape, though it's actuals, not a projection.
- `etl/gridiron_etl/sources.py`'s cached-download `Source` pattern for pulling nflverse
  release assets — the new odds source follows the same shape.

**Consequence:** no changes to existing tables (`player`, `player_week_stat`, `metric`)
beyond adding two columns to `metric` (`dist_family`, `zero_inflated`). Everything else is
additive: new tables, a new ETL package, two new Android modules.

## 1. ETL pipeline (`etl/gridiron_etl/projections.py` + supporting modules)

Six stages, run in order, each a pure `polars` function `(frame, params) -> frame`, unit
tested the way `etl/tests/test_expected.py` tests today's transforms — hand-computed cases
per stage, not integration-only.

1. **Volume cascade.** Team plays → pass/rush split → snap share → routes/carries →
   targets/carries. EWMA-weighted per the research doc's half-life table (role 2–3 games,
   target/carry share 4–5, efficiency 8–12, TD rate effectively ∞). Cross-season carryover
   discounts last season's final EWMA (~0.5–0.6 at week 1, decaying to irrelevance by
   ~week 6). Regime-break flags (new team, new OC, new starting QB) zero the prior instead
   of decaying it.
2. **Shrunk efficiency.** James-Stein blend `w = n/(n+k)` toward the positional baseline;
   `k` fit per (position, stat) from historical split-half reliability, stored as ETL
   constants (recomputed offline, not live — they change slowly). TD rate is replaced by
   xTD from a logistic model on play-by-play (`p_TD ~ yards_to_goal, is_pass,
   air_yards_to_endzone, down, goal_to_go, position`), then shrunk the same way with a
   large `k` (in practice, even a full season stays close to baseline).
3. **Matchup.** Ridge regression, offense dummies + defense dummies + home, L2-penalized,
   fit weekly on rate inputs only (not fantasy points — see §1.5 of the research doc for
   why raw DvP is wrong). Multiplier caps: efficiency ±15%, volume ±5%, TD ±20%. Defense
   ratings themselves get shrunk by sample size (`n/(n+k)`, large `k`) so week-2/3 ratings
   sit near league average.
4. **Game script.** Vegas implied totals (`total/2 ∓ spread/2`) scale TD share most,
   yardage less, attempts least. Pass rate shifts with spread (`κ ≈ 0.4–0.8pp per point`).
   Weather (wind only, quadratic above ~12mph) is gated behind `is_outdoor`; domes zero the
   whole weather module.
5. **Market blend.** New `odds` source in `sources.py`, same `Source`/`fetch()` shape as
   existing nflverse sources, backed by The Odds API (free tier). De-vig via the
   proportional method (power/Shin reserved as a documented future improvement for
   longshot markets, not built now). Anytime-TD props convert directly to `λ = −ln(1−p)`.
   Reception/yardage props convert to a mean via an assumed CV by position/role, per the
   research doc's table. Blend with the model's own output by inverse-variance weighting;
   players with no liquid props skip this stage untouched. The Odds API key is read from
   an environment variable set from GitHub Actions secrets (never committed), per
   PRODUCT_SPEC §10.
6. **Distribution assembly.** Per component: NegBinom (targets/carries), Binomial
   (receptions | targets), Gamma (yards), Poisson (TDs, zero-inflated). Parameters (shape,
   scale, zero-inflation π) come from empirical CVs by position/role (research doc §2.1),
   refined by `σ = a·μ^b` (sub-linear in projected volume). ROS is the sum of weekly means
   and variances across remaining weeks — no cross-week correlation modeled, since nothing
   downstream needs it until Phase 6.

Fitting (ridge coefficients, James-Stein `k`s, xTD logistic coefficients, empirical CVs) is
walk-forward: each week's ETL run fits only on data available *before* that week, so
there's no leakage into the numbers the app would have shown a real user at the time. This
is also what makes the accuracy backtest (§5) meaningful.

**Positions:** QB/RB/WR/TE use the volume-cascade chain as specced in the research doc. K
projects from field-goal distance distribution (drive-level expected-points model → number
and distance of attempts) plus the existing wind model applied to long attempts. DST
projects points/yards allowed and turnovers from the same opponent-adjusted ratings used
for matchup adjustment (stage 3) applied to the DST's own defense, plus a sack-rate model
from pressure-rate data already in nflverse. Both get their own stage-3/4 treatment; they
skip stages 1–2 (no player-level volume cascade applies to a team unit).

## 2. Schema (`etl/gridiron_etl/schema.py`)

```sql
-- One row per player/week/component/stage: the projected mean of a raw stat component.
-- 'baseline' = post volume-cascade + shrinkage (stage 2), before matchup/script/market.
-- 'final'    = fully adjusted (post stage 6). The client needs both to apportion its own
-- real FP delta across factors under the user's actual scoring — see §3.
CREATE TABLE player_week_projection (
    player_id   TEXT NOT NULL,
    season      INTEGER NOT NULL,
    week        INTEGER NOT NULL,
    metric_id   TEXT NOT NULL,
    stage       TEXT NOT NULL,   -- 'baseline' | 'final'
    mean        REAL NOT NULL,
    variance    REAL NOT NULL,
    PRIMARY KEY (player_id, season, week, metric_id, stage)
) WITHOUT ROWID;

-- One row per player/week/factor: log-space attribution multiplier for one stage's
-- adjustment (stage 1-2's output is the baseline itself, not a factor row — there's
-- nothing to attribute until stage 3 starts adjusting it).
-- Scoring-profile-independent by construction — see §3 for why.
CREATE TABLE player_week_projection_factor (
    player_id     TEXT NOT NULL,
    season        INTEGER NOT NULL,
    week          INTEGER NOT NULL,
    factor        TEXT NOT NULL,   -- 'matchup' | 'game_script' | 'weather' | 'market'
    log_multiplier REAL NOT NULL,
    note          TEXT,            -- short evidence string for the UI
    PRIMARY KEY (player_id, season, week, factor)
) WITHOUT ROWID;

-- Rest-of-season aggregate: summed weekly means/variances, no per-week detail.
CREATE TABLE player_ros_projection (
    player_id   TEXT NOT NULL,
    season      INTEGER NOT NULL,
    as_of_week  INTEGER NOT NULL,  -- last completed week this reflects
    metric_id   TEXT NOT NULL,
    mean        REAL NOT NULL,
    variance    REAL NOT NULL,
    PRIMARY KEY (player_id, season, as_of_week, metric_id)
) WITHOUT ROWID;

-- Frozen at projection time; never overwritten. Joined against player_week_stat
-- once actuals land to compute accuracy — no separate actuals table.
CREATE TABLE projection_snapshot (
    player_id      TEXT NOT NULL,
    season         INTEGER NOT NULL,
    week           INTEGER NOT NULL,
    metric_id      TEXT NOT NULL,
    projected_mean REAL NOT NULL,
    projected_variance REAL NOT NULL,
    snapshot_at    TEXT NOT NULL,  -- ISO timestamp of the ETL run
    PRIMARY KEY (player_id, season, week, metric_id, snapshot_at)
) WITHOUT ROWID;

-- Precomputed accuracy, refreshed each ETL run.
CREATE TABLE accuracy_summary (
    position   TEXT NOT NULL,
    season     INTEGER NOT NULL,
    metric_id  TEXT NOT NULL,       -- 'fantasy_points' or a component, e.g. 'rec_yards'
    baseline   TEXT NOT NULL,       -- 'model' | 'season_avg' | 'last4_avg'
    sample_n   INTEGER NOT NULL,
    mae        REAL NOT NULL,
    rmse       REAL NOT NULL,
    bias       REAL NOT NULL,       -- mean error
    r2         REAL,
    PRIMARY KEY (position, season, metric_id, baseline)
) WITHOUT ROWID;
```

`metric` gains two nullable columns: `dist_family TEXT` (`negbinom`/`binomial`/`gamma`/
`poisson`) and `zero_inflated INTEGER NOT NULL DEFAULT 0`. Distribution *shape* is a
property of the metric, not the player-week, so it isn't duplicated per row — the client
reconstructs the full distribution from `mean`/`variance` plus the registry lookup.

Baselines (season-to-date average, last-4-games average) are computed and stored through
the exact same `projection_snapshot`/`accuracy_summary` mechanism as the model, so accuracy
code has no special cases for them — they're just alternate projections.

## 3. On-device (`:core:projections`, `:feature:projections`)

**Why two new modules, not extensions of existing ones:** `:core:projections` sits beside
`:core:statquery` (query/compute logic) the way `:core:statquery` sits beside `:core:data`
(repository/IO logic) — same split, one layer over. `:feature:projections` sits beside
`:feature:compare` and `:feature:scoring` as a third UI feature module.

**The scorer.** Fantasy scoring today only exists as generated SQL
(`StatQueryBuilder.scoring()`/`points()`, reading `RULE_INPUTS`). Single-player Monte Carlo
needs to call scoring tens of thousands of times per second in a tight loop — SQL can't do
that. So the `ScoringRule`→`Component` weighting math is extracted into a standalone pure
function:

```kotlin
fun score(components: Map<Component, Double>, profile: ScoringProfile): Double
```

living in `:core:projections`. `StatQueryBuilder` is refactored to call this function
instead of re-deriving the same math in SQL generation, so there's one source of truth for
"what does this ScoringProfile do to these components," not two that could drift.

**Never ship fantasy points — including in the explainability data.** The factor table
stores `log_multiplier`, not an FP delta, specifically so a Chiefs-fan on a
6-point-passing-TD superflex league and someone on standard half-PPR see attributions
computed under *their own* scoring, not a server-assumed default. The client:

1. Computes `FP_baseline` and `FP_final` itself, via `score()`, from two component-mean
   rows the ETL ships per metric: the post-stage-2 (volume + shrunk efficiency, before
   matchup/script/weather/market) output as the baseline, and the post-stage-6
   (fully-adjusted) output as final. Stage 6 writes both into `player_week_projection`,
   distinguished by a `stage` column (`'baseline'` or `'final'`), rather than requiring the
   client to reconstruct baseline by dividing out multipliers.
2. Apportions its own real `Δ = FP_final − FP_baseline` across factors using the shipped
   `log_multiplier` ratios: `Δᵢ = Δ × log_multiplier_i / Σⱼ log_multiplier_j`.

Same exact-sum-to-whole guarantee as the research doc's formula; correct under every
league's scoring, because it's computed from real components, not shipped points.

**`ProjectionsRepository`**, matching the existing repository shape:

```kotlin
suspend fun projections(request: ProjectionsRequest, catalog: Catalog): ProjectionsPage
suspend fun rosProjections(request: ProjectionsRequest, catalog: Catalog): RosProjectionsPage
```

Reads `player_week_projection`/`player_week_projection_factor`/`player_ros_projection`,
applies `score()` with the caller's `ScoringProfile` to get point means.

**Single-player Monte Carlo** for floor/ceiling and boom/bust: draw each component from its
distribution family (metric registry lookup), run `score()` per draw — 10k draws, plain
`DoubleArray`, `SplittableRandom`, no boxing, matching the research doc's §4.4 implementation
notes. No cross-player correlation; that needs the Gaussian-copula machinery, out of scope
until Phase 6 builds H2H/lineup tools on top of it.

**`:feature:projections` UI:** the waterfall composable, floor/ceiling display, TD-dependence
badge (`6λ_TD / FP`), confidence badge (driven by shrinkage weight `w`), stale-input
indicator (odds/injury report timestamp), and the accuracy page (§4).

## 4. Accuracy tracking

Computed entirely server-side into `accuracy_summary` — the client only renders it, same
pattern as everything else in this design. Per position/season/metric/baseline: MAE
(primary), RMSE, bias, R². Calibration (P10–P90 coverage, Brier score for boom/bust) is
checkable retroactively from `projection_snapshot`'s stored `variance`, without re-running
old ETL — those are computed by a separate accuracy pass, not stored as their own table,
since they're cheap to derive on read.

The in-app accuracy page reads `accuracy_summary` directly: weekly MAE by position (this
season and all-time), model vs. the two baselines, and — per PRODUCT_SPEC §5.5's framing —
an honest explainer of what R² actually means for this domain.

## Error handling

| Case | Behavior |
|---|---|
| Odds API unavailable or rate-limited | Market stage (5) skipped for that run; projections ship from stages 1–4 only, flagged `market_blended = false` per player in the payload (a boolean, not a separate table) |
| Player has no historical data (rookie, new team) | Stage 2 falls back to the draft-capital/positional-baseline prior; `w ≈ 0` |
| Ridge fit fails to converge (rare, tiny early-season sample) | Matchup stage no-ops (multiplier = 1.0) for that run rather than emitting garbage |
| Metric has no `dist_family` set | Falls back to Gamma (safe default for a positive continuous quantity); logged as a data-quality warning |
| Client requests projections for a week with no ETL data yet | Repository returns an empty page; UI shows "No projection yet" the same way the Grid shows "No players match" |

## Testing

- **ETL, per-stage unit tests** (`etl/tests/`, extending the existing pattern): hand-computed
  cases for James-Stein shrinkage, ridge matchup adjustment on a small synthetic league,
  de-vig arithmetic (proportional method), prop→mean inversion (anytime-TD, receptions,
  yards), EWMA half-life math, distribution parameter fitting from empirical CVs.
- **ETL, walk-forward backtest:** train on weeks 1..N of real historical data, project week
  N+1, compare to the real outcome — the main pipeline-level sanity check and the mechanism
  that makes the accuracy tracker meaningful from week 1 of real use.
- **Kotlin, `score()` unit tests:** every `ScoringProfile` preset × a representative
  component map, replacing/extending today's SQL-only scoring assertions with direct
  function tests.
- **Kotlin, `ProjectionsRepository` tests** against a JDBC fixture DB seeded with the new
  tables (mirroring `RealDatabaseContractTest`'s pattern).
- **Kotlin, Monte Carlo test:** simulated P50 converges to the analytic mean within
  tolerance, for each distribution family used.
- Roborazzi screenshots for the waterfall UI: left to the implementation plan, alongside
  the actual composable design.

## Known gaps (carried forward, not blocking)

- Power/Shin de-vig for longshot markets (anytime-TD) — proportional de-vig is used
  instead; documented as a future accuracy improvement, not built now.
- IDP is excluded entirely — no formulas exist yet and it depends on league settings the
  app doesn't support.
- Cross-week correlation for ROS distributions isn't modeled — ROS variance is the sum of
  weekly variances, which slightly understates true ROS variance (ignores that a player's
  good/bad weeks are mildly correlated) but nothing downstream needs the correction yet.
