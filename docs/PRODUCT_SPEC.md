# Gridiron — Product & Technical Specification

**A free, no-paywall NFL fantasy football analytics and prediction app for Android.**

Status: Draft v0.1 — brainstorm synthesis, pre-implementation
Last updated: 2026-09-22

---

## 1. Thesis

> **The whole stat sheet, free.**

Everything FantasyPros charges $72–$276/yr for, plus the advanced metrics PlayerProfiler charges $135/yr for, free — and actually usable on a phone.

A complete fantasy toolkit today costs roughly **$337/yr stacked across five products**, and still excludes DFS and PFF. Rankings live at FantasyPros, advanced metrics at PlayerProfiler, dynasty values at KeepTradeCut, ADP at Fantasy Football Calculator, and your actual roster at ESPN or Sleeper. Nobody joins that data. That is the opening.

### Why this is achievable at $0 licensing

Roughly **85% of the metric catalog is computable from free, openly-licensed sources** — primarily `nflverse` (CC BY 4.0), which publishes play-by-play back to 1999, snap counts, depth charts, injuries, Next Gen Stats, PFR advanced stats and FTN charting with no API key and no rate limit. The paid tier of this industry is largely selling convenience over data that is already free.

### Why now

**NFL Fantasy is dead as a season-long game.** On 2026-07-16 the NFL and ESPN jointly announced the NFL no longer operates its own fantasy game; ESPN is the official NFL fantasy game and NFL Fantasy users are being migrated via `espn.com/importnfl`. Millions of displaced users just landed in the ESPN app, which carries the worst Android rating among the major platforms (~3.8). There is an acquisition window open right now.

---

## 2. The four defended gaps

### 2.1 Mobile stat tables are broken market-wide

This is the least-defended surface in the entire sector:

- FantasyPros' Android app reportedly has interactive elements at the bottom of the screen that collide with the system nav bar and cannot be tapped.
- NFL Fantasy shipped a changelog entry fixing **sorting being broken on stat pages**.
- PlayerProfiler, RotoViz, 4for4, Fantasy Points and Footballguys are all desktop-density web crammed onto a phone viewport.

A genuinely good mobile grid — frozen name column, long-press-to-sort, 60+ column chooser, saved views, multi-condition filters, export — is both the hardest engineering problem here and the clearest differentiator. It also screenshots extremely well on a Play listing.

### 2.2 N-way comparison does not exist anywhere

Every tool on the market compares exactly **two** players, or two trade packages. Nothing lets you put four to six players side by side across twenty columns and sort them. This is a genuine product-category gap, not just an execution gap.

### 2.3 Advanced metrics are free to produce

`nflverse` play-by-play + `ffopportunity` (xFP via xgboost) + Fantasy Football Calculator ADP (explicitly free for **commercial** use, attribution requested) yields a credible advanced-stats product at zero licensing cost, competing against a $45–$499/yr tier.

### 2.4 Honest accuracy is an unclaimed position

Weekly fantasy projection R² is only **3–23%**. Season-long industry projections carry a documented **+21.6 point optimism bias**. Every competitor hides this. Publishing live accuracy tracking — weekly MAE by position vs. naive baselines, win-probability calibration curves, hit-rate by stated confidence, "our biggest misses this week" — converts the industry's dirty secret into a credibility asset. It is nearly free to build and nobody does it.

---

## 3. Locked decisions

| Decision | Choice | Consequence |
|---|---|---|
| Sport | NFL fantasy football | — |
| "Inclusive" means | Feature-packed, extensive stats, heavy sort/filter + free/no paywall | Drives the grid-first architecture |
| Platform | Kotlin + Jetpack Compose, Android | Native a11y, best table performance ceiling |
| Monetization | **Banner + rewarded ads only** — never interstitials | Triggers commercial-use review of data sources (§4.3) |
| League sync v1 | **Sleeper + manual entry + ESPN private league import** | ESPN private requires cookie handling (§7.2) |
| Betting data | **Full lines and props displayed** | Collides with ads under Play policy (§3.1) |

### 3.1 🔴 Open risk: ads + full odds display is the specifically-named violating pattern

You chose both ad monetization and full odds/prop display. Google Play's Real-Money Gambling policy bars non-RMG apps from providing gambling *companion functionality* — the policy text names "sports score/odds/performance tracking" — **while running gambling ads**, and Google's common-violations guidance calls out *a dedicated sports odds tracker app containing integrated gambling ad links* as a prohibited pattern.

The trigger is the **combination**, not either element alone. Mitigation, which must be treated as a launch blocker rather than a nice-to-have:

1. **Blocklist all gambling and betting ad categories** in AdMob mediation. This is the single most important control — it decouples the two halves of the prohibited pattern.
2. **No sportsbook affiliate links, ever.** No deep links, no promo codes, no "bet this" CTAs.
3. **Frame odds as projection inputs, not wagering information.** Display implied team totals and prop-derived projections in fantasy context. Avoid sportsbook branding and avoid presenting lines as a shopping comparison across books.
4. Expect to need an **18+ content rating**, and declare accordingly.
5. **Verify with Play policy support before launch.** This costs an email and de-risks a full rejection.

If Play pushes back, the fallback that preserves nearly all projection accuracy is to keep odds in the model and surface only derived, unbranded values ("implied team total 24.5"). The modelling value is in the de-vigged prop means, not in showing a spread.

### 3.2 🔴 Open risk: ads make the app commercial, and two key sources are non-commercial-only

- **Sleeper API** states it is "free to use for non-commercial purposes… For commercial use, please reach out to us directly to discuss licensing." An ad-supported app is plausibly commercial, and Sleeper sync is the headline onboarding feature. **Email Sleeper for a license before building on it.** One email de-risks the entire feature.
- **Open-Meteo** explicitly defines apps that display advertisements as commercial. **Resolved:** use `api.weather.gov` instead (US public domain, no key, requires only a descriptive User-Agent). No cost, no restriction.
- **FantasyCalc** is reported non-commercial + attribution-link required; read their ToS directly before depending on it.

### 3.3 🔴 Trademark posture (non-negotiable)

Facts are not copyrightable; logos, wordmarks and headshots are. `nflverse`'s `teams` release hands you `team_logo_espn` URLs — those are NFL-owned marks hotlinked from ESPN's CDN. Shipping them is infringement *and* hotlinking.

- No NFL or team logos, wordmarks, or player headshots.
- Use team abbreviations plus the `team_color` / `team_color2` fields.
- Keep "NFL" out of the app title, package ID and icon.
- Ship a non-affiliation disclaimer.
- **Never scrape KeepTradeCut** — their terms expressly forbid it and they have no API.

---

## 4. Data architecture

### 4.1 Core rule: the client never touches upstream sources

The measured payload sizes settle this decisively:

| Upstream resource | Raw size | After server-side normalization |
|---|---|---|
| `depth_charts_2026.csv` | **52.3 MB** | ~50 KB |
| Sleeper `/v1/players/nfl` | **14.7 MB** | ~200 KB |
| ESPN `/nfl/injuries` | **9.0 MB** | ~30 KB |

A mobile client cannot consume these. All ingestion, normalization and modelling happens server-side; the app downloads compact derived artifacts.

### 4.2 Zero-cost pipeline

```
GitHub Actions (public repo → unlimited standard-runner minutes)
  └─ nightly + in-game ETL, normalize with polars / duckdb
      └─ publish to GitHub Releases (Fastly-backed CDN, ETag support)
          ├─ manifest.json          (tiny; polled by WorkManager)
          ├─ week-NN.json.gz        (delta payloads)
          └─ stats.sqlite.gz        (prebuilt, pre-indexed, pre-ANALYZEd)
              └─ Android: OkHttp ETag → Room → Compose (offline-first)

Cloudflare Worker (100K req/day free)
  └─ proxies The Odds API only, so the key never ships in the APK
```

**GitHub Releases over GitHub Pages** — Pages has a 100 GB/mo soft bandwidth cap; Releases is not metered the same way.

**Explicitly rejected: Supabase as the spine.** Free projects auto-pause after 7 days without requests, which is fatal across a February–August offseason.

### 4.3 Source register

| Source | License | Auth | Role | Status |
|---|---|---|---|---|
| nflverse-data | CC BY 4.0 | none | **Foundation** — PBP, snaps, depth charts, injuries, NGS, PFR adv, QBR, combine, draft | ✅ Clean |
| api.weather.gov | US public domain | User-Agent only | Game-day weather (wind) | ✅ Clean |
| Fantasy Football Calculator | Free incl. commercial, attribution | none | ADP | ✅ Clean |
| The Odds API | Paid API, free Starter tier | key (proxied) | Lines, totals, player props | ⚠️ 500 credits/mo; ETag 304s are free |
| Sleeper | **Non-commercial** | none | League sync, trending adds/drops | 🔴 Needs license email |
| ESPN undocumented | No license | cookies for private | Inactives, private league sync | 🔴 Grey area, degrade gracefully |
| FantasyPros | Proprietary | — | — | ❌ Off the table at $0 |
| Pro Football Reference | ToU forbids competing data store | — | — | ❌ Never scrape directly |
| PFF / PlayerProfiler / 4for4 | Paywalled, no redistribution | — | — | ❌ Excluded |

**Two documented traps found by live testing:** `nextgen_stats` is *not* season-partitioned (`ngs_passing.csv.gz`, not `ngs_2026_passing.csv.gz` → 404), and `contracts/historical_contracts.csv` 404s. Also note ESPN's host moved to `lm-api-reads.fantasy.espn.com`; the legacy `fantasy.espn.com` v3 host is unreliable in 2026.

**Chain-of-title wrinkle worth recording:** `nflverse`'s `snap_counts`, `pfr_advstats`, `combine` and `draft_picks` are themselves PFR-scraped and relicensed CC BY. nflverse cannot grant rights it does not hold. The risk is small and diffuse — the whole ecosystem sits on this — but a fallback should exist.

### 4.4 Freshness tiers

| Tier | Content | Cadence |
|---|---|---|
| Live | Scores, in-game stats | Foreground polling only, never WorkManager (15-min floor) |
| Near-live | Inactives (11:30am ET Sunday), odds | ESPN proxy + odds refresh |
| Daily | Injuries, depth charts, rosters, ADP, projections | 07:00 UTC |
| Cyclic | Snap counts, PFR advanced, FTN charting | 00/06/12/18 UTC |
| Seasonal | Historical PBP, combine, draft capital | On demand |

**The unavoidable grey area is Sunday inactives.** The highest-stakes moment in fantasy has no clean free source; `nflverse` injuries refresh once daily at 07:00 UTC. Either proxy ESPN's unofficial endpoints through the backend (never shipping ESPN URLs in the APK) or accept being an hour behind. Every ESPN-derived field must be optional so the app degrades rather than crashes.

---

## 5. The metrics engine

~450 metrics catalogued in [`research/research-stats-catalog.md`](research/research-stats-catalog.md). Computability tiers: **A** = free from play-by-play, **B** = free auxiliary feed, **C** = proprietary but rebuildable in-house, **D** = licensed only. Tier C ships as clearly-labelled in-house models ("xFP — our model"), never omitted. Tier D is excluded.

### 5.1 Architectural principle: scoring is configuration, not columns

A `ScoringProfile` object — PPR variants, TE premium, first-down bonuses, yardage bonuses, negative points, IDP, kicker, D/ST tiers, roster/flex settings — must drive **live recomputation** of fantasy points, boom/bust rates, VORP, DvP and auction values.

This has a hard consequence for the payload: **ship stat components, never fantasy points.** The server sends projected receptions, yards, TDs; the device applies the user's exact league scoring locally. Half-PPR, TE-premium, superflex and IDP then all work for free, offline.

### 5.2 Highest-value metrics

**Opportunity — build first; opportunity is far stickier than efficiency**

Snap share + 3-week rolling trend (the #1 waiver signal) · route participation · target share · **targets per route run (TPRR)** (>22% is elite WR) · air yards share · **WOPR** = `1.5 × target_share + 0.7 × air_yards_share` (best single composite; elite >0.70) · aDOT · opportunity share (>70% = bellcow) · weighted opportunities = `carries + ~2.6 × targets` · **green-zone (inside 10) and goal-line (inside 5) carries** — 74% and 68% of rushing TDs originate there · end-zone target share (an end-zone target is worth ~3.0 pts vs ~1.8 from the 19)

**Efficiency**

YPRR (top-20 YPRR WRs scored ~154% more the following season than bottom-20) · RACR · YAC over expected · RYOE/att · rushing success rate (beats YPC, which is notoriously unstable) · EPA/dropback · CPOE · QB designed rushes and rushes inside the 5 (the single biggest QB fantasy differentiator) · pressure rate and time to throw (free via FTN + NGS)

**Fantasy-specific**

**xFP/game** (opportunity-only baseline — a *better* forward predictor than actual FPPG) · **FPOE** = actual − expected (the buy-low/sell-high engine) · FP per opportunity · boom / bust / start-worthy rate · floor (p10) and ceiling (p90) · spike weeks · VORP with settings-aware replacement level

**Context**

Team PROE (actual − `xpass`) · situation-neutral pace · **implied team total** = `(total − team_spread) / 2` · schedule-adjusted DvP and ROS/playoff-week SoS (raw fantasy-points-allowed is badly schedule-confounded) · adjusted line yards (rebuildable free; explains ~29% of RB half-PPR production) · **practice participation trend** DNP→LP→FP, with Friday the actionable day · **wind** (the only weather variable that reliably matters — effects begin ~15 mph, ~15–20% passing drop at 20+)

**IDP** — defensive snap share, box/safety rate. Nearly all IDP metrics are tier A and the market is badly underserved. Cheap differentiation.

### 5.3 Storage model

Long/narrow weekly fact table `(player_id, season, week, metric_id, value)` plus a **metric-metadata table** carrying definition, formula, what-it-predicts, stability and distribution.

Adding a metric becomes a data change, not a migration — and the metadata table powers info sheets, column search, onboarding and empty states for free. Materialize wide views per stat-pack for render speed.

---

## 6. Prediction engine

Full methodology with formulas in [`research/research-prediction-models.md`](research/research-prediction-models.md).

**Design stance: opportunity-first, market-anchored, regression-heavy, transparent.**

### 6.1 Six layers

1. **Volume cascade** — never project points directly. Team plays → pass/rush split → snap share → routes/carries → target share → targets → receptions → yards. Opportunity is the only genuinely stable input.
2. **Shrunk efficiency** — James-Stein blend `w = n/(n+k)` toward positional baseline, `k` fit server-side per (position, stat). **TD rate gets the heaviest shrinkage**: YoY correlation ~0.28 vs ~0.70–0.75 for target share. Replace observed TDs with opportunity-derived xTD. This is the biggest free edge available — historically **66.3% of low-TD WR/TE with stable volume scored more the following season**.
3. **Time weighting** — EWMA with half-life by signal type: aggressive on role (H≈2–3 games), mild on efficiency (H≈8–12), **never on TD rate**. Regime-break flags (new team, new OC, new QB) zero the prior.
4. **Matchup** — discard raw DvP; it measures schedule, not defense. Use ridge-regression two-way opponent adjustment applied to *rate* inputs. Cap multipliers: ±15% efficiency, ±5% volume, ±20% TD.
5. **Game script** — implied totals drive TD share and play volume; spread drives pass rate (~0.4–0.8pp per point). Weather gated behind `is_outdoor`, quadratic wind penalty above ~12 mph.
6. **Market anchor + ensemble** — de-vig props, invert to means (**anytime-TD → `λ = −ln(1−p)` is the cleanest single win in the pipeline**), inverse-variance blend with the model. Ensemble 3–6 signals with **equal weights**: published 11/12-season studies show consensus beats individual sources in 63–69% of head-to-heads, and equal weighting beats tuned weighting.

### 6.2 Distributions, not point estimates

Every projection is a distribution — simulate components as NegBinom targets → Binomial receptions → Gamma yards → Poisson TDs, zero-inflated for a realistic floor. Every multi-player question uses a **Gaussian copula** with a structural correlation matrix (QB-WR1 ≈ 0.55, QB-TE ≈ 0.40, same-team WR-WR ≈ −0.02, RB-WR negative, bring-back positive).

### 6.3 Compute split

**Server (batch, scales with ~600 players, not with users):** all ETL, ridge opponent adjustment, empirical-Bayes prior fitting, xFP/xTD model *fitting*, prop ingestion and de-vigging, ensembling, correlation matrix, residual/bootstrap tables, ROS aggregates, replacement levels, auction values, tiers.

**Payload:** ~600 players × (mean, σ, shape, zero-inflation π, ~8 factor contributions, ids) ≈ **80–250 KB packed, ~400 KB gzipped**. Cached in Room → fully offline.

**On-device Kotlin:** league-scoring application, and all Monte Carlo over the user's league — H2H win probability (10k iters ≈ 20–60 ms), start/sit ΔWP with common random numbers (≈0.2–0.5 s), season simulation (2k iters foreground / 10k via WorkManager), optimal-lineup solving, trade and waiver marginal value, live VONA/auction inflation, explainability rendering.

Implementation: plain `DoubleArray` + `SplittableRandom`, preallocated buffers, `Dispatchers.Default` parallel chunks. Hand-roll the ~20-line Cholesky; `multik-kotlin` if a library is wanted.

**ONNX Runtime Mobile / LiteRT: rejected.** Adds 3–8 MB to an app whose core payload is 200 KB, the model is not the latency bottleneck, and it destroys the explainability differentiator — SHAP on a GBM is approximate and sums to nothing a user recognizes. **The better pattern is to use ML server-side to fit the coefficients of a transparent model, then ship the coefficients.**

### 6.4 Explainability

Compute multiplicatively, attribute additively via log-space decomposition so the parts sum *exactly* to the whole:

```
Δᵢ = (FP_final − FP_base) × log(mᵢ) / Σ log(mⱼ)
```

Precompute the Δs server-side; the client renders a waterfall: baseline → matchup → game script → weather → injury. Also surface **TD-dependence %** = `6λ_TD / FP` — it explains weekly volatility better than any other single number, and nobody displays it.

### 6.5 Accuracy targets

Published PPR weekly MAE to aim at: **QB 6.2 · RB 5.1 · WR 4.9 · TE 3.8**. Track and publish against naive baselines and consensus. De-bias the documented +21.6-point season-long optimism for free accuracy.

---

## 7. Feature specification

### 7.1 The Grid (flagship)

Frozen player-name column; horizontally scrollable stat columns; long-press header to sort; **stat packs** (Basic / Volume / Opportunity / Efficiency / Next Gen / Red Zone / Consistency / Matchup / Dynasty / DFS / IDP) as primary navigation — never render 200 columns at once.

- Two-tier filtering: persistent quick bar (position, week range, team, min-snaps) applying instantly; advanced sheet with per-column operators, explicit Apply, live match count.
- **Week-range filtering is the most-requested and most under-shipped control in the sector** — and rate metrics must *recompute* over the range, not merely sum.
- Minimum-opportunity thresholds mandatory on every efficiency leaderboard, with sample size visible in-row.
- Global toggles: per-game / total / per-opportunity, and raw / rank / percentile / z-score.
- Sparklines in every row (last 6 weeks of the sorted column) — the biggest density-per-pixel win available.
- ~10–15 **pre-built saved presets** shipped on day one ("Buy-low WRs", "Route share risers", "Green-zone hogs", "Soft playoff schedules") — the bridge that makes 200 columns usable immediately.
- CSV export, free.

### 7.2 Comparison (the category gap)

- **Persistent bottom compare tray, 0–4 players**, fed by long-press on any Grid row — the best multi-select pattern on mobile.
- **Sorted horizontal percentile bar strips as the primary view.** Research consensus (StatsBomb, Opta, PyMC Labs) is that bars beat radars: linear encoding, non-adjacent values directly comparable, no axis-order artifacts. Grouped Opportunity / Efficiency / Scoring / Context with section composites.
- **Percentile ranks on every metric** — this is what makes 60+ unfamiliar stats legible without teaching any of them.
- Radar retained as a secondary "shape" view: max 6–8 axes, percentile scales only, fixed semantic axis order, max 2 players, one-tap toggle to bars.
- **Opportunity-vs-efficiency scatter** — the highest-insight chart available. Killer variant: **xFP/game vs actual FP/game with a y=x line** — above the line = sell-high, below = buy-low. Explains regression with zero words.
- Head-to-head table, metrics as rows, players as columns, diff column, "only show metrics differing by >1 decile" toggle. Supports comparing a player to *himself* across seasons or week ranges.

### 7.3 League sync

Behind a `LeagueProvider` adapter so any platform can be dropped without touching UI.

- **Sleeper** — no token, read-only, documented. Cache `/players` once daily (14.7 MB). *Gated on the licensing email in §3.2.*
- **Manual roster entry as a first-class 60-second path**, not a fallback. Works for every platform, zero ToS exposure, enables full value before signup.
- **ESPN private leagues** — requires the user to hand-extract `SWID` and `espn_s2` cookies from desktop DevTools; this cannot be done programmatically. Design constraints, non-negotiable:
  - Cookies are stored **encrypted on-device only** and used for **direct device → ESPN requests**. They must **never** transit or rest on our backend. (Note this is the opposite of the inactives proxy in §4.4, and deliberately so — proxying credentials would make us a credential custodian.)
  - Declare credential handling honestly in the Data Safety form.
  - Expect significant drop-off at the DevTools step; instrument it, and always offer manual entry alongside.
  - Public ESPN leagues work unauthenticated — support those with no friction at all.
- **Yahoo** — v2. Apply now: self-service is gone, every submission is human-reviewed, and legacy apps reportedly began receiving 403s around 2026-07-22, so grandfathering cannot be assumed.

### 7.4 Decision tools

1. **Start/sit as ΔP(win), not point gaps.** Report `P(A outscores B)` and ΔWP from correlated simulation with common random numbers. Confidence tiers: ≥8pp strong / 3–8 start / 1–3 lean / <1 **coin flip**. Correctly flips toward high-floor when favored and high-ceiling when an underdog. Shipping "coin flip" honestly is itself a differentiator.
2. **Live accuracy tracking** (§2.4).
3. **Roster-context trade analyzer** — marginal ROS starting-lineup points, rebuilding both lineups post-trade, rather than a static value chart. Full ΔP(championship) re-sim for large trades. Surfaces 2-for-1 roster-spot cost, bye collisions, playoff-week SoS.
4. **Waiver value + dynamic FAAB** — rank by marginal lineup value versus your likely drop, not raw projection. Tiers: league-winner 40–70%, clear starter 15–30%, flex upside 5–12%, streamer 1–5%, scaled by playoff odds, weeks remaining, rival positional scarcity and relative budget. Show a range; tell contenders that unspent November FAAB is wasted capital.
5. **Playoff/championship odds** — 10k-iteration league sim with per-manager lineup-efficiency factors (the optimal-lineup assumption overstates opponents by 3–8%). The **`P(playoffs | win)` vs `P(playoffs | lose)` delta is the most engaging number in the app** and is free once the sim exists. Power rankings from simulated median score correct for schedule luck.

**Deprioritized:** DFS optimizer (small audience, well-served, highest compute cost), weighted ensembling (equal weights measurably beat it), on-device ML runtimes.

---

## 8. Android architecture

Full document with code sketches, mermaid module diagram and a `libs.versions.toml` excerpt in [`research/research-android-architecture.md`](research/research-android-architecture.md).

### 8.1 Modules

Now-in-Android-style: `:app`, six `:feature:*`, core split into UI (`:core:designsystem`, `:core:ui`, **`:core:table`**, `:core:charts`), data (`:core:data`, `:core:domain`, `:core:database`, `:core:network`, `:core:datastore`, `:core:model`, **`:core:statquery`**) and infra (`:core:common`, `:core:testing`, `:core:analytics`), plus `:sync:work` and `:benchmark`.

Two modules beyond the obvious set earn their keep: **`:core:table`** (the table engine is too large and too reused to live inside a feature) and **`:core:statquery`** (pure-Kotlin, Android-free query builder — 100% unit-testable before any UI exists). `:core:model` and `:core:statquery` stay `kotlin("jvm")` for fast compiles and Robolectric-free tests. `build-logic` convention plugins are the highest-leverage build investment at 18 modules.

### 8.2 Pattern

**MVVM + strict UDF, not a formal MVI framework.** State is dominated by a single `StatQuerySpec` object and everything derives from it, so a reducer is indirection over an already-pure transform. Adopt MVI's two real constraints — one immutable `UiState` per screen, UI only emits events upward — and skip the machinery. One-off events via `Channel`, never nullable `UiState` fields.

### 8.3 Library decisions

| Concern | Choice | Reasoning |
|---|---|---|
| DI | **Hilt + KSP** | Compile-time graph validation across 18 modules; `HiltWorkerFactory` matters for a WorkManager-heavy design |
| Database | **Room 3.x** | Inverts the usual premise — SQLDelight's value is *compile-time-verified* SQL, which cannot cover a query that doesn't exist until the user taps a header. It degrades to `executeQuery(identifier = null, …)` with hand-written mappers: same raw SQL, minus Room's result mapping, `createFromAsset()`, Paging integration, `InvalidationTracker` and exported schema diffs |
| Network | Retrofit 3 + OkHttp 5 | Disk cache and ETag interceptors directly useful for delta sync |
| Serialization | kotlinx.serialization | — |
| Paging | Paging 3, **selectively** | Player-week yes; 2.5k-row season aggregates no |
| Navigation | **Navigation 3** (stable Nov 2025) | Gives `ListDetailSceneStrategy` nearly free |
| Charts | **Vico** + hand-rolled Canvas | Vico has no radar and no box plot — exactly this app's differentiating visuals |
| Screenshot tests | Roborazzi over Paparazzi | Runs on Robolectric, so it can interact *then* capture |

**Room 3 gotcha, flagged loudly:** `SupportSQLiteQuery` / `SupportSQLiteDatabase` are **gone** from core APIs. `@RawQuery` now takes `RoomRawQuery` with an `onBindStatement` lambda, and coordinates are `androidx.room3:room3-*`. Every `SimpleSQLiteQuery` tutorial online is Room 2. Also add `androidx.sqlite:sqlite-bundled` so SQLite version and FTS availability don't vary by OEM.

### 8.4 The table

**Tier 1:** `LazyColumn` + **one hoisted `ScrollState`** shared by the header and every row, with the name cell placed outside the scrolling `Row`. Per-row `LazyRow`s are the common wrong answer — N independent `LazyListState`s produce sync feedback loops, tearing and N separate flings.

**Tier 2 (if the gate fails):** custom `LazyLayout` with a 2D item provider, fixed row height (turning visible-range calculation into division rather than a prefix-sum scan), pinned cells at higher z-index. `oleksandrbalan/lazytable` (Apache-2.0, `pinConfiguration` for frozen columns) does exactly this — **vendor it** rather than depending on a ~58-commit single-maintainer project for the core feature.

Performance rules that matter most: pre-format every cell string in the mapper on `Dispatchers.Default` (in-cell `String.format` is the #1 jank source) · `@Immutable` + `ImmutableList`, and **reuse row instances across re-sorts** so strong skipping actually fires · `key = { playerId }` + `contentType` · lambda-taking `Modifier.offset {}` / `graphicsLayer {}` for per-frame reads · `derivedStateOf` for the frozen-column shadow · gate sparklines on `!isScrollInProgress` · baseline profile generated from a real table-scroll journey.

**Query safety:** column identifiers come *only* from a sealed `StatColumns` registry; every value binds as `?`. `ORDER BY` emits `(expr) IS NULL, (expr) DESC` for NULLS-last plus a `full_name` tiebreak so paging stays stable. Fuzz-test that generated SQL never contains a character originating from user input.

**Two simplifications against the original assumptions:** FTS is probably unnecessary (~3,000 active players; a normalized lowercase `search_name` column with a B-tree index and the range trick `>= 'st' AND < 'st￿'` is sub-millisecond and costs zero schema — adopt FTS only for news/injury free-text later). And precomputed aggregates are unambiguously worth it: season rollups, L3/L5/L8 splits and per-column percentiles all built server-side and shipped **inside** the prebuilt DB. Hybrid column layout: ~50 hot stats as real indexed columns, the remaining ~165 in a JSON1 blob, with sort-usage analytics driving promotion.

### 8.5 Sync and storage

Ship a **prebuilt, pre-indexed, pre-`ANALYZE`d SQLite file** via `createFromAsset()` in a Play Asset Delivery install-time pack. JSON-then-insert costs 20–90 s of inserts and index builds on first launch — precisely when users are lost; a `.db` copy is 1–3 s. Bundle current + last season; download history on demand.

Delta sync on an opaque monotonic cursor with a `full_refresh_required` escape hatch, apply-then-persist-cursor so crashes replay harmlessly. **Live scoring must not use WorkManager** (15-minute periodic floor) — use foreground polling gated by `repeatOnLifecycle`, writing into a `live_stat` table so there is still one source of truth. A Saturday-11pm `PrefetchWorker` is the Sunday insurance policy.

**Strongest structural recommendation: split into two databases from day one.**

- `stats.db` — server-derived, fully reconstructible → `fallbackToDestructiveMigration` + re-seed.
- `user.db` — presets, rosters, draft boards, notes → hand-written migrations, never destructive.

A single database forces careful migrations for re-downloadable data and, worse, makes it tempting to destructively migrate a user's draft board. Splitting later is a painful data migration; splitting on day one is free. Related: **no table or column name contains a year**, so a new season is data, not schema.

### 8.6 Accessibility

The key insight for a 200-column table: **make the row the accessibility node, not the cell.** Per-cell `collectionItemInfo` yields a ~5,000-node tree where reaching column 40 takes 40 swipes. Use `semantics(mergeDescendants = true)` with a precomputed `contentDescription` carrying long names and units ("9.1 targets", not "9.1") for visible columns only, `clearAndSetSemantics {}` on the cell container, and a custom action ("Read all stats") routing to a linear detail sheet that is the accessible path to the full dataset.

Every chart gets a programmatically generated summary (min/max/mean/trend) plus a "View as table" toggle — free, since the table engine already exists.

**Font scaling is the table's real enemy:** fixed-dp columns + `maxLines = 1` silently clip "128.4" to "128." at 200% scale, which is worse than a visibly broken layout. Measure widths with `rememberTextMeasurer` keyed on `fontScale`; offer a density toggle that changes padding, never text size.

**Colorblind-safe diverging blue↔orange, never red/green** — male-skewed audience, ~8% red-green deficiency — with the number always legible on top.

Adaptive: `ListDetailPaneScaffold` (players → detail), `SupportingPaneScaffold` (compare). On foldables, keep the frozen name column off the hinge.

### 8.7 Platform targets

Compose BOM 2026.09.00 (Compose 1.12) · Kotlin 2.4.x · AGP 9.2.x · KSP2 · `compileSdk 37` · **`targetSdk 36`** (Play requirement since 2026-08-31) · `minSdk 26`.

Play: AAB only; Data Safety form required even with no account; and an easy-to-miss item specific to this app — **16 KB page alignment**, free for pure Kotlin but this app ships `sqlite-bundled` native libraries, so it must actually be verified. Size budget: base AAB < 25 MB, per-device install < 45 MB including the asset pack.

---

## 9. Biggest technical risk

**The stat table is the product, and Compose has no first-party primitive that does what it needs.**

Compose virtualizes one axis at a time. `Modifier.horizontalScroll` composes, measures and lays out *every* column of every visible row, including off-screen ones. At 200+ columns that is thousands of composables per frame. No configuration flag fixes this; it is inherent to the layout model.

Every mitigation carries its own cost. Capping visible columns at ~25 makes Tier 1 viable but imposes a product constraint born of a rendering limitation. A custom `LazyLayout` solves it properly but is bespoke infrastructure — 2D fling physics, pinned z-ordering, scroll restoration, a11y integration, nested-scroll interop — that looks like two weeks and is two months. `lazytable` does it today but is one person's spare time.

**Response: treat it as an explicit go/no-go gate in week 3.** Spike Tier 1 against 2,500 synthetic rows × 25 columns on a genuinely midrange device (Pixel 6a class — not a flagship, not an emulator), with a macrobenchmark measuring P95 frame time during fling. If it passes, ship Tier 1 with the column cap and budget a full engineer-month for the `LazyLayout`, vendoring `lazytable` as the starting point.

Do not discover this in month four with five features stacked on a table that cannot scale.

---

## 10. Build order

Sequenced so the riskiest thing is proven before anything is built on it.

| Phase | Work |
|---|---|
| **0 — De-risk (week 1)** | Email Sleeper re: commercial licensing. Email Play policy support re: odds + ads. Read FantasyCalc ToS directly. Apply for Yahoo API access (long lead time). |
| **1 — Foundations (weeks 1–2)** | `build-logic` convention plugins, module skeleton, `:core:statquery` built and unit-tested with zero UI. GitHub Actions ETL producing a seed `stats.db`. |
| **2 — The gate (week 3)** | Table Tier 1 spike + macrobenchmark on midrange hardware. Go/no-go. |
| **3 — The Grid (weeks 4–7)** | Stat packs, sort, two-tier filters, week-range recomputation, saved presets, sparklines, CSV export. |
| **4 — Comparison (weeks 8–10)** | Compare tray, percentile bars, xFP-vs-actual scatter, head-to-head table, radar as secondary. |
| **5 — Projections (weeks 11–14)** | Server pipeline layers 1–6, component payload, on-device scoring application, explainability waterfall. |
| **6 — League + decisions (weeks 15–18)** | Manual entry first, then Sleeper (license permitting), then ESPN public/private. Start/sit ΔWP, playoff odds sim, waiver/FAAB, trade analyzer. |
| **7 — Launch prep** | Accuracy tracking page, a11y pass, baseline profiles, Data Safety form, 16 KB alignment verification, ad category blocklist. |

---

## 11. Compliance checklist

- [ ] Sleeper commercial-use license obtained (or Sleeper sync cut)
- [ ] Play policy guidance on odds display + ads, in writing
- [ ] AdMob gambling/betting ad categories blocklisted
- [ ] No sportsbook affiliate links, promo codes or wagering CTAs
- [ ] 18+ content rating applied
- [ ] Banner + opt-in rewarded ads only; no interstitials, none non-closeable after 15s, none before splash
- [ ] No NFL/team logos, wordmarks or player headshots; team colors + abbreviations only
- [ ] "NFL" absent from app title, package ID and icon
- [ ] Non-affiliation disclaimer shipped
- [ ] CC BY 4.0 attribution block for nflverse shipped in-app
- [ ] FFC attribution shipped
- [ ] Data Safety form matches actual SDK behavior (AdMob collects Device/other IDs — Android ID was reclassified as a device identifier in the April 2025 update)
- [ ] ESPN cookies encrypted on-device, never transmitted to our backend
- [ ] No UGC in v1 (moderation burden, no thesis fit, keeps rating lower)
- [ ] 16 KB page alignment verified with `sqlite-bundled` native libs
- [ ] Never scrape KeepTradeCut or Pro Football Reference directly

---

## 12. Known gaps in this research

- **Reddit was unreachable** during research (WebFetch blocked on reddit.com; the `.json` API returned 403). The user-complaint gap analysis is built from Play/App Store review aggregators, vendor changelogs and review blogs — solid but secondary. **A manual pass over r/fantasyfootball, r/DynastyFF and r/fantasyfootballadvice should happen before the roadmap locks**; §2.1 is the section most likely to shift.
- Play Store star ratings came from aggregator sites; re-check the listings directly.
- Not captured due to 403s/paywalls: RotoViz, Establish The Run, Sharp Football, DFS Army pricing.
- Verify current Play Payments policy before shipping any tip jar — in-app digital purchases generally require Play Billing.

---

## Appendices

- [Data sources, licensing and ingestion](research/research-data-sources.md) — 516 lines, live-tested endpoints
- [Stat catalog](research/research-stats-catalog.md) — 887 lines, ~450 metrics, 55+ sources
- [Prediction methodology](research/research-prediction-models.md) — 1,093 lines, formulas, 50 sources
- [Android architecture](research/research-android-architecture.md) — 1,213 lines, code sketches, module diagram
- [Competitive analysis](research/research-competitive.md) — 320 lines, 25-competitor table
