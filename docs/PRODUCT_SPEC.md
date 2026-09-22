# Gridiron — Product & Technical Specification

**A personal-use NFL fantasy football analytics and prediction app for Android.**

Status: Draft v0.2 — personal build, pre-implementation
Target device: **Samsung Galaxy S24 Ultra** (single-device app)
Last updated: 2026-09-22

---

## 1. What this is

A private fantasy football analytics tool, built for one user, sideloaded to one phone. Not distributed, not monetized, not published.

That constraint is a feature. A complete commercial fantasy toolkit costs roughly **$337/yr stacked across five products** — rankings at FantasyPros, advanced metrics at PlayerProfiler, dynasty values at KeepTradeCut, ADP at Fantasy Football Calculator, and your roster at ESPN or Sleeper. Nobody joins that data, and the mobile experiences are uniformly poor.

Roughly **85% of what those products sell is computable from `nflverse`** — CC BY 4.0, play-by-play back to 1999, snap counts, depth charts, injuries, Next Gen Stats, PFR advanced stats and FTN charting, with no API key and no rate limit.

### 1.1 What personal use changes

Most of the hard constraints in a distributed version of this app were *distribution* constraints. They do not apply here:

| Constraint | Status |
|---|---|
| Google Play gambling policy (odds display) | **Gone.** Show full lines, totals and props. |
| Data Safety form, content rating, AAB, Play Asset Delivery | **Gone.** |
| `targetSdk 36` mandate | **Gone.** Play requirement only. |
| Ad monetization and its knock-on effects | **Gone.** No ads. |
| Sleeper's non-commercial API terms | **Satisfied.** Personal use is non-commercial. No licensing email needed. |
| Open-Meteo's non-commercial terms | **Satisfied**, though `api.weather.gov` remains the better source (public domain, no key). |
| NFL trademark exposure (logos, headshots) | **Effectively moot.** Team logos and headshots are fine in an app only you run. |
| ESPN cookie custody | **Moot.** Your own credentials, your own device. |
| Cloudflare Worker proxying the odds API key | **Unnecessary.** Embed the key. |
| FantasyPros / PFF / PlayerProfiler | **Available if you subscribe.** Their personal-use tiers permit exactly this. Not required — the free stack stands alone. |

**What still applies** is technical courtesy, not legal risk. Rate limits are enforced by servers regardless of your intent: Pro-Football-Reference jails you for up to a day above ~20 req/min, and Sleeper's 1,000 req/min is real. Cache aggressively and back off politely. That is the whole compliance story now.

---

## 2. Design goals

Ordered by what actually matters for a single-user tool.

1. **Every stat, sortable and filterable.** The core ask. 200+ metrics, organized, comparable, with week-range recomputation.
2. **N-way comparison.** Every commercial tool compares exactly two players. Four to six side by side, across twenty columns, sorted.
3. **Transparent projections.** A factor waterfall on every number — baseline → matchup → game script → weather → injury. No black box, because you are the only person who has to trust it.
4. **Works on Sunday morning with no signal.** Offline-first, non-negotiable.
5. **Your exact league scoring, applied everywhere.** Not a PPR toggle — real settings, recomputing fantasy points, boom/bust, VORP and DvP.

**Non-goals:** user accounts, onboarding flows, social features, UGC, monetization, multi-device sync, backwards compatibility with old Android versions, and broad device support.

---

## 3. Data architecture

### 3.1 Core rule: the client still never touches upstream sources

Personal use doesn't shrink the payloads. Measured:

| Upstream resource | Raw size | After normalization |
|---|---|---|
| `depth_charts_2026.csv` | **52.3 MB** | ~50 KB |
| Sleeper `/v1/players/nfl` | **14.7 MB** | ~200 KB |
| ESPN `/nfl/injuries` | **9.0 MB** | ~30 KB |

A phone should not parse a 52 MB CSV on a Sunday morning. ETL stays server-side; the app consumes compact derived artifacts.

### 3.2 Pipeline

```
GitHub Actions (public repo → unlimited standard-runner minutes)
  └─ scheduled ETL, normalize with polars / duckdb
      └─ publish to GitHub Releases (Fastly-backed CDN, ETag support)
          ├─ manifest.json        (tiny; polled by WorkManager)
          ├─ week-NN.json.gz      (delta payloads)
          └─ stats.sqlite.gz      (prebuilt, pre-indexed, pre-ANALYZEd)
              └─ Android: OkHttp ETag → Room → Compose
```

**Keep the repo public** if you use GitHub Actions — private repos cap at 2,000 free minutes/month, public repos are unmetered on standard runners. The ETL code is not sensitive; the odds API key lives in Actions secrets, and the app embeds its own copy.

**Alternative worth considering:** run the ETL locally on a PC and `adb push` the database. Zero infrastructure, full control, and entirely reasonable for one user. The GitHub Actions path is better only because it runs on schedule without you being at a machine — which matters at 7am on a Sunday.

**Rejected: Supabase.** Free projects auto-pause after 7 days without requests — fatal across a February–August offseason.

**No Cloudflare Worker.** It existed solely to keep an API key out of a distributed APK.

### 3.3 Sources

| Source | License | Auth | Role |
|---|---|---|---|
| nflverse-data | CC BY 4.0 | none | **Foundation** — PBP, snaps, depth charts, injuries, NGS, PFR adv, QBR, combine, draft |
| api.weather.gov | Public domain | User-Agent | Game-day weather (wind) |
| Fantasy Football Calculator | Free, attribution | none | ADP |
| The Odds API | Free Starter tier | key (embedded) | Lines, totals, player props — 500 credits/mo is ample for one user; ETag 304s cost nothing |
| Sleeper | Non-commercial (satisfied) | none | League sync, trending adds/drops |
| ESPN undocumented | No license | cookies | Inactives, private league sync |
| Pro Football Reference | — | — | Respect ~20 req/min. Mostly unnecessary — nflverse already relicenses the useful parts |
| FantasyPros / PFF | Paid personal tiers | sub | Optional. The free stack does not need them |

**Two traps found by live endpoint testing:** `nextgen_stats` is *not* season-partitioned (`ngs_passing.csv.gz`, not `ngs_2026_passing.csv.gz` → 404), and `contracts/historical_contracts.csv` 404s. ESPN's host moved to `lm-api-reads.fantasy.espn.com`; the legacy `fantasy.espn.com` v3 host is unreliable in 2026.

### 3.4 Freshness

| Tier | Content | Cadence |
|---|---|---|
| Live | Scores, in-game stats | Foreground polling only — never WorkManager (15-min floor) |
| Near-live | Inactives (11:30am ET Sun), odds | ESPN + odds refresh |
| Daily | Injuries, depth charts, rosters, ADP, projections | 07:00 UTC |
| Cyclic | Snap counts, PFR advanced, FTN charting | 00/06/12/18 UTC |
| Seasonal | Historical PBP, combine, draft capital | On demand |

**Sunday inactives remain the one genuinely awkward feed.** `nflverse` injuries refresh once daily at 07:00 UTC, which is useless for an 11:30am ET inactives list. ESPN's unofficial endpoints are the practical answer — and as a personal app you can simply call them directly from the device. Make every ESPN-derived field optional so the app degrades rather than crashes when they change.

---

## 4. The metrics engine

~450 metrics catalogued in [`research/research-stats-catalog.md`](research/research-stats-catalog.md). Computability tiers: **A** = free from play-by-play, **B** = free auxiliary feed, **C** = proprietary but rebuildable in-house, **D** = licensed. Tier C ships as labelled in-house models ("xFP — my model"). Tier D is now optional rather than excluded, if you subscribe to something.

### 4.1 Architectural principle: scoring is configuration, not columns

A `ScoringProfile` — PPR variants, TE premium, first-down bonuses, yardage bonuses, negative points, IDP, kicker, D/ST tiers, roster and flex settings — drives **live recomputation** of fantasy points, boom/bust rates, VORP, DvP and auction values.

Consequence for the payload: **ship stat components, never fantasy points.** The server sends projected receptions, yards and TDs; the device applies your scoring locally. Every format then works offline for free, and switching between your leagues is instant.

### 4.2 Highest-value metrics

**Opportunity — build first; opportunity is far stickier than efficiency**

Snap share + 3-week rolling trend (the #1 waiver signal) · route participation · target share · **targets per route run (TPRR)** (>22% elite WR) · air yards share · **WOPR** = `1.5 × target_share + 0.7 × air_yards_share` (best single composite; elite >0.70) · aDOT · opportunity share (>70% = bellcow) · weighted opportunities = `carries + ~2.6 × targets` · **green-zone (inside 10) and goal-line (inside 5) carries** — 74% and 68% of rushing TDs originate there · end-zone target share (an end-zone target is worth ~3.0 pts vs ~1.8 from the 19)

**Efficiency**

YPRR (top-20 YPRR WRs scored ~154% more the next season than bottom-20) · RACR · YAC over expected · RYOE/att · rushing success rate (beats YPC, which is notoriously unstable) · EPA/dropback · CPOE · QB designed rushes and rushes inside the 5 (the biggest QB fantasy differentiator) · pressure rate and time to throw

**Fantasy-specific**

**xFP/game** (opportunity-only baseline — a *better* forward predictor than actual FPPG) · **FPOE** = actual − expected (the buy-low/sell-high engine) · FP per opportunity · boom / bust / start-worthy rate · floor (p10) and ceiling (p90) · spike weeks · VORP with settings-aware replacement level

**Context**

Team PROE (actual − `xpass`) · situation-neutral pace · **implied team total** = `(total − team_spread) / 2` · schedule-adjusted DvP and ROS/playoff-week SoS (raw fantasy-points-allowed is badly schedule-confounded) · adjusted line yards (explains ~29% of RB half-PPR production) · **practice participation trend** DNP→LP→FP, Friday being the actionable day · **wind** — the only weather variable that reliably matters, effects beginning ~15 mph, ~15–20% passing drop at 20+

**IDP** — defensive snap share, box/safety rate. Nearly all IDP metrics are tier A.

### 4.3 Storage model

Long/narrow weekly fact table `(player_id, season, week, metric_id, value)` plus a **metric-metadata table** carrying definition, formula, what-it-predicts, stability and distribution.

Adding a metric becomes a data change, not a migration — and the metadata table powers info sheets, column search and empty states for free. Materialize wide views per stat-pack for render speed.

---

## 5. Prediction engine

Full methodology with formulas in [`research/research-prediction-models.md`](research/research-prediction-models.md).

**Stance: opportunity-first, market-anchored, regression-heavy, transparent.**

### 5.1 Six layers

1. **Volume cascade** — never project points directly. Team plays → pass/rush split → snap share → routes/carries → target share → targets → receptions → yards. Opportunity is the only genuinely stable input.
2. **Shrunk efficiency** — James-Stein blend `w = n/(n+k)` toward positional baseline, `k` fit server-side per (position, stat). **TD rate gets the heaviest shrinkage**: YoY correlation ~0.28 vs ~0.70–0.75 for target share. Replace observed TDs with opportunity-derived xTD. The biggest free edge available — historically **66.3% of low-TD WR/TE with stable volume scored more the following season**.
3. **Time weighting** — EWMA, half-life by signal: aggressive on role (H≈2–3 games), mild on efficiency (H≈8–12), **never on TD rate**. Regime-break flags (new team, OC, QB) zero the prior.
4. **Matchup** — discard raw DvP; it measures schedule, not defense. Ridge-regression two-way opponent adjustment applied to *rate* inputs. Cap multipliers: ±15% efficiency, ±5% volume, ±20% TD.
5. **Game script** — implied totals drive TD share and play volume; spread drives pass rate (~0.4–0.8pp per point). Weather gated behind `is_outdoor`, quadratic wind penalty above ~12 mph.
6. **Market anchor + ensemble** — de-vig props, invert to means (**anytime-TD → `λ = −ln(1−p)` is the cleanest single win in the pipeline**), inverse-variance blend with the model. Ensemble 3–6 signals with **equal weights**: published 11/12-season studies show consensus beats individual sources in 63–69% of head-to-heads, and equal weighting beats tuned weighting.

With no Play Store constraint, **player props are available without hedging** — they are arguably the sharpest freely-obtainable projection input in existence, and you can now use and display them directly.

### 5.2 Distributions, not point estimates

Every projection is a distribution — NegBinom targets → Binomial receptions → Gamma yards → Poisson TDs, zero-inflated for a realistic floor. Multi-player questions use a **Gaussian copula** with a structural correlation matrix (QB-WR1 ≈ 0.55, QB-TE ≈ 0.40, same-team WR-WR ≈ −0.02, RB-WR negative, bring-back positive).

### 5.3 Compute split

**Server (batch, ~600 players):** all ETL, ridge opponent adjustment, empirical-Bayes prior fitting, xFP/xTD model *fitting*, prop ingestion and de-vigging, ensembling, correlation matrix, residual/bootstrap tables, ROS aggregates, replacement levels, auction values, tiers.

**Payload:** ~600 players × (mean, σ, shape, zero-inflation π, ~8 factor contributions, ids) ≈ **80–250 KB packed, ~400 KB gzipped**.

**On-device:** league-scoring application plus all Monte Carlo — H2H win probability, start/sit ΔWP with common random numbers, season simulation, optimal-lineup solving, trade and waiver marginal value, live VONA/auction inflation, explainability rendering.

**The S24 Ultra changes the sizing here.** The published timings (10k-iteration H2H ≈ 20–60 ms) were midrange estimates. On a Snapdragon 8 Gen 3 with 12 GB RAM you can be substantially more aggressive: run 10k season simulations in the foreground rather than deferring to WorkManager, and hold far more of the dataset resident in memory. Benchmark before assuming, but plan for headroom rather than scarcity.

Implementation: plain `DoubleArray` + `SplittableRandom`, preallocated buffers, `Dispatchers.Default` parallel chunks. Hand-roll the ~20-line Cholesky; `multik-kotlin` if a library is wanted.

**ONNX Runtime Mobile / LiteRT: rejected.** Adds 3–8 MB to an app whose core payload is 200 KB, the model isn't the latency bottleneck, and it destroys explainability — SHAP on a GBM is approximate and sums to nothing recognizable. **Use ML server-side to fit the coefficients of a transparent model, then ship the coefficients.**

### 5.4 Explainability

Compute multiplicatively, attribute additively via log-space decomposition so parts sum *exactly* to the whole:

```
Δᵢ = (FP_final − FP_base) × log(mᵢ) / Σ log(mⱼ)
```

Precompute Δs server-side; the client renders a waterfall. Also surface **TD-dependence %** = `6λ_TD / FP` — it explains weekly volatility better than any other single number.

### 5.5 Accuracy

Published PPR weekly MAE to aim at: **QB 6.2 · RB 5.1 · WR 4.9 · TE 3.8**. Weekly R² is only 3–23%; season-long 14–26%. Industry season-long projections carry a **+21.6 point optimism bias** — de-bias for free accuracy.

Build the accuracy tracker anyway, even though there's nobody to impress. Its real value is personal: it tells you which of your own model's components are actually working, and whether to trust a given week's start/sit call.

---

## 6. Features

### 6.1 The Grid (flagship)

Frozen player-name column; horizontally scrollable stat columns; long-press header to sort; **stat packs** (Basic / Volume / Opportunity / Efficiency / Next Gen / Red Zone / Consistency / Matchup / Dynasty / DFS / IDP) as primary navigation.

- Two-tier filtering: persistent quick bar (position, week range, team, min-snaps) applying instantly; advanced sheet with per-column operators, explicit Apply, live match count.
- **Week-range filtering with rate-metric recomputation** — rates must recompute over the range, not merely sum. Most commercial tools get this wrong.
- Minimum-opportunity thresholds on every efficiency leaderboard, sample size visible in-row.
- Global toggles: per-game / total / per-opportunity, and raw / rank / percentile / z-score.
- Sparklines in every row (last 6 weeks of the sorted column) — the biggest density-per-pixel win available.
- Saved presets ("Buy-low WRs", "Route share risers", "Green-zone hogs", "Soft playoff schedules"). Build these as you discover the views you actually use.
- CSV export.

**S24 Ultra affordances worth exploiting:**

- **S Pen hover** — hover a column header to preview the metric definition without tapping, and hover a cell for the underlying raw counts. This is a genuinely good fit for a dense grid and most apps can't assume it exists.
- **Landscape and DeX** — a 6.8" 1440p panel in landscape fits far more columns than the ~25 the midrange analysis assumed. Design the column cap as a setting, not a constant.

### 6.2 Comparison

- **Persistent bottom compare tray, 0–4 players**, fed by long-press on any Grid row.
- **Sorted horizontal percentile bar strips as the primary view.** Research consensus (StatsBomb, Opta, PyMC Labs) is that bars beat radars: linear encoding, non-adjacent values directly comparable, no axis-order artifacts. Grouped Opportunity / Efficiency / Scoring / Context with section composites.
- **Percentile ranks on every metric** — what makes 60+ unfamiliar stats legible without learning any of them.
- Radar as a secondary "shape" view: max 6–8 axes, percentile scales, fixed semantic axis order, max 2 players.
- **Opportunity-vs-efficiency scatter** — the highest-insight chart available. Killer variant: **xFP/game vs actual FP/game with a y=x line** — above = sell-high, below = buy-low. Explains regression with zero words.
- Head-to-head table, metrics as rows, players as columns, diff column, "only show metrics differing by >1 decile" toggle. Also compares a player to *himself* across seasons or week ranges.

### 6.3 League sync

Behind a `LeagueProvider` adapter.

- **Sleeper** — no token, read-only, documented. Cache `/players` once daily.
- **ESPN private leagues** — extract `SWID` and `espn_s2` from desktop DevTools once, store them in `EncryptedSharedPreferences` or the Keystore, call ESPN directly from the device. Since these are your own credentials on your own phone, the custody concerns that would burden a distributed app simply don't exist. Public ESPN leagues work unauthenticated.
- **Manual roster entry** — still worth building as a fallback for any platform the adapters don't cover, but no longer needs to be a polished onboarding path.
- **Yahoo** — only if you play there. Requires a human-reviewed application; legacy apps reportedly began receiving 403s around 2026-07-22, so don't assume grandfathering.

### 6.4 Decision tools

1. **Start/sit as ΔP(win), not point gaps.** `P(A outscores B)` and ΔWP from correlated simulation with common random numbers. Tiers: ≥8pp strong / 3–8 start / 1–3 lean / <1 **coin flip**. Correctly flips toward high-floor when favored and high-ceiling when an underdog.
2. **Accuracy tracking** (§5.5).
3. **Roster-context trade analyzer** — marginal ROS starting-lineup points, rebuilding both lineups post-trade, rather than a static value chart. Full ΔP(championship) re-sim for large trades. Surfaces 2-for-1 roster-spot cost, bye collisions, playoff-week SoS.
4. **Waiver value + dynamic FAAB** — rank by marginal lineup value versus your likely drop, not raw projection. Tiers: league-winner 40–70%, clear starter 15–30%, flex upside 5–12%, streamer 1–5%, scaled by playoff odds, weeks remaining, rival positional scarcity and relative budget.
5. **Playoff/championship odds** — 10k-iteration league sim with per-manager lineup-efficiency factors (the optimal-lineup assumption overstates opponents by 3–8%). The **`P(playoffs | win)` vs `P(playoffs | lose)` delta** is the most useful single number in the app. Power rankings from simulated median score correct for schedule luck.

**Deprioritized:** DFS optimizer (unless you play DFS), weighted ensembling (equal weights measurably beat it), on-device ML runtimes.

---

## 7. Android architecture

Full document with code sketches, mermaid module diagram and a `libs.versions.toml` excerpt in [`research/research-android-architecture.md`](research/research-android-architecture.md).

### 7.1 Modules

Full Now-in-Android structure, as chosen: `:app`, six `:feature:*`, core split into UI (`:core:designsystem`, `:core:ui`, **`:core:table`**, `:core:charts`), data (`:core:data`, `:core:domain`, `:core:database`, `:core:network`, `:core:datastore`, `:core:model`, **`:core:statquery`**) and infra (`:core:common`, `:core:testing`, `:core:analytics`), plus `:sync:work` and `:benchmark`.

Two modules beyond the obvious set earn their keep regardless of team size: **`:core:table`** (the table engine is too large and too reused to live inside a feature) and **`:core:statquery`** (pure-Kotlin, Android-free query builder — 100% unit-testable before any UI exists, and the single best place to start writing code). `:core:model` and `:core:statquery` stay `kotlin("jvm")` for fast compiles and Robolectric-free tests.

`build-logic` convention plugins are essential at 18 modules — without them you maintain 18 nearly-identical build files by hand. Build this first.

> Noted tradeoff, since it's a solo build: the coordination benefit of this structure is zero here, and the boilerplate cost is real — expect a day or two of scaffolding before the first feature. The build-caching and enforced-boundary benefits remain genuine.

### 7.2 Pattern

**MVVM + strict UDF, not a formal MVI framework.** State is dominated by a single `StatQuerySpec` and everything derives from it, so a reducer is indirection over an already-pure transform. Adopt MVI's two real constraints — one immutable `UiState` per screen, UI only emits events upward — and skip the machinery. One-off events via `Channel`, never nullable `UiState` fields.

### 7.3 Libraries

| Concern | Choice | Reasoning |
|---|---|---|
| DI | **Hilt + KSP** | Compile-time graph validation across 18 modules; `HiltWorkerFactory` for WorkManager |
| Database | **Room 3.x** | Inverts the usual premise: SQLDelight's value is *compile-time-verified* SQL, which cannot cover a query that doesn't exist until you tap a header. It degrades to `executeQuery(identifier = null, …)` with hand-written mappers — same raw SQL, minus Room's result mapping, `createFromAsset()`, Paging integration, `InvalidationTracker` and exported schema diffs |
| Network | Retrofit 3 + OkHttp 5 | Disk cache and ETag interceptors directly useful for delta sync |
| Serialization | kotlinx.serialization | — |
| Paging | Paging 3, selectively | Player-week yes; 2.5k-row season aggregates no |
| Navigation | **Navigation 3** | Stable Nov 2025; `ListDetailSceneStrategy` nearly free |
| Charts | **Vico** + hand-rolled Canvas | Vico has no radar and no box plot — exactly the differentiating visuals here |
| Screenshot tests | Roborazzi | Runs on Robolectric, so it can interact *then* capture |

**Room 3 gotcha:** `SupportSQLiteQuery` / `SupportSQLiteDatabase` are **gone** from core APIs. `@RawQuery` now takes `RoomRawQuery` with an `onBindStatement` lambda; coordinates are `androidx.room3:room3-*`. Every `SimpleSQLiteQuery` tutorial online is Room 2. Add `androidx.sqlite:sqlite-bundled` for a predictable SQLite version and guaranteed FTS availability.

### 7.4 The table

**Tier 1 is almost certainly sufficient on an S24 Ultra.** `LazyColumn` + **one hoisted `ScrollState`** shared by the header and every row, with the name cell placed outside the scrolling `Row`. Per-row `LazyRow`s are the common wrong answer — N independent `LazyListState`s produce sync feedback loops, tearing and N separate flings.

The Snapdragon 8 Gen 3 removes most of the risk that made this a go/no-go gate in the distributed plan. **Two caveats specific to this device:**

- **The 120Hz panel gives an 8.3ms frame budget, not 16.7ms.** High refresh rate makes jank *more* visible, not less. A flagship CPU with a doubled frame budget requirement is not automatically a win — benchmark at 120Hz specifically.
- Hold Tier 2 in reserve: a custom `LazyLayout` with a 2D item provider, fixed row height, pinned cells at higher z-index. `oleksandrbalan/lazytable` (Apache-2.0, `pinConfiguration` for frozen columns) does this today — **vendor it** rather than depending on a ~58-commit single-maintainer project.

Performance rules that still matter: pre-format every cell string in the mapper on `Dispatchers.Default` (in-cell `String.format` is the #1 jank source) · `@Immutable` + `ImmutableList`, and **reuse row instances across re-sorts** so strong skipping fires · `key = { playerId }` + `contentType` · lambda-taking `Modifier.offset {}` / `graphicsLayer {}` for per-frame reads · `derivedStateOf` for the frozen-column shadow · gate sparklines on `!isScrollInProgress` · baseline profile from a real table-scroll journey.

**Query safety** — still worth doing, for correctness rather than defense against an attacker who is also you. Column identifiers come *only* from a sealed `StatColumns` registry; every value binds as `?`. `ORDER BY` emits `(expr) IS NULL, (expr) DESC` for NULLS-last plus a `full_name` tiebreak so paging stays stable.

**Two simplifications:** FTS is unnecessary (~3,000 active players; a normalized lowercase `search_name` column with a B-tree index and the range trick `>= 'st' AND < 'st￿'` is sub-millisecond). And precomputed aggregates are unambiguously worth it — season rollups, L3/L5/L8 splits and per-column percentiles built server-side, shipped **inside** the prebuilt DB. Hybrid layout: ~50 hot stats as real indexed columns, the remaining ~165 in a JSON1 blob.

### 7.5 Sync and storage

Ship a **prebuilt, pre-indexed, pre-`ANALYZE`d SQLite file** via `createFromAsset()`. JSON-then-insert costs 20–90 s of inserts and index builds on first launch; a `.db` copy is 1–3 s. With no Play Asset Delivery, put it in `assets/` directly or `adb push` it — both fine for a sideloaded app, and 12 GB of RAM plus ample storage means you can bundle far more history than a distributed app would dare.

Delta sync on an opaque monotonic cursor with a `full_refresh_required` escape hatch; apply-then-persist-cursor so crashes replay harmlessly. **Live scoring must not use WorkManager** (15-minute periodic floor) — foreground polling gated by `repeatOnLifecycle`, writing into a `live_stat` table so there's one source of truth. A Saturday-11pm `PrefetchWorker` is the Sunday insurance policy.

**Split into two databases from day one:**

- `stats.db` — server-derived, fully reconstructible → `fallbackToDestructiveMigration` + re-seed.
- `user.db` — presets, rosters, draft boards, notes → hand-written migrations, never destructive.

This matters *more* for a personal app, not less: there's no support channel when you destructively migrate your own draft board mid-season. Splitting later is a painful migration; splitting now is free. Related: **no table or column name contains a year**, so a new season is data, not schema.

### 7.6 Accessibility and layout

Scoped to what benefits you on one device rather than a compliance checklist.

Keep the cheap wins: **font scaling is the table's real enemy** — fixed-dp columns with `maxLines = 1` silently clip "128.4" to "128." at large scale, which is worse than a visibly broken layout. Measure with `rememberTextMeasurer` keyed on `fontScale`; offer a density toggle that changes padding, never text size. Use **colorblind-safe diverging blue↔orange rather than red/green** — it's a better heatmap ramp regardless of vision, with the number always legible on top.

Deprioritize the elaborate TalkBack table semantics work unless you use a screen reader. If you do want it later, the key insight is to **make the row the accessibility node, not the cell** — per-cell `collectionItemInfo` yields a ~5,000-node tree where reaching column 40 takes 40 swipes.

Do build the adaptive layouts: `ListDetailPaneScaffold` (players → detail) and `SupportingPaneScaffold` (compare) pay off immediately on a 6.8" panel in landscape and in DeX.

### 7.7 Platform targets

Compose BOM 2026.09.00 (Compose 1.12) · Kotlin 2.4.x · AGP 9.2.x · KSP2 · `compileSdk 37`.

**`minSdk` can be set to your device's API level.** This is a real simplification — no compat shims, no `@RequiresApi` branching, no testing older behavior. Set it to whatever the S24 Ultra runs and never think about it again. `targetSdk` is now purely a behavior-opt-in choice, not a deadline.

**16 KB page alignment still matters** — it's a device requirement on modern Android, not a Play requirement, and this app ships `sqlite-bundled` native libraries. Verify it once.

No AAB, no signing ceremony beyond a keystore you keep, no size budget. Install over USB.

---

## 8. Risks

**The table remains the hardest engineering problem**, but on an S24 Ultra it is a tuning exercise rather than a viability question. Compose still virtualizes one axis at a time — `Modifier.horizontalScroll` composes, measures and lays out every column of every visible row, including off-screen ones — so the underlying constraint is unchanged. What changed is the headroom. Benchmark Tier 1 at 120Hz with your real column count early, and keep `lazytable` bookmarked.

**Data source fragility replaces legal risk as the top concern.** ESPN's undocumented endpoints have already moved hosts once in 2026. nflverse file naming has at least two inconsistencies. A personal app has no user base to disappoint, but it does have exactly one Sunday morning per week where it needs to work. Make every non-nflverse field optional and fail soft.

**Scope is the real risk.** This spec describes something several commercial teams build collectively. Solo, the build order below sequences it so each phase is independently useful — a working Grid with no projections is already better than the alternatives.

---

## 9. Build order

Sequenced so each phase produces something usable on its own.

| Phase | Work |
|---|---|
| **1 — Foundations** | `build-logic` convention plugins, 18-module skeleton, `:core:statquery` built and unit-tested with zero UI. This is the right first code: pure Kotlin, fully testable, and the thing everything else depends on. |
| **2 — Data pipeline** | GitHub Actions ETL from nflverse → normalized `stats.db`. Testable entirely outside Android. At the end of this phase you have the dataset, which is most of the value. |
| **3 — The Grid** | Table Tier 1, benchmarked at 120Hz. Stat packs, sort, two-tier filters, week-range recomputation, presets, sparklines, CSV export. **Ship to your phone here** — already more useful than any commercial app. |
| **4 — Comparison** | Compare tray, percentile bars, xFP-vs-actual scatter, head-to-head table, radar. S Pen hover affordances. |
| **5 — Projections** | Pipeline layers 1–6, component payload, on-device scoring, explainability waterfall, accuracy tracking. |
| **6 — League + decisions** | Sleeper adapter, then ESPN (public, then private via cookies). Start/sit ΔWP, playoff odds sim, waiver/FAAB, trade analyzer. |

---

## 10. Remaining courtesy notes

Not compliance — just the things that will break or annoy someone if ignored.

- [ ] Respect Pro-Football-Reference's ~20 req/min; prefer nflverse's relicensed copies anyway
- [ ] Respect Sleeper's 1,000 req/min; cache `/players` daily, not per-launch
- [ ] Descriptive User-Agent for `api.weather.gov` (it's required)
- [ ] Don't hammer ESPN's undocumented endpoints; cache and back off
- [ ] Keep nflverse CC BY attribution in an about screen — costs nothing, and it's the right thing
- [ ] Odds API key in Actions secrets and `local.properties`, not committed
- [ ] `user.db` backed up somewhere before each season rollover

---

## 11. Known gaps

- Reddit was unreachable during research (WebFetch blocked, `.json` API 403). The user-complaint analysis leaned on review aggregators. **This matters far less now** — for a personal tool, you are the user research.
- The competitive analysis in [`research/research-competitive.md`](research/research-competitive.md) is retained for its feature-gap findings, but its positioning and monetization sections are now moot.
- Not captured due to paywalls: RotoViz, Establish The Run, Sharp Football pricing.

---

## Appendices

- [Data sources, licensing and ingestion](research/research-data-sources.md) — 516 lines, live-tested endpoints
- [Stat catalog](research/research-stats-catalog.md) — 887 lines, ~450 metrics, 55+ sources
- [Prediction methodology](research/research-prediction-models.md) — 1,093 lines, formulas, 50 sources
- [Android architecture](research/research-android-architecture.md) — 1,213 lines, code sketches, module diagram
- [Competitive analysis](research/research-competitive.md) — 320 lines; feature gaps still useful, positioning now moot
