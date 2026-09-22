# Prediction Engine Research — NFL Fantasy Football Android App

Research date: 2026-09-22. Target: free, no-paywall Kotlin/Jetpack Compose app needing credible weekly + rest-of-season (ROS) projections and decision tools.

---

## 0. Executive summary

The defensible architecture is **opportunity-first, market-anchored, regression-heavy, and transparent**:

1. Project **volume** (snaps → routes/carries → targets → receptions), not points.
2. Project **efficiency** as heavily shrunk rates toward positional baselines — never raw observed rates.
3. **Regress touchdowns hard.** TD rate has ~0.28 year-over-year correlation; volume metrics run 0.55–0.75. TD regression is the single largest free edge.
4. **Anchor to betting markets.** De-vigged player props (receptions, rec yards, rush yards, anytime TD) are the sharpest publicly available per-player forecast. Use as a Bayesian prior or as a blend partner, not a replacement.
5. **Ensemble.** Consensus beats every individual source in ~63–69% of head-to-head comparisons across 11–12 seasons of real data.
6. **Ship distributions, not points.** Every projection is a distribution; every decision tool is a simulation over those distributions with correlation.
7. **Explain every number.** Additive factor decomposition (baseline + matchup + game script + weather + injury) is both a UX differentiator and a debugging tool.

Expectation-setting, from real published accuracy studies: **weekly projections explain only 3–23% of variance in actual fantasy points (R²), season-long 14–26%.** Design the product around that reality rather than hiding it.

---

## 1. Projection methodologies

### 1.1 Opportunity-based projection (the backbone)

Every good public model projects **volume first, then efficiency, then points**. Opportunity is far more stable and predictive year-over-year than any efficiency number.

**The cascade:**

```
Team plays  →  Team pass attempts / rush attempts
            →  Player snap share
            →  Player route participation  /  Player carry share
            →  Player target share (per route run: TPRR)
            →  Targets
            →  Receptions (catch rate)
            →  Receiving yards (YPT or air yards + YAC)
            →  TDs (from red-zone/goal-line opportunity, not past TDs)
            →  Fantasy points (league scoring applied last)
```

**Concrete chain (WR, PPR):**

```
team_plays        = f(pace, Vegas total, spread)
team_pass_att     = team_plays * PROE_adjusted_pass_rate
routes            = team_pass_att * route_participation
targets           = routes * TPRR                      # targets per route run
  (equivalently: targets = team_pass_att * target_share)
receptions        = targets * catch_rate
rec_yards         = targets * yards_per_target
                  ( or: air_yards_share * team_air_yards * completion_adj + receptions * YAC/rec )
rec_TD            = expected_TD  (see §1.2)
FP                = receptions*1.0 + rec_yards*0.1 + rec_TD*6 + bonuses
```

**RB chain:**

```
rush_att      = team_rush_att * carry_share
rush_yards    = rush_att * YPC_shrunk
targets       = team_pass_att * rb_target_share
rush_TD       = goal_line_carries * gl_TD_rate_shrunk + open_field_TD_rate * rush_att
```

**QB chain:**

```
pass_att      = team_pass_att
comp          = pass_att * completion_pct_shrunk
pass_yards    = pass_att * YPA_shrunk
pass_TD       = f(implied_team_total, team_pass_TD_share, red_zone_pass_rate)
INT           = pass_att * int_rate_shrunk (heavily shrunk; INT rate is nearly pure noise)
rush_yards/TD = designed_runs + scramble_rate * pass_att
```

**Why this works:** usage rate is the most causally defensible input available — snap count, target share and carry share represent *allocation decisions the offense makes* that determine opportunity before talent enters the picture. Efficiency is where luck lives.

**Weighted opportunity.** Not all touches are equal. PFF's "weighted opportunity" and similar constructs weight a target ~2.5–2.75x a carry in PPR, and weight by field position. A practical weighted-opportunity score:

```
WOPR = 1.5 * target_share + 0.7 * air_yards_share     # PFF/Josh Hermsmeyer's standard
```

WOPR is one of the best single-number opportunity proxies for WRs and worth exposing directly in the UI.

**Expected Fantasy Points (xFP).** The generalization: assign each opportunity the average fantasy points that opportunity historically produces, given its context.

```
xFP = Σ_targets  E[FP | air_yards, down, distance, field_position]
    + Σ_carries  E[FP | LOS, down, distance]
```

Built from 10-season play-by-play samples: value each target at each depth-of-target and each line of scrimmage; value each carry at each LOS/down/distance. `Actual FP − xFP` is your efficiency-luck residual and the primary regression signal.

**Data source:** nflverse / nflfastR play-by-play is free, open, and has everything needed to compute xFP, EPA, air yards, route data proxies, and red-zone splits. This is the natural server-side ETL input for a free app.

### 1.2 Regression to the mean — TD rates and efficiency (the biggest edge)

**Year-over-year stability (approximate published values):**

| Metric | YoY correlation | Treatment |
|---|---|---|
| Targets / target share | ~0.70–0.75 | trust, light shrinkage |
| Receiving yards per game | ~0.65 | trust |
| Carry share / snap share | ~0.60–0.70 | trust |
| Air yards per target (WR) | moderate-high | trust |
| Catch rate | moderate | shrink |
| Yards per target / YPC | low | shrink hard |
| Yards after catch per rec | low | shrink hard |
| **TD rate** | **~0.28** | **shrink very hard** |
| INT rate (QB) | very low | shrink near-fully to baseline |

TDs are rare relative to opportunity, so a swing of a couple of scores moves TD rate enormously — hence the weak correlation. Scoring and efficiency are nowhere near as reliable as volume.

**Expected TD model.** Replace observed TDs with opportunity-derived expected TDs:

```
xTD = Σ_i  p_TD(opportunity_i)
```

where `p_TD` comes from a logistic model on play-by-play:

```
logit p_TD = β0 + β1*log(yards_to_goal) + β2*is_pass + β3*air_yards_to_endzone
           + β4*down + β5*goal_to_go + β6*position
```

Then project next-period TDs as a shrunk blend:

```
TD_proj = w * xTD_rate_player + (1 - w) * xTD_rate_positional_baseline
w = n_opps / (n_opps + k)
```

with `k` calibrated per position (see §1.3). In practice for TD rate, `k` is large — meaning even a full season of data leaves the projection close to baseline.

**Published magnitude of the effect:** in 2012–2024, among WR/TE with <5 TDs on 50+ offensive touches who had 50+ touches the next season, **66.3% scored more TDs the following season.** That's a coin-flip beaten by 16 points, for free, with no model.

**Implementation rule for the app:** never let a player's own historical TD rate carry more than ~25–35% of the weight in a weekly TD projection. Drive it from red-zone/goal-line share × team implied red-zone trips.

### 1.3 Bayesian / shrinkage for small samples

Essential for weeks 1–6, rookies, players in new roles, and post-injury returns.

**Beta-Binomial (for rate stats: catch rate, target share, TD rate, goal-line share)**

```
posterior_rate = (successes + α) / (trials + α + β)
```

where `α, β` are fit by method-of-moments on the positional population:

```
μ = population mean rate
σ² = population variance of rate
ν = μ(1-μ)/σ² − 1          # "prior sample size"
α = μν,   β = (1-μ)ν
```

The **stabilization point** is where the player's own data carries 50% of the weight: `n* = α + β = ν`. Publish these per stat. Practical implication: target share stabilizes in roughly 4–6 games; TD rate does not stabilize within a season at all.

**Normal-Normal (for continuous rates: YPT, YPC, YPA)**

```
posterior_mean = (τ² * x̄_player + σ²/n * μ_population) / (τ² + σ²/n)
```

`τ²` = between-player (true talent) variance, `σ²` = within-player (noise) variance. Estimate both from historical split-half reliability.

**James-Stein / shrinkage factor form (easiest to implement and to explain in-app):**

```
w = n / (n + k),  where k = σ²_within / τ²_between
projected_rate = w * observed_rate + (1 - w) * prior_rate
```

`k` is literally "how many observations before you believe the player." A small table of `k` by (position, stat) is a complete, explainable, shippable shrinkage system.

**Priors for rookies / no-sample players:**
- Draft capital (round/pick) is the strongest rookie prior — build a prior curve of `expected_target_share | draft_pick`.
- Athletic/production composites (college dominator rating, breakout age, RAS) as secondary.
- Beat-reporter/depth-chart-derived role tags as a manual override lever.

**Hierarchical structure worth building server-side:** player nested within team-offense nested within position. Players on a new team get partial pooling toward the *team's* offensive baseline, which correctly handles "WR2 on a high-volume passing offense."

### 1.4 Time weighting: recent form vs season-long

Use **exponentially weighted moving averages (EWMA)** rather than "last 3 games" windows — windows throw away information and create discontinuities.

```
EWMA_t = λ * x_t + (1 - λ) * EWMA_{t-1}
```

Half-life form (more intuitive to expose to users):

```
λ = 1 − 2^(−1/H)        where H = half-life in games
equivalently  H = −log(2) / log(1 − λ)
```

**Recommended half-lives (start here, tune by walk-forward validation):**

| Signal | Half-life | Rationale |
|---|---|---|
| Snap share / route participation | 2–3 games | role changes fast, and it's the thing that changes |
| Target share / carry share | 4–5 games | genuine but drifting |
| Efficiency rates (YPT, YPC) | 8–12 games, or cross-season | mostly noise; slow = good |
| TD rate | effectively ∞ (use baseline) | don't chase |
| Team pace / PROE | 4–6 games | scheme drift is real |

**Cross-season carryover:** apply a discount when crossing a season boundary rather than resetting. A common treatment is to weight last season's final EWMA at ~0.5–0.6 entering week 1, decaying to irrelevance by ~week 6. Add explicit regime-break flags that zero out prior-team data: new team, new OC, new QB, role change (e.g. RB2 → RB1 after injury ahead of them).

**Key discipline:** apply time-weighting to *volume/role* aggressively and to *efficiency* barely. Chasing hot efficiency is the most common amateur modeling error.

### 1.5 Matchup adjustment done correctly

**Why raw DvP (Defense vs Position / fantasy points allowed) is bad:** raw fantasy points allowed tells you more about *who a team has played* than how well they defend a position. Allowing 30 points to the Chiefs is scored identically to allowing 30 to the Panthers. In small samples (weeks 1–8) DvP is mostly schedule noise, and it's the single most over-used stat in fantasy content.

**Better, in increasing order of quality:**

1. **Schedule-adjusted fantasy points allowed (SAFPA).** Instead of raw points, measure points allowed *relative to each opponent's own average*:

   ```
   adj_def_effect_d = mean over games g of ( FP_allowed(d,g) − expected_FP(opponent_offense, g) )
   ```

   This is the minimum viable fix and cheap to compute.

2. **Opponent-adjusted rate stats.** Adjust the *rate* inputs, not the fantasy output. What you actually want to adjust:
   - YPC allowed / rush success rate allowed (for RB rushing)
   - Completion % over expected and YPA allowed (for QB/pass game)
   - Yards per route run allowed **by alignment** — slot vs wide vs tight — matched to the receiver's alignment rate. This is where real matchup edge lives (e.g., a slot-heavy WR vs a team with a weak nickel).
   - Pressure rate / sack rate allowed (drives QB rushing and checkdowns)
   - Red-zone TD rate allowed

3. **EPA-based defensive ratings.** EPA/play allowed, split pass vs rush, opponent-adjusted, and EWMA-weighted. EPA is the engine behind most modern efficiency frameworks and gives a context-adjusted picture that raw yardage obscures. Expected Points Allowed **per drive** is better still since it normalizes for the number of drives faced.

4. **Ridge regression / two-way opponent adjustment.** The statistically correct version: regress per-game outcomes on offense dummies + defense dummies with L2 penalty. The defense coefficients *are* the opponent-adjusted defensive ratings, automatically de-confounded from schedule. Cheap enough to run weekly server-side (a few hundred × few hundred design matrix).

   ```
   y_gd = μ + off_o + def_d + home + ε,  minimize ||y − Xβ||² + λ||β||²
   ```

   Shrink `λ` hard early in the season so week-3 ratings sit near league average.

**Magnitude discipline:** matchup should be a *modest multiplier*, typically bounded to roughly ±15% on efficiency components and near-zero on volume (defenses don't change how many targets a WR sees very much; they change what he does with them). A common failure mode of fantasy apps is matchup adjustments that swamp the baseline. Recommended caps:

```
matchup_mult_efficiency ∈ [0.85, 1.15]
matchup_mult_volume     ∈ [0.95, 1.05]
matchup_mult_TD         ∈ [0.85, 1.20]   # red-zone defense is more real than most matchup stats
```

Also apply **shrinkage-by-sample** to the defense itself: a week-2 defensive rating gets weight `n/(n+k)` with a large `k`.

### 1.6 Game script modeling from Vegas

Betting markets give you the best free forecast of the *game environment*.

**Implied team totals:**

```
implied_total_home = total/2 − spread_home/2
implied_total_away = total/2 + spread_home/2
```

(with `spread_home` negative when home is favored). Example: total 47, home favored by 6 → home 26.5, away 20.5.

**Uses:**

- **Scale scoring opportunity.** A QB baseline built on a 24-point team environment facing a 30-point implied total scales passing volume up; a ~10–15% adjustment for a 6-point implied-total delta is a reasonable published rule of thumb. Apply mostly to **TD share**, less to yardage, least to attempts.
- **Pass/rush split from spread.** Trailing teams pass; leading teams run. The effect is roughly monotone in the spread. Model it as:

  ```
  Δpass_rate ≈ κ * spread_team        (spread_team > 0 means underdog)
  ```

  with `κ` on the order of 0.4–0.8 percentage points of pass rate per point of spread, fitted from play-by-play. Blowout games (|spread| > 9) show the largest effect, and it is concentrated in the second half.
- **Plays from total + pace.** Higher totals → more plays and more red-zone trips.
- **Who it moves:** the spread impacts running backs and pass-catching tight ends more than quarterbacks. Concretely: heavy-favorite RBs gain carries and goal-line work; big-underdog RBs gain *targets* but lose carries (receiving RBs are relatively game-script-proof); underdog WRs gain volume but in lower-leverage garbage time.
- **Derive team pass attempts directly:**

  ```
  team_plays     = base_plays(pace_off, pace_def) * total_adjustment
  pass_rate      = neutral_pass_rate + PROE_team + κ*spread_team
  team_pass_att  = team_plays * pass_rate
  ```

- **Red-zone trips:** `implied_total ≈ 7*TD + 3*FG`; back out expected TDs as `≈ (implied_total − FG_share_points)/7` and allocate via each player's red-zone target/carry share.

**Caution:** lines move. Cache the line used, timestamp it, and re-project on significant moves (>1.5 pts on total or spread). Show the user which line the projection assumed.

### 1.7 Weather adjustments

Only a few weather variables matter, and only past thresholds. Published findings:

**Wind — the only one that reliably matters a lot:**

| Wind (sustained) | Effect |
|---|---|
| < 10 mph | negligible; QB completion ~60.3% |
| 10–15 mph | slight; ~0.7pp completion, ~0.13 yards/attempt |
| **15+ mph** | first real inflection — pass attempts, pass yards, and points all drop measurably |
| 20+ mph | completion ~54.7% (−5.6pp vs <10mph); passing yards down ~20/team; rush attempts up. The drop from 15–20 → 20+ is roughly 1.5–2.0× the drop from 10–15 → 15–20 |
| 25+ mph | YPA craters ~17.9% |

Also: kickers are hit harder than anyone by wind (long FG attempts), and deep/vertical WRs are hit harder than slot/YAC WRs.

**Implementable wind model:**

```
wind_pass_mult = 1 − max(0, wind_mph − 12)² * c     # quadratic above ~12 mph, c ≈ 0.00035
wind_pass_mult = clamp(wind_pass_mult, 0.80, 1.0)
wind_rush_mult = 1 + (1 − wind_pass_mult) * 0.5     # volume shifts to the ground
wind_deep_penalty applies extra to aDOT > 12 receivers
wind_FG_mult    = steeper; distance-dependent
```

**Precipitation:** effect is much smaller than folklore suggests. Light rain ≈ negligible. Heavy rain/snow: modest ding to passing efficiency and a real ding to fumble-free ball security; primarily reduce *total* scoring ~1–2 points rather than reallocating.

**Temperature:** small, mostly via extreme cold (<20°F) reducing passing efficiency slightly and reducing total scoring. Not worth a large coefficient.

**Dome / retractable roof:** hard gate — zero out all weather adjustments. This is the highest-value weather feature because it prevents false positives from outdoor forecasts near domed stadiums.

**Practical:** gate the whole weather module behind `is_outdoor AND (wind ≥ 12 OR precip_prob ≥ 0.5 OR temp ≤ 25)`. Otherwise show "Weather: no impact" — which is itself a trust-building message.

### 1.8 Betting markets as a prior (the sharpest free-ish source)

Player props are a forecast produced by parties with money at risk and are arguably the sharpest per-player public number available. Use them.

**Step 1 — de-vig.** Convert American odds to implied probability:

```
p_raw = (−odds)/(−odds + 100)          if odds < 0
p_raw = 100/(odds + 100)               if odds > 0
```

Then normalize the two-sided market:

```
p_fair_over = p_raw_over / (p_raw_over + p_raw_under)
```

(Multiplicative/proportional de-vig is the simple default; power and Shin methods are better for longshots like anytime-TD, where favorite-longshot bias makes proportional de-vig overstate longshot probability.)

**Step 2 — convert a line + fair probability to a mean.** A prop gives you a *median-ish* quantile, not a mean. Assume a parametric distribution for the stat and solve for the mean:

- **Receptions** → Negative Binomial or Poisson. Given line `L` (e.g. 4.5) and `P(X > L) = p`, solve for λ such that `P(X ≥ 5) = p`.
- **Receiving/rushing yards** → Gamma or lognormal. Solve for the mean given `P(X > L) = p` and an assumed CV (coefficient of variation) by position/role (WR rec yards CV ≈ 0.6–0.75; RB rush yards CV ≈ 0.5–0.6).
- **Anytime TD** → directly gives `P(TD ≥ 1)`. Convert to expected TDs assuming Poisson:

  ```
  P(TD ≥ 1) = 1 − e^(−λ)   ⇒   λ = −ln(1 − p_fair)
  ```

  This is the cleanest single win in the whole pipeline: **anytime-TD props give you a market-consensus expected-TD number that already embeds regression, game script, and red-zone role.**

**Step 3 — assemble prop-implied fantasy points:**

```
FP_market = λ_rec * PPR + μ_recyds * 0.1 + μ_rushyds * 0.1 + λ_TD * 6 + ...
```

**Step 4 — blend with your own model** as a precision-weighted average (inverse-variance):

```
FP_final = (FP_model/σ²_model + FP_market/σ²_market) / (1/σ²_model + 1/σ²_market)
```

In practice the market deserves large weight — 50–70% for players with liquid props — and your model's job becomes (a) covering players with no props, (b) providing the *distribution* and correlation structure that props don't give, and (c) supplying ROS projections where props don't exist.

**Availability caveat for a free app:** live odds APIs are mostly paid (The Odds API has a free tier with limited monthly requests; books' own feeds are unofficial). A realistic design: server fetches props once or twice weekly on a free/cheap tier, caches them, and ships the derived numbers to clients. Treat props as an enrichment layer that degrades gracefully, not a hard dependency. Also: an app that is free and shows betting-derived numbers should be careful about app-store gambling classification — present them as *projections*, not as odds or bet recommendations, and don't link out to sportsbooks.

### 1.9 Ensembling / consensus

**The evidence is unambiguous.** Fantasy Football Analytics' long-running studies:

- **Season-long, 12 seasons (2014–2025), 11 sources:** the FFA Average (simple mean of sources) outperformed individual sources in **69%** of head-to-head comparisons, average rank 4.2 of 11. **Equal weighting edged weighted averaging.**
- **Weekly/DFS, 11 seasons (2015–2025), 9 sources, 6,000+ source-week combinations:** the FFA Average outperformed individual sources in **63%** of head-to-head comparisons.
- FantasyPros (itself a consensus product) led QB season MAE at 61.0 with FFA Average at 61.7 — the two aggregation approaches beat every individual source.

**Why:** individual sources have idiosyncratic, partially uncorrelated errors. Averaging cancels them. Weighted averaging tends to overfit past accuracy because source rank is itself unstable year to year.

**Practical recommendation:**
- Use a **simple mean or trimmed mean** (drop min/max) of 3–6 independent signals. Do not over-engineer the weights.
- Reasonable component set for this app: (1) your own opportunity model, (2) market/prop-implied, (3) an EWMA recent-form baseline, (4) a season-long-prior baseline, and if licensing allows, (5) any free public consensus you may legitimately use.
- **Median is more robust than mean** when one component can blow up (e.g., a stale prop or an injury the model hasn't ingested).
- Track per-component accuracy in the background and expose it — but resist auto-reweighting more than mildly (e.g., weights bounded to [0.5×, 2×] equal weight).

**Licensing note for a free app:** scraping competitor projections is legally and commercially risky. Build the ensemble out of *your own* signal variants plus open data (nflverse) plus markets. That's defensible and still gets most of the ensembling benefit because the components genuinely differ in method.

---

## 2. Distributions, not point estimates

A point projection is the least interesting output of a projection system. Fantasy decisions are almost always about *distributions* — you start the player more likely to win you the week, which is not always the higher mean.

### 2.1 What distribution fits fantasy points?

Fantasy points are a **compound / mixture** quantity: a continuous yardage component plus a discrete, heavy-weighted TD component. That structure is why no single simple distribution fits well.

**Recommended: simulate the components, don't fit the total.**

```
per simulation draw:
  targets     ~ NegBinom(mean = μ_tgt, dispersion by position)
  receptions  ~ Binomial(targets, catch_rate)            # or Beta-Binomial
  yards       ~ Gamma(shape, scale) conditioned on receptions   (or sum of per-catch Gammas)
  TDs         ~ Poisson(λ_TD)   [or Binomial(red_zone_opps, p)]
  FP          = apply league scoring
```

This reproduces the real shape automatically: right skew, a lumpy multi-modal profile from TD counts, and a floor near zero.

**If you must fit the total directly** (cheaper on-device):
- **Gamma** is the best simple fit for positive, right-skewed fantasy points and is noted as fitting fantasy data nicely. Parameterize by mean and CV: `shape k = 1/CV², scale θ = μ·CV²`.
- **Lognormal** also used and reasonable; weekly scores have been modeled as lognormal with week-to-week variance redrawn each week.
- **Skew-normal** is used in the Fantasy Football Analytics textbook, selected against normal by AIC (prefer skew-normal only when ΔAIC > 2).
- **Zero-inflation matters** for RB/TE/WR3: real probability mass at ~0–2 points (inactive, ejected from game script, one-catch dud). A two-component mixture (`π · Dud + (1−π) · Gamma`) captures floor much better than any single-family fit.
- **QBs are the most normal** of the positions (high volume, many small contributions); **TEs and RBs are the most TD-dependent and hence the most lumpy.**

**Empirical CVs to use as starting variance priors** (weekly, PPR, for starters):

| Position | Typical CV of weekly FP |
|---|---|
| QB | 0.35–0.45 |
| RB | 0.50–0.65 |
| WR | 0.60–0.80 |
| TE | 0.65–0.90 |
| K | 0.45–0.60 |
| DST | 0.70–1.00 |

Note these are *unconditional*. Conditional on role, high-volume players have lower CV — variance should be a function of projected volume, not a per-position constant. A defensible model: `σ = a·μ^b` with `b ≈ 0.7–0.85` (sub-linear, so bigger projections are proportionally safer).

**Non-parametric alternative:** bootstrap each player's residual distribution from comparable historical player-weeks (matched on projected volume and position). This avoids distributional assumptions entirely and is easy to precompute server-side as a small residual table shipped to the client.

### 2.2 Floor, ceiling, percentiles

Report **P10 / P25 / P50 / P90** plus mean. Users understand "floor / median / ceiling" instantly.

```
Floor   = P10 (or P15)
Ceiling = P90
Boom    = P(FP ≥ boom_threshold)
Bust    = P(FP ≤ bust_threshold)
```

Thresholds should be **position- and context-relative**, not absolute. Two good options:
- Relative to the player's own projection: boom = `P(FP ≥ 1.5 × proj)`, bust = `P(FP ≤ 0.5 × proj)`.
- Relative to positional starter baseline: boom = `P(FP ≥ weekly positional top-12 threshold)`, bust = `P(FP ≤ replacement level)`. This is more decision-relevant.

Prefer the second for start/sit and the first for "is this player volatile?"

### 2.3 Correlation and stacking

Independent simulation of players is wrong and produces badly miscalibrated team totals and matchup win probabilities.

**Qualitative correlation structure (published):**

- **QB–WR1**: strongest positive; the canonical stack.
- **QB–TE**: second strongest positive.
- **QB–WR2/WR3**: positive but weaker.
- **QB–RB**: weak; slightly negative for rushing-heavy RBs, positive for receiving RBs.
- **WR–WR same team**: roughly **−0.02** — flat, faintly negative. Same-team WR pairs do not stack; they split a fixed pie.
- **RB–WR same team**: negative (they steal TDs from each other).
- **QB–K**: small positive. **K–DST**: positive (both benefit from a winning, field-position-favorable script).
- **WR–DST same team**: negative.
- **Opposing-team (bring-back)**: positive game-total correlation — both offenses benefit from a shootout. Weaker than same-team QB-WR but real, and it's the whole basis of game stacks.
- **Same-team DST vs opposing skill players**: strongly negative.

**Magnitude of the stacking effect (real published figure):** stacking a QB and his WR versus spreading the same two picks across different teams concentrates **roughly +1.8 points onto the combined weekly ceiling** and shaves **about −1.6 points off the floor.** That's the entire trade: stacking buys ceiling with floor. Correct for tournaments/best ball, usually wrong for a redraft H2H team that just needs to win a median week.

**Important modifier:** the QB–WR correlation depends on the offense. Pass-heavy offenses have genuinely strong QB–WR correlation; **rushing quarterbacks break it hardest** — points the QB banks on the ground don't flow to his receivers. Pass-heavy QB-WR stacks have been measured at more than double the correlated value of run-heavy ones.

**Implementation — Gaussian copula.** The standard, and it's simple:

1. Build a correlation matrix `Σ` over the players in the slate/game, populated from the structural rules above (not estimated per-player-pair — you never have enough data).
2. Draw `Z ~ MVN(0, Σ)` via Cholesky: `Z = L·u`, `u ~ N(0,I)`, `L = chol(Σ)`.
3. Map to uniforms `U = Φ(Z)`.
4. Map each `U_i` through the player's own marginal inverse CDF: `FP_i = F_i⁻¹(U_i)`.

This gives correct marginals *and* correct dependence, and it costs one Cholesky of a small matrix plus a matrix-vector product per draw. Entirely feasible on-device for a single game (≈20 players) or a single fantasy matchup (≈20 players).

**Starter correlation matrix (structural template):**

```
              QB    RB1   WR1   WR2   TE    DST(own)  opp_QB  opp_WR1
QB          1.00   0.10  0.55  0.40  0.40   -0.15      0.20    0.15
RB1         0.10   1.00 -0.05 -0.05 -0.05    0.10      0.00    0.00
WR1         0.55  -0.05  1.00 -0.02  0.00   -0.10      0.15    0.10
WR2         0.40  -0.05 -0.02  1.00  0.00   -0.10      0.12    0.10
TE          0.40  -0.05  0.00  0.00  1.00   -0.10      0.12    0.10
DST(own)   -0.15   0.10 -0.10 -0.10 -0.10    1.00     -0.40   -0.35
```

Treat these as tunable constants, validate empirically against nflverse, and **force positive-semi-definiteness** (nearest-PSD projection / eigenvalue clipping) before Cholesky — hand-authored matrices are routinely non-PSD.

### 2.4 Monte Carlo: weekly and season-long

**Weekly H2H win probability:**

```
for i in 1..N:
    draw correlated FP for all starters on both rosters (copula, §2.3)
    my_total[i]  = Σ my starters
    opp_total[i] = Σ opp starters
win_prob = mean(my_total > opp_total)   (+ ½ · P(tie) if league ties)
```

`N = 5,000–10,000` is plenty; the standard error of a probability estimate at N=10,000 is ≤0.5pp. Use a seeded RNG so the same question gives the same answer twice — users lose trust in numbers that jitter.

**Season-long league simulation:**

```
for i in 1..N:
    for each remaining week w:
        for each team: simulate lineup score (with correlation, with lineup-setting behavior)
        resolve scheduled matchups → W/L, points-for
    apply tiebreakers, seed playoffs, simulate bracket
accumulate: P(make playoffs), P(bye), P(seed=k), P(win championship), E[wins], E[points]
```

Industry practice sits at **2,000–10,000 iterations**; 10,000 is the common published number for playoff-odds tools. Season sims should be server-side or run once and cached (see §4).

**Subtleties worth modeling:**
- Teams don't start optimal lineups. Modeling "opponent starts their projected-best lineup" overstates opponent strength by roughly 3–8%. A simple fix: apply a per-manager lineup-efficiency factor estimated from their season history.
- Waiver/trade churn over 8 remaining weeks is unmodelable — communicate playoff odds with a wide honest band, and update weekly.
- Injuries: simulate availability with a per-player weekly availability probability, not just a binary.

### 2.5 Calibration

A probability output is only useful if calibrated. Track and display:

- **Reliability diagram / calibration curve**: bucket all "win probability" predictions into deciles, plot predicted vs observed frequency.
- **Brier score**: `BS = (1/N)Σ(p_i − o_i)²`; compare against a baseline of always predicting 0.5 (BS = 0.25).
- **Brier skill score**: `BSS = 1 − BS/BS_ref`.
- **PIT histogram** for continuous outputs: `u_i = F_i(actual_i)` should be uniform. Non-uniform PIT tells you exactly how your distributions are wrong (U-shaped = too narrow; hump = too wide).
- **CRPS** (continuous ranked probability score) is the right scoring rule for full predictive distributions and strictly better than RMSE for judging a distributional forecast.

Fantasy models are almost always **under-dispersed** (too confident). Expect to need to widen σ after the first calibration pass.

---

## 3. Decision tools built on projections

Ranked by user value per unit of engineering effort.

### 3.1 Start/sit with confidence and win-probability framing

The single highest-usage tool in any fantasy app.

**Do not** frame it as "Player A projects 12.4, Player B projects 11.8, start A." That's a 0.6-point difference against a ~6-point MAE — noise. Frame it as:

```
P(A outscores B) = 54%           ← from correlated simulation
ΔP(win matchup)  = +1.8pp        ← the number that actually matters
Recommendation   = Lean A (low confidence)
```

**Marginal win probability is the correct objective**, and it differs from "higher mean" in two important cases:
- **You're a big favorite** → start the higher-floor player (minimize variance).
- **You're a big underdog** → start the higher-ceiling player (maximize variance). This is the classic result and users love it when an app explains it.

```
ΔWP(swap A→B) = P(win | start B) − P(win | start A)
```

Compute by re-running the simulation with the swap. With shared random numbers (same RNG seed / common random variates across both branches), the variance of the *difference* collapses and 2,000 iterations is enough for a stable answer.

**Confidence levels** should come from the simulation, not from vibes:

| ΔWP | Label |
|---|---|
| ≥ 8pp | Strong start |
| 3–8pp | Start |
| 1–3pp | Slight lean |
| < 1pp | Coin flip — start whoever you like |

"Coin flip" as an honest output is a differentiator. Every competitor manufactures false confidence.

### 3.2 Trade analyzer (roster-context-aware, not a static chart)

Static trade value charts are the industry standard and are wrong, because a player's value depends on **your roster** and **your league settings**.

**Correct methodology — marginal lineup value:**

```
1. Compute ROS projections for every player (weekly distributions).
2. For your roster, simulate the rest of season → baseline P(championship).
3. Apply the proposed trade to both rosters.
4. Re-simulate → new P(championship) for both sides.
5. Report ΔP(championship) for each side. That is the trade grade.
```

This automatically handles everything static charts miss: roster construction, bye weeks, positional surplus (your WR5 is worth ~0 to you), superflex, TE-premium, and the fact that 2-for-1 trades cost a roster spot.

**Cheaper approximation (good enough, far cheaper on-device):** **marginal starting-lineup points over replacement, ROS.**

```
value(player | roster) = Σ_{remaining weeks} [ E[best_lineup(roster ∪ player)] − E[best_lineup(roster)] ]
```

This is the "rebuild both projected starting lineups after the trade" approach — computed by placing players in the roster slot with the lowest opportunity cost.

**Replacement level, done right:**

```
replacement_rank(pos) = n_teams × (starters_at_pos + flex_share_of_pos) + waiver_depth_buffer
VOR(player) = proj_points(player) − proj_points(replacement_rank(pos))
```

Flex/superflex slots add fractional demand to every eligible position — compute `flex_share` by seeing which positions actually fill flex slots at the margin, rather than assuming.

**Things to surface:**
- 2-for-1 penalty (roster spot has value; quantify it as the value of your worst bench player).
- Bye-week collision warning.
- Playoff-weeks SoS of the acquired players (weeks 15–17).
- Consolidation vs. depth framing given the user's current playoff odds (a team at 20% odds should take variance; a team at 85% should take floor).

### 3.3 Waiver wire priority and FAAB bids

**Priority scoring:** rank free agents by `ΔP(championship)` if added (or the cheap proxy: marginal ROS starting-lineup points added), *not* by raw projection. A WR who projects 9 PPG is worthless to a team already starting three better WRs.

```
add_value = ROS_marginal_lineup_points(player | your roster)
          − ROS_marginal_lineup_points(your likely drop | your roster)
```

**FAAB bid recommendation.** Published tiering that matches consensus practice:

| Tier | Description | % of remaining budget |
|---|---|---|
| League-winner | Backup RB inheriting a bell-cow role; elite waiver breakout | **40–70%** (effectively "all of it" for a contender in November) |
| Clear weekly starter | New every-week starter at a position of need | **15–30%** |
| Flex-worthy upside | Ascending role, boom/bust flex | **5–12%** |
| Streamer / dart throw | QB/TE/DST stream, handcuff speculation | **$1–$5 (1–5%)** |

**Make it dynamic, which nobody does well:**

```
bid% = base_tier% 
     × urgency_multiplier(your_playoff_odds, weeks_remaining)
     × scarcity_multiplier(how many rival teams need this position)
     × budget_multiplier(your_remaining_budget / league_avg_remaining)
```

- **Spend aggressively early** (the player helps for 14 weeks) **and again right before the playoffs**; hoard in the dead middle.
- Late-season budget has near-zero option value — if you're 60%+ to make the playoffs in week 12, unspent FAAB is wasted capital. Explicitly tell the user this.
- Show a **bid range** (e.g. "22–31% — bid 27% to be safe") rather than a false-precision single number, plus "expected winning bid" if you can model league bidding history.

### 3.4 Draft tools

**Value-Based Drafting (VBD) baselines** — pick deliberately, and let the user choose:

| Baseline | Definition | Best for |
|---|---|---|
| **VOLS** (Value Over Last Starter) | last player at the position who will be a starter (`n_teams × starters`) | early drafts / season-long |
| **VORP** (Value Over Replacement) | last startable player including waiver replacement, deeper baseline | general purpose, most common |
| **VONA** (Value Over Next Available) | best player at that position you'd still get at your next pick | **live in-draft** — this is the one that's correct during a draft |
| **BEER / man-games** | accounts for games missed and the fact you start replacements some weeks | most theoretically sound; more complex |

```
VORP(p)  = proj(p) − proj(replacement_at_pos)
VONA(p)  = proj(p) − E[proj(best available at pos at my next pick)]
```

VONA requires an **ADP-based availability model**:

```
P(player available at pick k) = 1 − Φ((k − ADP) / ADP_stdev)
E[best at pos at next pick] = Σ over players  P(available) × P(best) × proj
```

This is the heart of a genuinely good draft assistant: it turns "who's the best player" into "who will *not* be there next time," which is the actual draft decision.

**Auction values** — the standard derivation:

```
1. Compute VORP for every player using your league's baseline.
2. Keep only the top (n_teams × roster_size) players — these are the ones that get drafted.
3. total_surplus_dollars = n_teams × (budget − roster_size × $1)
4. $/VORP = total_surplus_dollars / Σ VORP over those players
5. auction_value(p) = $1 + VORP(p) × $/VORP
```

Clamp at $1 minimum. Recompute live as money leaves the room (inflation):

```
inflation = remaining_dollars / remaining_value_dollars
adjusted_value = auction_value × inflation
```

**Tiered rankings.** Drop-offs matter more than ordinal rank. Generate tiers automatically:
- 1-D k-means or Jenks natural breaks on projected points within a position, or
- Gap-based clustering: start a new tier where the gap to the next player exceeds `c × σ` of within-tier spacing.

Tiers are also the correct UI for communicating uncertainty — "these six players are indistinguishable" is honest and useful.

**Best ball ceiling weighting.** In best ball, only your weekly best scores count, so **the objective is the expected maximum, not the expected value.** Rank by something like:

```
best_ball_value ≈ w1 × E[FP] + w2 × P90(FP)          (crude but effective; w2 material)
```

or properly, simulate `E[max over eligible players at the slot]`. High-variance, TD-dependent, spike-week players (deep WRs, TE touchdown merchants, backup RBs with standalone upside) are systematically undervalued by mean-based rankings in best ball. Also: **stack** in best ball (§2.3 — +1.8 ceiling / −1.6 floor is exactly the trade best ball wants).

### 3.5 DFS lineup optimizer

**Formulation: integer linear programming (ILP).** Not greedy — greedy is provably suboptimal on knapsack-with-constraints and gets beaten badly.

```
maximize   Σ_i proj_i · x_i
subject to Σ_i salary_i · x_i ≤ cap
           Σ_i x_i[pos = QB] = 1
           2 ≤ Σ x_i[pos = RB] ≤ 3          (flex-aware)
           3 ≤ Σ x_i[pos = WR] ≤ 4
           1 ≤ Σ x_i[pos = TE] ≤ 2
           Σ_i x_i = 9
           Σ_i x_i[team = t] ≤ 4   ∀t        (site rule / diversification)
           x_i ∈ {0,1}
```

**Stacking as constraints:**
```
x_WR_t ≥ x_QB_t          for at least one WR on QB's team  (forced stack)
Σ_{i in opp(t)} x_i ≥ x_QB_t                                (forced bring-back)
```

**Multi-lineup generation:** add a no-good cut after each solve to force diversity:
```
Σ_{i ∈ L_k} x_i ≤ |L_k| − d        (differ from lineup k by at least d players)
```
plus global exposure caps `Σ_k x_i^k ≤ max_exposure_i × K`.

**Ownership / leverage.** For GPPs you don't want the highest-projection lineup; you want the highest *expected-finish* lineup. Practical objective modifications:
```
leverage_i = proj_i − γ · ownership_i                 (simple)
or          = P(boom_i) / ownership_i                 (leverage ratio)
or          maximize E[FP] subject to Σ ownership_i ≤ O_max
```
Best practice is to **simulate the tournament**: draw the field's lineups from the ownership distribution, draw correlated player scores, and score your candidate lineups by `E[prize]` rather than `E[points]`. That's a server-side job.

**Solver in Kotlin/Android:** there is no great pure-Kotlin MILP solver. Options: (a) implement a branch-and-bound over a DP/knapsack relaxation yourself — very tractable for 9 slots and a few hundred players; (b) use a greedy + local-search (swap 1, swap 2) heuristic which gets within ~0.5% of optimal in milliseconds and is what most mobile optimizers actually do; (c) JNI to an LP solver (heavy, bad for app size); (d) solve server-side. **Recommendation: local-search heuristic on-device for single lineups, server-side ILP for 20–150 lineup GPP builds.** Given this is a free app, DFS optimization is also the lowest-priority feature — it serves a small, already well-served audience.

### 3.6 Playoff odds and championship odds

Covered in §2.4. Output set:
- P(make playoffs), P(first-round bye), P(each seed), P(reach final), P(championship)
- Expected final record, expected points for
- **"What do I need this week"** — P(playoffs | win this week) vs P(playoffs | lose this week). This delta is the most engaging number in the whole feature, and it's free once you have the sim.
- Power rankings from simulated *true strength* (median weekly score) rather than record, which corrects for schedule luck.

### 3.7 Rest-of-season SoS and playoff-week SoS

Standard SoS (sum of opponent win %) is useless for fantasy. Build **positional, projection-based SoS**:

```
SoS(player, weeks W) = Σ_{w ∈ W} [ proj(player, w) − proj_neutral(player) ]
```

i.e., the sum of the matchup adjustments the model already computes. This is internally consistent with the projections — a huge advantage over sites where the SoS page and the projections page disagree.

- Report both **ROS SoS** and **playoff-week SoS (weeks 15–17)** separately; the latter drives trade deadline decisions and is under-served.
- Include bye weeks explicitly.
- Show it as expected points gained/lost, not as a 1–32 rank. "+7.2 points over weeks 15–17" is actionable; "ranked 8th" is not.
- Honesty note: SoS beyond ~4 weeks out is weak signal because defenses change. Say so.

---

## 4. On-device vs server compute

### 4.1 The constraints

- Android phones are fast (modern mid-range ≈ 100–500 MFLOPS+ single-threaded in JVM; a simple RNG draw + arithmetic loop runs tens of millions of iterations/second).
- The binding constraints are **battery**, **thermal**, **cold-start latency**, and **app size** — not raw throughput.
- The app must work **offline**, which means anything required for core UX must either run locally or be cached.
- No paywall → server costs must stay near zero → push work to the client where it's cheap, and make server work *batch* (once per data refresh for all users), never per-user-request.

### 4.2 What is actually expensive

Rough cost model for a Monte Carlo draw of one player-week: ~10–50 ns in optimized Kotlin (primitive `DoubleArray`, no boxing, `java.util.SplittableRandom` or a custom xoshiro256++).

| Workload | Scale | Est. on-device time | Verdict |
|---|---|---|---|
| Single player distribution (10k draws) | 10⁴ | < 1 ms | **on-device, trivially** |
| H2H matchup sim, 2 rosters × 10 starters, 10k iters, with copula | 2×10⁵ draws + Cholesky(20×20) | ~20–60 ms | **on-device** |
| Start/sit ΔWP for 5 candidate swaps (common random numbers) | ~10⁶ draws | ~0.2–0.5 s | **on-device** |
| Full league season sim: 12 teams × 8 weeks × 10 starters × 10k iters | ~10⁷–10⁸ draws | ~5–40 s, heavy battery | **on-device only if optimized + backgrounded; prefer 2k iters or server** |
| Trade analyzer via full championship-odds re-sim (2 rosters × several scenarios) | ~10⁸ | too slow interactively | **use the marginal-lineup-points approximation on-device**; full sim server-side or backgrounded |
| DFS ILP, 20–150 lineups | NP-hard | seconds to minutes | **server-side** (heuristic single-lineup on-device) |
| Ridge regression for opponent adjustment | few hundred² matrix | ms–s, but needs full league PBP data | **server-side** (data volume, not compute) |
| Model training / fitting priors / distribution fitting | full historical corpus | — | **server-side, offline, batch** |

### 4.3 Recommended split

**Server-side (batch, once per data refresh — nightly + post-game + Wednesday/Sunday-morning odds pull):**

1. **All ETL** from nflverse/nflfastR: play-by-play → snap shares, route participation, target shares, air yards, red-zone opportunity, EPA.
2. **Opponent adjustment** (ridge / SAFPA / EPA-based defensive ratings). Needs all 32 teams' data.
3. **Empirical Bayes prior fitting** (α, β, k by position/stat). Changes slowly; ship as constants.
4. **xFP and xTD models** — fit the logistic/regression coefficients offline, ship **coefficients**, not the model.
5. **Prop ingestion + de-vig + prop→mean conversion.**
6. **Ensembling weights** and accuracy tracking.
7. **Baseline projections** for every player: mean, σ, skew/shape, and the **factor decomposition** (§6).
8. **Global correlation matrix** and per-game correlation overrides.
9. **Residual/bootstrap tables** for non-parametric distributions.
10. **DFS multi-lineup ILP** if built at all.
11. **Precomputed ROS aggregates**: ROS projections, SoS, playoff-week SoS, replacement levels, VORP, auction values, tier boundaries.

**Shipped to client (the payload):** a compact bundle per week. Realistic size: ~600 players × (mean, σ, shape, zero-inflation π, ~8 factor contributions, position, team, opponent, game id) ≈ **80–250 KB** as packed binary / protobuf / FlatBuffers, or ~400 KB as gzipped JSON. Plus a ~32×32 defensive rating table and a small correlation matrix. **This is nothing.** Cache it in Room/SQLite and the app works fully offline for the week.

**On-device (Kotlin, runs against the cached bundle):**

1. **League scoring application.** Critical: never send fantasy points from the server. Send *stat projections* (or component means) and apply the user's exact league scoring locally. This handles half-PPR, TE premium, 6-pt passing TDs, return yards, IDP — all for free, offline, instantly.
2. **All Monte Carlo over the user's own league**: H2H win probability, start/sit ΔWP, floor/ceiling percentiles, boom/bust.
3. **Season simulation** at reduced iterations (2,000) with a background WorkManager job for a 10,000-iteration refresh.
4. **All roster-context logic**: optimal lineup solving (trivial — it's a small bipartite matching / greedy-with-flex), marginal lineup value, trade evaluation approximation, waiver add value, FAAB bid computation.
5. **Draft tools**: VORP/VONA/auction values recomputed live against the user's actual league settings and live draft board.
6. **The explainability breakdown** — rendered from the shipped factor contributions, no compute needed.

**Why this split is right:** the server does everything that needs *the whole league's data* or *historical training*; the client does everything that needs *this user's league settings and roster*, which is exactly the part that can't be precomputed anyway (there are too many league configurations to enumerate server-side). It also means server cost scales with *number of players in the NFL*, not *number of users* — essential for a free app.

### 4.4 Kotlin numeric implementation notes

- **Don't reach for a library for Monte Carlo.** Plain `DoubleArray`, `IntArray`, and a hand-rolled PRNG beat any generic ndarray library for this workload. Avoid `List<Double>` (boxing kills you), avoid allocating inside the hot loop, preallocate all buffers.
- **RNG:** `java.util.SplittableRandom` (fast, splittable for parallel streams) or a hand-written xoshiro256++/PCG. `java.util.Random` is synchronized and slow. For Gaussians use the Ziggurat method or cached Box-Muller pairs.
- **Parallelism:** `Dispatchers.Default` with `chunked` iteration ranges across cores gives near-linear speedup on Monte Carlo (embarrassingly parallel). Split the RNG per coroutine, never share.
- **Matrix math (Cholesky, ridge):** for a 20×20 or 32×32 correlation matrix, write the ~20-line Cholesky yourself. If you want a library:
  - **Multik** (JetBrains, Kotlin/Multiplatform): N-d arrays, linear algebra, stats. `multik-kotlin` (pure Kotlin) is recommended for Android — JetBrains notes the OpenBLAS performance difference isn't significant on mobile, and pure Kotlin avoids shipping native libs (app size + ABI splits).
  - **KMath** (SciProgCentre): broader math abstractions, can delegate to Multik for hot paths.
  - **Apache Commons Math** works on Android but is heavy and has been largely unmaintained; prefer the above or hand-rolled.
- **Battery/thermal:** run anything above ~200 ms in a coroutine off the main thread; run season sims via `WorkManager` with battery-not-low + charging constraints for the full-iteration version, and show the cheap 2,000-iteration version instantly. Never spin the CPU while the user is idle.

### 4.5 ONNX Runtime Mobile / LiteRT — warranted?

**Recommendation: no, not for the core projection engine. Yes, possibly for one or two narrow sub-models later.**

Arguments against for the core:

1. **The model isn't the bottleneck — the data is.** A GBM trained on features you compute server-side anyway can just be *evaluated server-side*; you ship the output. There's no latency or privacy reason to run it on the phone.
2. **Explainability is a stated differentiator (§6).** A transparent formula-based projection lets you show an exact additive decomposition. A black-box GBM requires SHAP to explain, which is expensive, approximate, and — critically — SHAP values of a tree ensemble don't sum to anything a user recognizes as "the projection." Formula-based decomposition is *exact by construction*.
3. **App size and complexity.** ONNX Runtime Mobile adds ~3–8 MB; LiteRT similar. For a free app whose core value is a ~200 KB weekly data bundle, that's a poor trade.
4. **Accuracy ceiling is low anyway.** Weekly projections top out around R² 0.23. The gap between a well-built formula model and a tuned GBM on this problem is small — much smaller than the gap between "uses market props" and "doesn't."

Arguments for, in narrow cases:
- If you later build a **learned residual correction** (GBM on ~30 features predicting the model's own error), you could ship it as ONNX/LiteRT. But you can equally ship the tree ensemble as JSON and evaluate it in ~50 lines of Kotlin — tree inference is trivial and needs no runtime.
- If you build an on-device **natural-language "explain this matchup"** feature, that's a genuinely different story and would need a runtime.

**Concrete recommendation:**
- **Core projections: transparent, formula-based, hierarchical, market-anchored.** Every term named and exposed.
- **If you want ML: use it server-side** to *fit the coefficients* of that transparent model (e.g., fit the matchup multipliers, the game-script κ, the wind curve, the shrinkage k's by regression), then ship the coefficients. You get ML-quality parameters with formula-quality explainability. This is the best of both.
- **If you truly need an on-device tree model:** serialize the trees to JSON/FlatBuffers and write the ~50-line evaluator. Skip ONNX/LiteRT entirely until you have a neural model.

---

## 5. Accuracy and evaluation

### 5.1 Metrics

**Point forecasts:**
- **MAE** — primary. Most interpretable ("we're off by 4.9 points on the average WR week"). Robust to the TD-driven outliers that dominate RMSE.
- **RMSE** — secondary; penalizes big misses, which is arguably right for fantasy since big misses are what lose weeks.
- **ME (mean error)** — bias. Season-long projections industry-wide carry a **+21.6 point optimism bias**; weekly are near-neutral (**−0.10** across sources). Track it and correct it — de-biasing is free accuracy.
- **R²** — variance explained. Report it, but explain it (see below).
- **MASE** — MAE scaled by a naive baseline's MAE. `MASE < 1` means you beat the baseline. This is the honest headline number.

**Rank quality (what actually matters for start/sit and draft):**
- **Spearman ρ** and **Kendall τ** within position, within week.
- **Top-N hit rate**: of your projected top-12 RBs, how many finished top-12?
- **Pairwise accuracy**: over all pairs of players a user might choose between, how often did the higher-projected player score more? This is the most decision-relevant metric in the whole list and almost nobody reports it. Realistic values sit around **55–60%** — worth knowing, and worth telling users.

**Probabilistic outputs:**
- **Brier score** and **Brier skill score** vs. a 0.5 baseline for all binary probabilities (win probability, boom probability).
- **Calibration curves** (reliability diagrams) bucketed by decile.
- **CRPS** for the full distribution.
- **PIT uniformity** for distribution shape.
- **Coverage**: does the 80% interval (P10–P90) actually contain the outcome 80% of the time?

### 5.2 Baselines you must beat

Always report against these, because a model that doesn't beat them is worse than useless:

1. **Season-to-date average PPG** — shockingly hard to beat weekly.
2. **Last 4 games average.**
3. **Preseason ranking / ADP-implied projection** (for ROS).
4. **Positional average by projected rank** (i.e., "the RB1 in our ranking scores whatever RB1s historically score").
5. **Public consensus** (FantasyPros ECR or similar) — the real competitive bar.

### 5.3 What accuracy is actually achievable (real published numbers)

**Weekly/DFS projections — 11 seasons (2015–2025), 9 sources, 6,000+ source-week combinations** (Fantasy Football Analytics):

| Position | Best MAE (11-season) | Source |
|---|---|---|
| QB | **6.20** | FantasyPros (FFA Weighted 6.22, CBS 6.23) |
| RB | **5.20** | FFA Average (FantasyPros 5.22) |
| WR | **4.94** | FFA Weighted (NumberFire / FantasyPros 4.95) |
| TE | **3.85** | ESPN (NumberFire 3.86) |

- **R² for weekly projections: 3–23% by position.** Best recent 3-season figure was RBs at 23.3% (FFA Average/Weighted).
- Mean error across all sources: **−0.10** (essentially unbiased).
- Consistency: FFA Weighted best QB CV at **16.64%**; ESPN had the best overall TE MAE but the *worst* recent TE consistency at **35.50% CV** — a good illustration of why you should report both accuracy and stability.
- **Aggregation: FFA Average beat individual sources in 63% of head-to-head comparisons.**

**Season-long projections — 12 seasons (2014–2025), 11 sources:**

| Position | Best MAE (season total points) |
|---|---|
| QB | 61.0 (FantasyPros); FFA Average 61.7; ESPN recent 86.7 (worst) |
| RB | 52.2 (CBS); FantasyPros 52.4 |
| WR | 40.2 (FantasyPros); FFToday recent 40.9 |
| TE | 31.4 (FantasyPros); FFToday recent 29.4 |

- **R² for season projections: 14–26%**, RBs most predictable, QBs least.
- Bias: **+21.6 points average optimism**; QB bias nearly doubled from +22.5 to +46.5 recently.
- **Aggregation: FFA Average beat individual sources in 69% of comparisons, average rank 4.2 of 11. Equal weighting beat weighted averaging.**

**What this means for the product:** a well-built free model that (a) uses opportunity-based projection, (b) regresses TDs, (c) anchors to props, and (d) ensembles, should land in the same MAE band as the best commercial sources — roughly **QB ~6.2, RB ~5.1, WR ~4.9, TE ~3.8 weekly MAE in PPR**. That is a reasonable, achievable, and *stateable* target. Anyone claiming dramatically better is either overfitting or lying.

### 5.4 Ship accuracy tracking in the app (the trust play)

This is the highest-leverage differentiator in the entire spec and costs almost nothing.

**Build a public, permanent, in-app accuracy page:**
- Weekly MAE / RMSE by position, this season and all-time.
- **Comparison against the naive baselines** and, if available, against public consensus.
- Calibration curve for win probability: "when we said 70%, you won 68% of the time."
- Hit rate on start/sit calls, bucketed by our stated confidence: "Strong start calls hit 71%; coin-flip calls hit 51%."
- **Our biggest misses this week**, with a one-line reason. Owning misses publicly buys more trust than any amount of accuracy claiming.
- A plain-English "what R² = 0.18 means" explainer: *"Football is mostly unpredictable week to week. Nobody's projections explain more than about a quarter of what happens. Ours are competitive with the best paid services — but always treat a 1-point projection gap as a coin flip."*

No major competitor does this. It converts the low-R² problem from a weakness into a credibility asset.

---

## 6. Explainability

### 6.1 Design the model so explanation is free

The key architectural decision: make the projection an **additive decomposition in fantasy-point space**, even though the underlying model is multiplicative on rates. Compute the multiplicative model, then attribute the deltas:

```
FP_baseline   = neutral-context projection (league-average opponent, neutral script, no weather, healthy)
FP_final      = FP_baseline × Π multipliers

Attribution (sequential / Shapley-averaged over orderings):
  Δ_matchup    = FP after applying matchup    − FP before
  Δ_gamescript = FP after applying script     − FP before
  Δ_weather    = FP after applying weather    − FP before
  Δ_injury     = FP after applying injury     − FP before
  Δ_role       = FP after applying role change− FP before
  Δ_market     = FP after market blend        − FP before

FP_final = FP_baseline + Σ Δ_i    (exactly, by construction)
```

Because the multipliers are order-dependent, average the attributions over random orderings (this is exactly the Shapley value for a multiplicative game) or use the simpler log-space decomposition:

```
log(FP_final) = log(FP_baseline) + Σ log(m_i)
Δ_i = (FP_final − FP_baseline) × log(m_i) / Σ_j log(m_j)
```

This guarantees the parts sum to the whole, which is the property users actually need. **Precompute these Δ values server-side and ship them in the bundle** — the client just renders.

### 6.2 What to show

**The one-screen projection card:**

```
Player X — Week 8 — 14.2 pts  (Floor 6.1 · Ceiling 24.8)

  Baseline (role + recent form)        12.8
  + Matchup vs DEN secondary           +1.4    ← 28th vs slot WRs by YPRR allowed
  + Game script (implied total 27.5)   +0.6    ← 7-pt underdog, pass-leaning
  − Weather (18 mph wind, outdoor)     −0.9    ← deep targets suppressed
  + Injury (WR1 out)                   +0.3    ← +4% projected target share
  ────────────────────────────────────────
  Projection                           14.2

  Why the range is wide: 42% of his projection is touchdown-dependent.
```

**Waterfall chart** is the natural visualization — baseline bar, signed contribution bars, final bar.

**Also surface:**
- **Confidence / uncertainty badge** driven by sample size and shrinkage weight: "Low confidence — only 3 games in this role."
- **Which inputs are stale**: "Odds from Thu 9:15am. Injury report from Fri."
- **The volume story**, because that's the actual model: "Projected 7.4 targets on 31 routes (68% route participation, 24% target share)."
- **TD dependence %**: `6 × λ_TD / FP_total`. High TD dependence = high variance = explains why the range is wide. Nobody shows this and it's the single most explanatory number for weekly volatility.
- **What would change this**: "If the line moves to a 3-point spread, this drops ~0.8."
- **Comparison to consensus**: "We're 1.9 points above consensus, mostly because of TD regression — he's scored 5 TDs on 6 red-zone targets."

### 6.3 Trust mechanics

- **Let users override.** Let a user mark a player as "I think he plays" / adjust a role assumption and see the projection recompute. Systems that let owners modify system-created projections to reflect their own information are demonstrably more trusted and more used.
- **Explain the shrinkage.** "We're using 60% league-average TD rate because 4 games isn't enough to trust his 3 TDs." Users who've heard of regression love seeing it named; users who haven't learn something.
- **Never show false precision.** Show 14.2, not 14.23. And show the range as prominently as the point.
- **Admit when you don't know.** "No projection — role unclear after the trade" is better than a made-up number.
- **Link every factor to its evidence** (the underlying stat, with the sample size).

---

## 7. Concrete build plan

### Phase 1 — credible baseline (the whole value prop)
1. nflverse ETL → snap/route/target/carry shares, red-zone opportunity, EPA-based defensive ratings.
2. EWMA volume model + heavy shrinkage on efficiency + xTD model.
3. Ridge/SAFPA opponent adjustment, capped.
4. Vegas implied totals + spread → game script.
5. Weather gate (dome check first).
6. Ship stat components + σ + factor decomposition; apply league scoring on-device.
7. Explainability card + floor/ceiling.

### Phase 2 — distributions and decisions
8. On-device correlated Monte Carlo (Gaussian copula).
9. Start/sit with ΔWP and honest confidence labels.
10. H2H win probability + playoff odds (2k iters foreground / 10k background).
11. Accuracy tracking page, live from week 1.

### Phase 3 — market anchoring and advanced tools
12. Prop ingestion + de-vig + Poisson/Gamma inversion + ensemble blend.
13. Trade analyzer (marginal lineup value; full-sim version for big trades).
14. Waiver value + dynamic FAAB.
15. Draft tools: VONA, tiers, auction values, live inflation.
16. ROS + playoff-week SoS.

### Deprioritize
- DFS optimizer (small audience, well-served, highest compute cost).
- On-device ML runtimes (ONNX/LiteRT) — no current justification.
- Weighted ensembling (equal weights measurably beat weighted ones).

---

## Sources

- [PFF — Weighted opportunity: A better fantasy football predictor than raw touches](https://www.pff.com/news/fantasy-football-weighted-opportunity-a-better-fantasy-football-predictor-than-raw-touches)
- [PFF — Expected fantasy points: The most efficient fantasy players](https://www.pff.com/news/fantasy-football-expected-fantasy-points-the-most-efficient-fantasy-players)
- [Fantasy Projection Lab — Projection Models Explained: Methodologies and Approaches](https://fantasyprojectionlab.com/projection-models-explained/)
- [Fantasy Projection Lab — NFL Fantasy Projections: How the Lab Approaches Football](https://fantasyprojectionlab.com/nfl-fantasy-projections)
- [Fantasy Projection Lab — Vegas Lines and Fantasy Projections](https://fantasyprojectionlab.com/vegas-lines-and-fantasy-projections)
- [RotoWire — What are projections in fantasy football?](https://www.rotowire.com/faq/what-are-projections-in-fantasy-football-a43ccdf0)
- [ESPN — 2025 fantasy football expected fantasy points (xFP)](https://africa.espn.com/fantasy/football/story/_/id/46169084/2025-fantasy-football-expected-fantasy-points-xfp-te)
- [The Fantasy Footballers — Identifying Touchdown Regression Candidates](https://www.thefantasyfootballers.com/analysis/identifying-2023-touchdown-regression-candidates-wrs-fantasy-football/)
- [FantasyPros — Touchdown Regression Report](https://www.fantasypros.com/nfl/reports/touchdown-regression.php)
- [ESPN — Players who will score more TDs this season](https://www.espn.com/fantasy/football/story/_/id/49178691/2026-fantasy-football-rankings-projections-more-touchdowns-td)
- [FanDuel Research — Touchdown Regression: What It Is and How to Use It](https://www.fanduel.com/research/touchdown-regression-what-it-is-and-how-to-use-it-for-player-prop-bets-fantasy-football)
- [Sharp Football — Wide Receiver Stats That Matter for Fantasy Football](https://www.sharpfootballanalysis.com/fantasy/wide-receiver-stats-that-matter-fantasy-football-2024/)
- [4for4 — The Most Predictable Wide Receiver Stats](https://www.4for4.com/2023/preseason/most-predictable-wide-receiver-stats)
- [Fantasy Football Blueprint — Target Share, Air Yards, and Which Receiver Stats Actually Predict Next Season](https://www.fantasyfootballblueprint.com/2026/08/05/target-share-and-air-yards/)
- [Fantasy Points — Points Allowed, Schedule Adjusted](https://www.fantasypoints.com/nfl/stats/points-allowed/schedule-adjusted)
- [FTN — Fantasy Points Against, Adjusted for Schedule with DVOA](https://ftnfantasy.com/nfl/dvoa-points-against)
- [Sports Info Solutions — Evaluating NFL Defenses by Expected Points](https://www.sportsinfosolutions.com/2019/10/02/evaluating-nfl-defenses-by-expected-points/)
- [nfelo — What are Expected Points Added (EPA) in the NFL](https://www.nfeloapp.com/analysis/expected-points-added-epa-nfl/)
- [ESPN — Expected points and EPA explained](https://www.espn.com/nfl/story/_/id/8379024/nfl-explaining-expected-points-metric)
- [Sharp Football — NFL Implied Team Totals Tool](https://www.sharpfootballanalysis.com/fantasy/nfl-implied-team-totals-tool/)
- [The Fantasy Footballers — Over & Undervalued Players Based on Vegas Implied Totals](https://www.thefantasyfootballers.com/articles/over-undervalued-fantasy-football-players-based-on-vegas-implied-totals/)
- [Advanced Football Analytics — Weather Effects on Passing](http://www.advancedfootballanalytics.com/2012/01/weather-effects-on-passing.html)
- [PFF — The Factors: Wind's influence on completion percentage](https://www.pff.com/news/fantasy-football-the-factors-week-14-2017)
- [Fantasy Life — Does Wind Matter in Fantasy Football?](https://www.fantasylife.com/articles/best-ball/does-wind-matter-in-fantasy-football)
- [Fantasy Life — The Effects of Weather on Fantasy Football 2026](https://www.fantasylife.com/articles/fantasy/the-effects-of-weather-on-fantasy-football-2026)
- [The Spax — Analyzing the Effect of Weather in the NFL](https://www.thespax.com/nfl/analyzing-the-effect-of-weather-in-the-nfl/)
- [Action Network — How to Remove Juice/Vig from Sports Betting Odds](https://www.actionnetwork.com/education/remove-juice-vig)
- [OddsShopper — No Vig Odds: How to Remove the Vig & Find True Odds](https://www.oddsshopper.com/articles/betting-101/how-to-remove-the-vig)
- [Fantasy Football Analytics — We Analyzed 12 Seasons of Fantasy Football Projections](https://fantasyfootballanalytics.net/2026/08/we-analyzed-12-seasons-of-fantasy-football-projections-heres-what-we-found.html)
- [Fantasy Football Analytics — We Analyzed 11 Seasons of DFS Projections](https://fantasyfootballanalytics.net/2026/09/we-analyzed-11-seasons-of-dfs-projections-heres-what-we-found.html)
- [Fantasy Football Analytics — Which Projections Are Most Accurate?](https://fantasyfootballanalytics.net/which-projections-are-most-accurate)
- [FantasyPros — In-Season Accuracy Methodology](https://www.fantasypros.com/about/faq/football-inseason-accuracy-methodology/)
- [Fantasy Football Analytics Textbook — Simulation: Bootstrapping and the Monte Carlo Method](https://isaactpetersen.github.io/Fantasy-Football-Analytics-Textbook/simulation.html)
- [srome.github.io — Making Fantasy Football Projections Via A Monte Carlo Simulation](http://srome.github.io//Making-Fantasy-Football-Projections-Via-A-Monte-Carlo-Simulation/)
- [RotoWire — Should You Stack in Fantasy Football? The Data on Correlated Draft Picks](https://www.rotowire.com/football/article/does-stacking-work-in-fantasy-football-what-four-years-of-data-say-about-drafting-correlated-players-2026-131409)
- [Subvertadown — Pairing/Stacking Analysis: Correlations Between the Different Fantasy Positions](https://subvertadown.com/article/pairing-stacking-analysis-correlations-between-the-different-fantasy-positions)
- [Subvertadown — Guide to VBD baselines: VOLS vs. VORP vs. Man-games (BEER+)](https://subvertadown.com/article/guide-to-understanding-the-different-baselines-in-value-based-drafting-vbd-vols-vs-vorp-vs-man-games-and-beer-)
- [FantasyPros — Draft Strategy: VORP, VOLS & VONA](https://www.fantasypros.com/2025/06/fantasy-football-draft-strategy-value-based-drafting-vorp-vols-vona/)
- [FantasyPros Support — What is value-based drafting?](https://support.fantasypros.com/hc/en-us/articles/115005868747-What-is-value-based-drafting-What-do-player-draft-values-mean-VORP-VONA-VOLS-VBD)
- [Footballguys — Cracking the Code: Developing Auction Values for your League](https://forums.footballguys.com/threads/cracking-the-code-developing-auction-values-for-your-league.808938/)
- [Signals — FAAB & Waiver Wire Strategy](https://signalsfantasy.com/guides/faab-waiver-wire-strategy)
- [4for4 — The Ultimate Guide to Waiver Wire & FAAB Strategy](https://www.4for4.com/2025/preseason/ultimate-guide-waiver-wire-faab-strategy-2025)
- [RotoWire — Fantasy Football Trade Analyzer methodology](https://www.rotowire.com/football/article/fantasy-football-trade-analyzer-find-and-grade-redraft-fantasy-football-trades-in-2026-134904)
- [Medium (Zachary Levonian) — Integer Linear Programming with PuLP: Optimizing a DraftKings NFL lineup](https://zwlevonian.medium.com/integer-linear-programming-with-pulp-optimizing-a-draftkings-nfl-lineup-5e7524dd42d3)
- [TCB Analytics — Linear Programming for an NFL Daily Fantasy Optimizer](https://tcbanalytics.com/2019/09/19/linear-programming-for-an-nfl-daily-fantasy-optimizer/)
- [RotoWire — When to Use an NFL Optimizer vs Manual DFS Lineup Building](https://www.rotowire.com/football/article/use-nfl-optimizer-instead-manual-lineup-building-115771)
- [My Fantasy Analyzer — Playoff Odds Calculator (10,000 simulations)](https://myfantasyanalyzer.com/playoff-odds/)
- [richabdill.com — Comparing fantasy teams using Monte Carlo simulation of schedules in R](https://richabdill.com/robsim/)
- [GitHub — Kotlin/multik](https://github.com/Kotlin/multik)
- [JetBrains Blog — Multik 0.2: Multiplatform, With Support for Android and Apple Silicon](https://blog.jetbrains.com/kotlin/2022/07/multik-0-2-multiplatform-with-support-for-android-and-apple-silicon/)
- [GitHub — SciProgCentre/kmath](https://github.com/SciProgCentre/kmath)
- [DZone — Edge AI: TensorFlow Lite vs. ONNX Runtime vs. PyTorch Mobile](https://dzone.com/articles/edge-ai-tensorflow-lite-vs-onnx-runtime-vs-pytorch)
- [Cactus — ONNX Runtime vs TensorFlow Lite: On-Device ML comparison](https://cactuscompute.com/compare/onnx-runtime-vs-tensorflow-lite)
- [Fora Soft — On-Device AI on Android: 2026 Build Guide](https://www.forasoft.com/blog/article/neural-networks-on-android-369)
- [Nathan Braun — Fantasy Math (distribution-based start/sit)](https://nathanbraun.com/fantasymath/)
