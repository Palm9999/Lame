# Session Handoff: Projection Model

**How the user wants to work:**
- One fresh session per batch of **4 tasks**, with `/clear` after each.
- Every session starts by reading this file, runs the next 4 tasks from **Next step**, then updates this file (tick each task, record rulings), commits, pushes, and stops.
- Keep replies short. Ask a question only when blocked, one line at a time.

**Branch:** `claude/dreamy-euler-phbdq1`, restarted from `claude/relaxed-hypatia-73hhub` after PR #4 merged (merge 3938c84). Sub-project 1 (Tasks 1–12, the review fixes and the layer-2 fix) is merged: https://github.com/Palm9999/Lame/pull/4. Each new sub-project goes in a new PR from this branch.

## Where things stand

- [x] **Design approved** (2026-09-26): `docs/superpowers/specs/2026-09-26-projection-model-design.md`.
  - A pure-Kotlin model runs inside Refresh and projects each week only from the games before it.
  - Four sub-projects, in order:
    1. Engine
    2. Accuracy page
    3. Odds API props (the user will enter their own key)
    4. K/DST
- [x] **Sub-project 1 plan written:** `docs/superpowers/plans/2026-09-26-projection-engine.md`.
  - 12 tasks, all with full code and tests.
  - Session A = Tasks 1–4, Session B = Tasks 5–8, Session C = Tasks 9–12.
  - Rulings made while planning are at the end of the plan ("Plan self-review").
- [x] **Plan approved; execution method: native** (2026-09-26). The implementer does each task itself with the `executing-plans` skill. One fresh reviewer on the most capable model checks the whole branch after Task 12.
- [x] **Sub-project 1 built** (Sessions A–C, Tasks 1–12) and reviewed. The final whole-branch review found 1 Critical, 3 Important and about 11 Minor issues.
  - Two of the Important issues are fixed (commit 44d16f0).
  - The Critical issue and the third Important one both come from layer 2's design, so they need a spec amendment rather than a code fix.
- [x] **Layer-2 fix designed** (user's choice: next session). It's the spec's "Amendment: layer 2", planned in `docs/superpowers/plans/2026-09-26-projection-share-fix.md` (2 tasks, full code).
- [x] **Layer-2 fix built** (commits 3097c75, 63fcdc1; see "Layer-2 fix" under Execution progress). Real data now has one passer per team and team totals at real volume.
- [x] **PR #4 merged** (2026-09-26, merge 3938c84, CI green). The user merged after the fix's self-review, without a fresh review.
- [x] **Sub-project 2 plan written** (2026-09-27): `docs/superpowers/plans/2026-09-27-projection-accuracy.md`.
  - 4 tasks, one session (Session A = Tasks 1–4), full code, tests first. Rulings are at the end of the plan ("Plan self-review").
  - Re-measured before planning, on a fresh 2024–2025 Kotlin build: the model now beats the season-to-date average at every position for 2025 under PPR (MAE model / season avg / last 4: QB 6.42 / 7.14 / 7.07, RB 5.88 / 6.01 / 6.18, WR 5.40 / 5.79 / 5.81, TE 4.87 / 5.19 / 5.42). So the CI gate passes without tuning. RB's margin is thin (0.13).
  - 2024, which has no 2023 history in that build, still loses at WR and TE. The plan uses that to show the gate can fail.
- [x] **Plan approved; execution method: native** (2026-09-27).
- [x] **Sub-project 2 built** (Session A, Tasks 1–4; see "Projection accuracy: execution progress").
- [x] **Final whole-branch review** (Opus, 2026-09-27): "ready with fixes". 0 Critical, 1 Important, 8 Minor. Fixed in commit 4fdf023 (see below).
- [x] **PR #5 merged** (2026-09-27).
- [x] **Range calibration fixed and merged** (PR #6, 594867a). See "Range calibration" below.
- [x] **Sub-project 3 plan written** (2026-09-27): `docs/superpowers/plans/2026-09-27-projection-props.md`, on draft PR #7.
- [x] **Plan approved; execution method: native** (2026-09-27). The user kept both judgment calls: `MARKET_VARIANCE_RATIO` 0.5 and `ONE_SIDED_OVERROUND` 1.08.
- [x] **Session A built** (Tasks 1–4; see "Projection props: execution progress").
- [x] **Session B built** (Tasks 5–6; see "Projection props: execution progress").
- [x] **Final whole-branch review** (Opus, 2026-09-27): "with fixes". 0 Critical, 2 Important (plus 1 Minor re-graded to Important), 7 Minor. Fixed in commit 2ff2be2 (see below).
- [x] **PR #7 merged** (753e1f1); PR #9 merged (e188dc2).
- [x] **Sub-project 4 plan written** (2026-09-27): `docs/superpowers/plans/2026-09-27-projection-kdst.md`.
- [x] **Plan revised for the user's rulings** (2026-09-27). The user overturned four of the first draft's rulings: editable points-allowed tiers (ESPN's by default), a one-time prefs migration instead of `fallback`, K and D/ST chips on the Grid, and a CI gate that covers K and D/ST. The plan grew from 9 to 11 tasks.
- [ ] **Plan approved; execution method** (the user decides).

## Next step: sub-project 4 (K/DST) Session A, Tasks 1–4

1. **PR #7 (sub-project 3, props) is merged** (753e1f1). **PR #9 (deferred minors, plus rosters) is merged** (e188dc2).
2. **Sub-project 4 plan, revised** (2026-09-27): `docs/superpowers/plans/2026-09-27-projection-kdst.md`, on draft PR #10. It has 11 tasks, with full code and tests written first.
   - **Session A** = Tasks 1–4: kicking and D/ST facts, Python parity, and scoring with each profile's own points-allowed tiers.
   - **Session B** = Tasks 5–8: prefs migration and the tier editor, the Grid's K and D/ST chips, the kicker and D/ST models.
   - **Session C** = Tasks 9–11: the walk-forward, the phone, the gate at six positions (tuning K/D/ST constants if needed) and range widening, and docs. Then the final whole-branch review.
3. **The user's rulings (2026-09-27), now in the plan:**
   - **Tiers:** points allowed are stored as a number, and each profile's editable tiers score them, ESPN's by default (0, 1–6, 7–13, 14–17, 18–21, 22–27, 28–34, 35–45, 46+: 5, 4, 3, 1, 0, −1, −4, −5, −5). Projections store a mean and spread, and the phone scores the tiers in expectation (per game for rest of season).
   - **Old profiles:** migrated once (prefs `formatVersion` 2) to the K/D/ST defaults and ESPN's tiers. There's no fallback.
   - **Gate:** covers K and D/ST. If either loses, Task 11 tunes its constants (up to 8 rebuilds, then it asks).
   - **Grid:** K and D/ST chips with their own packs (Kicking, Defense). Every other chip leaves them out.
   - The other rulings are listed at the end of the plan. Worth a look: the kicking defaults (3/4/5, −1, 1, −1) are the common values, not checked against ESPN's, and ESPN's yards-allowed and blocked-kick D/ST scoring isn't modeled.
4. **Plan approved; execution method: native** (the user, 2026-09-27). The next session runs Session A (Tasks 1–4) itself with the `executing-plans` skill, then ticks each task and records its rulings here. One fresh reviewer on the most capable model checks the whole branch after Task 11.
5. **The Odds API fixtures are hand-built** from the v4 docs, because there's no key here. The first refresh with the user's key checks the real shape: enter it in ☰ → Settings → Betting props, then Refresh stats. The toast should say "Props moved N projections"; Settings shows credits left.
6. **The user checks the build on their phone** (non-blocking): ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows. The floor-to-ceiling "held" figures should read about 78–83%.
7. `etl/build/accuracy.db` (2024–2025) and `etl/build/stats.db` (2024–2026) exist in this container. Rebuild them if the container is fresh (the commands are in the plans).

## Deferred minors fixed (2026-09-27)

Four batches, each test-first (every new test was seen failing against the old code). After all four: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` → BUILD SUCCESSFUL; `:app:assembleRelease lint` → BUILD SUCCESSFUL; the 2025 accuracy gate passes with an unchanged table.

- [x] **Screens** (commit f9d9cad):
  - The Player page, Team defense, the official and live Injury reports, the Projections list, the waterfall and the accuracy page reload when a refresh swaps in new stats (`dataVersion`, the executor's version flow).
  - The Player page's scoring runs on `Dispatchers.Default` (`loadProjectionCard(…, compute)`).
  - `ListScreen` no longer turns a superseded load's cancellation into an error.
  - Ruling: `NavigationTest`'s Player page check now waits for the off-main-thread scoring, like its accuracy check already did.
- [x] **Props polish** (commit 0992054):
  - The unmatched count is names no single player matched, including every name in a game that isn't this week's. A matched player with no usable quote is neither blended nor unmatched.
  - A receptions market that passes the targets raises the targets to match, so no catch rate tops 100%.
  - `UserPrefs.toString()` shows the key as "…".
  - Without a key, Settings asks for one instead of showing the last key's credits and error.
  - A failed stats build's toast still says why props weren't updated.
  - Ruling: `FORECAST_VERSION` stays 3. Only the upcoming week changes, and it's recomputed on every refresh.
- [x] **Refresh robustness** (commit d4c09e0):
  - A season the previous database can't supply (readable meta, damaged tables) is rebuilt with a warning instead of failing every refresh. The failed copy closes its transaction and deletes its rows first.
  - A `DETACH` that fails after a failed copy is added as suppressed and no longer hides the cause.
  - A killed build's `stats.db.new` and `ingest-work` files are deleted when the app starts.
  - The News and Injury report "as of" use their own feed's time.
  - A changed injury comment that ESPN dates the same as the last one replaces it.
  - Ruling: `ATTACH` is not opened read-only. That needs URI filenames in the bundled driver, and the copy only reads from it. Cost if wrong: none today.
- [x] **Wording and cleanup** (commit e385888):
  - The accuracy page says "No player-weeks to measure in 2024." (no "yet") and names positions with nothing to count. It warns that the oldest built season starts without last season's history.
  - An error with no message reads "…: IllegalStateException." instead of "…: null."
  - Column errors name ffopportunity for `ep_*` files.
  - A past season with no injury report says nflverse has none, instead of "yet".
  - Stale comments in `GridScreen.kt` and `AndroidManifest.xml` are fixed.
  - The unused `Rates.passShare` is removed.
  - CLAUDE.md is updated: the `:app` Settings line, the reload list, the accuracy timing, and data attribution (ESPN, The Odds API, ffopportunity).
- **Still deferred** (judgment calls, not bugs):
  - The accuracy page's Unavailable state has no season chips.
  - "Appeared" means a recorded play.
  - `AccuracyContractTest`'s 15 s budget.
  - Small waste in the backtest.
  - The 250 ms statquery timing margin.
  - Two same-name players count as one unmatched name.

**Things to know:**
- **The Python projection code is gone** (plan Task 12 Step 4, done with the user's go-ahead after the session). Three modules, 14 projection-only tests, the `build.py` stage, the `schema.py` loaders and `numpy` were removed. ETL pytest now passes 69/69 (the 50 removed tests were projection-only), and 2025 parity is OK on all 5 tables.
- **The accuracy gap was measured before the fix.** The reviewer's quick 2025 backtest had the model's error larger than a season-to-date average's at every position (QB 6.99 vs 6.62, RB 5.52 vs 5.23, WR 5.13 vs 4.99, TE 4.86 vs 4.85). It was re-measured after the fix while planning sub-project 2, and the model now leads at every position (see above); sub-project 2's CI gate holds it there.
- **Deferred Minor findings** (the user decides):
  - Player page scoring runs on the main thread.
  - The list's scoring time was never measured.
  - Screens don't reload after a refresh swap until reopened.
  - `Rates.passShare` (`League.kt`) no longer has a caller.
- **A timing test flakes under full parallel builds.** `:core:statquery`'s "scoring a full season … is fast" test fails under a full parallel `./gradlew build`. It's a known CPU-contention flake: run that module alone to confirm green.
- **Warnings are errors**, and explicit API mode is on in JVM modules.
- **Tracking.** The `.superpowers/` ledger doesn't survive the container. Rulings go in this file.

**PRs #4–#9:** merged; their watches and check-ins are cancelled. The branch is reset onto the merged base (e188dc2).

## Projection props: execution progress

**Session A** (2026-09-27). After Task 4: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` → BUILD SUCCESSFUL.

- [x] Task 1: The prop math (commit 67af351; `./gradlew :core:forecast:test` → 62/62, `PropMathTest` 10/10). Watched failing first (unresolved `normalizeName`, …). No rulings.
- [x] Task 2: The forecast blends props into the upcoming week (commit f6db6d3; `./gradlew :core:forecast:test :core:ingest:test` → forecast 72/72, `MarketTest` 7/7 and 3 new `ForecastEngineTest` tests). Watched failing first (unresolved `PropCandidate`, `Forecast.run` had no `props`). `FORECAST_VERSION` is 3.
  - Ruling: the plan changed only `run()`'s final `ProjectionOutcome`, but the early "no schedule" return builds one too and needs the new argument. It reports every prop name as unmatched when props were given, like "no upcoming week". Cost if wrong: a count in the toast for a build with no schedule.
- [x] Task 3: The Odds API client and parser (commit e4ada44; `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test` → 137 pass, 1 skipped; `OddsTest` 7/7, `UrlConnectionHttpClientTest` 3/3). Watched failing first (unresolved `OddsApi`, …). No rulings.
- [x] Task 4: Props in `live.db` within the credit budget (commit bf7b432; `./gradlew :core:data:test --tests "dev.gridiron.core.data.live.*"` → pass, `PropsRepositoryTest` 8/8). Watched failing first (unresolved `PropsRepository`, …). No rulings.

**Session B** (2026-09-27). After Task 6: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` → BUILD SUCCESSFUL; `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL; the gate on a fresh 2024–2025 build passes with the MAE table unchanged (QB 6.42, RB 5.88, WR 5.40, TE 4.87; held 78/78/81/83%).

- [x] Task 5: The key in Settings (commit 546cdc5; `:core:datastore:test`, `SettingsRepositoryTest`, `SettingsScreenTest` → pass, 6/6 in Settings). Watched failing first (unresolved `oddsApiKey`, `setOddsApiKey`). No rulings.
- [x] Task 6: Props flow from Refresh into the forecast; docs (commit b3d9b9a; `:app:testDebugUnitTest` → 45/45; `IngestPipelineTest` 22/22). Watched failing first (`build` had no `props`; then `GridironApplication`'s 4-parameter `stats` lambda). No rulings.

**Final whole-branch review** (594867a..b3d9b9a; Opus). Verdict: "with fixes". It confirmed every Review Focus case, that props touch only the upcoming week, that rest of season and the waterfall stay consistent, and that the key never reaches a message.

- **Fixed** (commit 2ff2be2, each RED→GREEN; `./gradlew test` and `:app:assembleRelease` green):
  - **Important:** a failed events call (refused key, no network, 5xx) skipped pruning, so a game's props stayed past kickoff and could be blended into a later game between the same two teams. Pruning now runs before any call. Test: "a game's props are dropped 12 hours after kickoff even when the Odds API refuses the key".
  - **Important:** a game whose props weren't posted yet was stamped fetched and skipped for 24 hours. It's now stamped, and "Props fetched" set, only when quotes arrive; an empty answer costs no credits. Test: "a game with no props yet is asked again on the next refresh, once they're posted".
    - Ruling: this changes two of the plan's own Task 4 assertions (the 24-hour test and the credits test expected the empty e1 to count as fetched). Cost if wrong: one extra free call per empty game per refresh.
  - **Important (re-graded from Minor):** `HttpURLConnection` followed redirects, so a redirect would carry the key to another host, against spec §5's "sent only to api.the-odds-api.com". Redirects are now returned, not followed (they show as "answered HTTP 302"). Test: "a redirect is returned, not followed, so the key never reaches another host".
- **Declined to judge** (all stand, as plan rulings or user-approved judgments): the Gamma CV reading, `MARKET_VARIANCE_RATIO` and `ONE_SIDED_OVERROUND`, Yes/No fixtures, only priced stats move, the fetch window, the 401 wording, preseason events, a same-name player with no history, and the "no schedule" `PropsOutcome`.
- **Deferred minors** (the user decides):
  - The "didn't match a player" count also includes matched players with no usable quote or below the minimum points. Two same-name players count as 1.
  - Settings says "Props are fetched on the next refresh" when no key is set, and an old error and credits line stays after the key is removed.
  - `UserPrefs` is a data class, so its `toString()` includes the key. Nothing logs it today.
  - The receptions blend can push receptions above the unchanged targets (a catch rate over 100%).
  - `CLAUDE.md`'s `:app` line still says "Settings (seasons)", and The Odds API isn't under Data attribution.
  - When the stats build itself fails, the toast drops the props error. Settings still shows it.

- **Plan rulings to carry** (from the plan's self-review): fixtures hand-built, with anytime TD assumed `Yes`/`No`; market weight about two thirds (`MARKET_VARIANCE_RATIO` 0.5); a one-sided anytime-TD price divided by 1.08; only priced stats move; the fetch week runs to the Wednesday (00:00 UTC) after its first kickoff; props pruned 12 hours after kickoff; rest of season includes the blended week; `live.db` keeps `user_version` 1; `FORECAST_VERSION` 3.

## Range calibration (2026-09-27)

- **Problem.** The floor-to-ceiling range held only 53–66% of real games, against the spec's target of about 80%.
- **Cause.** The simulation draws each stat independently, and `poisson` TD counts ignore the stored variance. So scaling the variances (`EMPIRICAL_CV`) alone tops out: at 2.4× the spread, QB still held only about 72%, and RB needed more than 2.4×.
- **Choice.** The user chose approach A: a per-position factor that moves the floor and ceiling away from the projection, fitted on 2024 and 2025 pooled. The rejected alternative, B, fitted each stat's spread from past misses; it needed more constants and QB still fell short.
- **Constants** (`RANGE_WIDENING` in `ProjectedPoints.kt`): QB 1.40, RB 1.59, WR 1.52, TE 1.40. The floor stops at zero, or at the simulation's own floor when that is below zero. Positions with no factor keep the simulation's range. The widening is applied on the phone, so stored projections and `FORECAST_VERSION` are unchanged.
- **Where it applies.** `projectPoints` covers the list, the Player page card and the accuracy page. The waterfall uses the same `calibratedRange`.
- **Tests.** `ProjectedPointsTest` (3 new tests) and `ProjectionsViewModelTest` (the waterfall's floor and ceiling), each watched failing first.
- **Real data** (`etl/build/accuracy.db`; AccuracyGateTest's table; MAE unchanged):

  | Season | QB | RB | WR | TE |
  |---|---|---|---|---|
  | 2024 held | 79% (was 63%) | 80% (was 53%) | 77% (was 55%) | 76% (was 60%) |
  | 2025 held | 78% (was 64%) | 78% (was 54%) | 81% (was 61%) | 83% (was 66%) |

- **Caveat.** The 2025 figures are partly in-sample, because 2025 was used in the fit. The user accepted that.

## Projection accuracy: execution progress

**Session A** (2026-09-27). After Task 4: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` → BUILD SUCCESSFUL (the timing flake didn't show); `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL.

- [x] Task 1: The backtest (commit a4b8cfe; `./gradlew :core:projections:test` → 26/26 pass).
  - Watched failing first (unresolved `ProjectedWeek`, `PlayedWeek`, `errorStats`).
  - Ruling: Step 4 says 7 new `BacktestTest` tests; the brief's file has 6, and all 6 ran and pass. A count typo in the plan. Cost if wrong: none.
- [x] Task 2: The repository, the contract test and CI's accuracy gate (commit a4d296d; `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test :core:projections:test` → pass, the gate skipped).
  - Watched failing first (unresolved `seasons`, `backtest`).
  - Ruling: `AccuracyRepositoryTest`'s fixture gives 10 yards per catch, so week 2 (8 catches, a TD) scores 22, not the 24 the brief assumed (copied from Task 1's 100-yard fixture). The code returned model MAE 5.0 and last-4 MAE 9.5, and hand arithmetic agrees. Only the comments and those two expected values changed; the assertions and sample rules are the brief's, and `playerWeeks = 2` still pins the upcoming-week and didn't-play exclusions. Cost if wrong: none.
  - `AccuracyContractTest` on the 2024–2026 build: pass, 2.7 s.
  - Gate on `etl/build/accuracy.db`: 2024 **fails** "at [WR, TE]", as planned. 2025 **passes**, identical to the planning table:

    ```
    2025, PPR: MAE (bias) by predictor
    pos       n           model      season avg          last 4   held
    QB      498    6.42 (-0.19)    7.14 (-0.57)    7.07 (-0.56)    64%
    RB      804    5.88 (-1.34)    6.01 (-0.31)    6.18 (+0.03)    54%
    WR     1203    5.40 (-1.03)    5.79 (+0.28)    5.81 (+0.40)    61%
    TE      455    4.87 (-1.62)    5.19 (-0.15)    5.42 (+0.30)    66%
    ```
  - Note for the user: floor to ceiling holds only 54–66% of scores against the spec's target of about 80%, so the model's spread is too narrow. The page shows it; the gate doesn't check it.
- [x] Task 3: The accuracy page (commit b59b401; `./gradlew :feature:projections:testDebugUnitTest :core:data:test :app:compileDebugKotlin` → 31/31 in projections, Accuracy 8/8). No rulings.
- [x] Task 4: ☰ → Projection accuracy, and docs (commit 018b1e9; `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :app:testDebugUnitTest` → 39/39). No rulings.

**Final whole-branch review** (a503a94..c6ba3ab; Opus). Verdict: "ready with fixes". It agreed with both executor rulings. It confirmed every Review Focus case on real data and in CI, that the gate is deterministic (model points are the scored means, and the simulation is seeded), that there's no NaN path, and that every SQL value is bound.

- **Fixed** (commit 4fdf023, each RED→GREEN; `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` and `:app:assembleRelease` green):
  - **Important:** the page recomputed the whole backtest on every rotation, and a superseded load kept running. A repeat request for the season on screen (or for the one the Grid's season fell back to) now keeps it, and a new request cancels the old one. Tests: `AccuracyViewModelTest` "asking again for the season on screen keeps it without recomputing" and "a superseded load is cancelled, not left running".
  - **Re-graded Minor → Important:** a skipped gate passed CI, and the spec says the gate is never skipped. The step now deletes the table first and fails without one. The step's script with the gate variable unset: exit 0 before, exit 1 after; with 2025: exit 0.
- **Rulings:**
  - A cancelled load stops at its next suspension point, but a `backtest()` loop already running finishes (its result is dropped). The dedupe removes the rotation case, which leaves only a season tap mid-load. Cost if wrong: one extra 1–3 s of CPU.
  - Declined to judge by the reviewer; each stands:
    - The calibration shortfall belongs to sub-project 1's variances; the page reports it.
    - Last 4 may include last season's playoff games: they are games played.
    - A postponed game pinning the upcoming week is the forecast's rule.
    - The page not reloading after a refresh swap is the existing deferred minor.
    - Phone timing and memory are the user's checkpoint.
    - `accuracy_summary` in the Python schema: nothing reads it.
- **Deferred minors (the user decides):**
  - "Appeared" means a recorded play (a `g` row). A player on the field with no touch or target is left out. The 2025 upper bound is 45 WR, 9 RB, 7 TE and 2 QB player-weeks, some of them healthy scratches. The plan's evidence for the rule was circular (snaps attach only to rows with plays), and the footnote says "the player played".
  - "No player-weeks to measure in 2024 yet." says "yet" for a finished season, and a single position with nothing to count just disappears.
  - An exception's Unavailable state drops the season chips, and a null message reads "…: null."
  - The oldest built season (no prior-season history) is offered without a caveat. The model loses there at WR and TE.
  - CLAUDE.md says "a few seconds on a phone", which hasn't been measured.
  - `AccuracyContractTest`'s 15 s budget runs inside the parallel build (it measured 2.7 s).
  - Small waste: `status()` is read up to three times per load, `score(means)` runs twice per sample, and played weeks are grouped for every player.

## Projection engine: execution progress

**Session A** (2026-09-26). Batch check after Task 4: `./gradlew :core:forecast:test :core:ingest:test` → green; ETL pytest 119/119; `GRIDIRON_STATS_DB=<Kotlin-built 2024–2026 DB> ./gradlew test` → BUILD SUCCESSFUL.

- [x] Task 1: `:core:forecast` module, math and ridge solver (commit aa81989; `./gradlew :core:forecast:test` → 11/11 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `ewma`, `fitRidge`).
- [x] Task 2: Schedule download, `game` table and schema v7 (commit 9a47856; `./gradlew :core:ingest:test` → 119/119 pass).
  - Watched failing first (unresolved `readGames`, then `writeGames`).
  - Ruling: `StatsDbWriterTest` also asserted `ingest_version` "1"; the brief only named its schema version. Changed to "2" to match the brief's `INGEST_VERSION` bump; the assertion isn't loosened. Cost if wrong: none.
  - Ruling: `core/data`'s `AccuracyRepository` still selects from `accuracy_summary`, which schema 7 drops. Left as-is: `AccuracyScreen` is unreachable, the accuracy page is sub-project 2, and the phone-built DB never had rows there. Cost if wrong: that unreachable screen shows a query error instead of an empty page.
- [x] Task 3: Distribution family for every projected stat (commit 26822db; `./gradlew :core:ingest:test` → 120/120; ETL pytest 119/119; parity for 2025: **OK**, all 5 tables 0 differing, metric 81/81).
  - Watched failing first (unresolved `DIST_FAMILIES` / Python assertion).
  - Ruling: the Python parity build ran with `--force` (the brief omits it), so a stale `~/.cache` copy couldn't skew parity, as in the previous project's Plan 1 Task 12. Cost if wrong: none.
- [x] Task 4: Forecast inputs and league rates (commit 9f87510; `./gradlew :core:forecast:test` → 17/17 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `loadInputs`, `LeagueTotals`).

**Session B** (2026-09-26). Batch check after Task 8: `./gradlew :core:forecast:test :core:ingest:test :app:testDebugUnitTest` → 46 + 124 + 38 pass; ETL pytest 119/119; `GRIDIRON_STATS_DB=<Kotlin-built 2024–2026 DB> ./gradlew test` → 539/539 once `:core:statquery:test` ran alone (it failed only the known "scoring a full season … is fast" timing test under the full parallel build, and passed alone).

- [x] Task 5: Baseline model, layers 1–4 (commit d9b3a30; `./gradlew :core:forecast:test` → 23/23 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `BaselineModel`, `PlayerContext`, `TeamVolume`).
- [x] Task 6: Matchup and game script, layers 5–6 (commit b705176; `./gradlew :core:forecast:test` → 36/36 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `KINDS`, `MatchupModel`, `gameScript`).
- [x] Task 7: The walk-forward engine (commit 02a90a4; `./gradlew :core:forecast:test` → 46/46 pass).
  - Watched failing first (unresolved `Forecast`, `SeasonCopy`, `FORECAST_OK`). Production code verbatim.
  - Ruling: two of `ForecastEngineTest`'s matchup-note expectations named the wrong week-3 opponents. The test's own fixture schedules AAA–DDD and BBB–CCC in week 3, but its docstring said AAA–BBB. Corrected to WR_A "vs DDD" and WR_T "vs BBB", and fixed the docstring. The WR_T assertion now tells the new team (CCC, playing BBB) from the old one (AAA, playing DDD); the brief's value would have passed only if the old team were used. Cost if wrong: none.
- [x] Task 8: Run the forecast at the end of every refresh (commit 9557b7f; `./gradlew :app:testDebugUnitTest :core:ingest:test :core:forecast:test` → 38 + 124 + 46 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `report.forecast`, `IngestProgress.Projecting`; then the non-exhaustive `when` in `progressText`).
  - Real data: `./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025"` ends with `projections: ok` in 14 s wall time. The database has 280,614 `final` rows over 35 weeks (2024 weeks 2–18, 2025 weeks 1–18), `forecast_status` ok and `forecast_version` 1. There's no upcoming week or rest of season because both seasons are complete. A 2024–2026 build also reports `projections: ok`.

**Session C** (2026-09-26). After Task 12: `./gradlew test` passes 565/565 with `:core:statquery:test` run alone (the full parallel run fails only the known timing flake). ETL pytest passes 119/119. On a real 2024–2026 build: `projections: ok`, `ProjectionsContractTest` and `ForecastTimingTest` pass, and `forecast: 38 weeks, 330175 rows in 2.0 s` on the JVM.

- [x] Task 9: Real distribution families, the list and status queries, the active scoring profile (commit 73754f0). No rulings.
- [x] Task 10: ☰ → Projections list (commit 016fa4b). No rulings.
- [x] Task 11: The Player page "This week" card (commit 2f011b3). No rulings.
- [x] Task 12: Real-data contract and timing tests, and docs (commit c54dddf).
  - Ruling: Step 4 (retire the Python projection code) wasn't done. The permission classifier refused the deletion as irreversible. A grep confirmed that all 14 listed tests test only projection code. Cost if wrong: dead Python code stays until the user runs Step 4. (Done afterwards with the user's OK; see Things to know.)
  - Ruling: CLAUDE.md's `:feature:projections` line and its Data Flow Python ETL line were also updated (the brief didn't name them), because both had become false. Cost if wrong: none.

**Final whole-branch review** (9dabb9a..c54dddf; Opus). Verdict: "with fixes". It found 1 Critical, 3 Important and 11 Minor issues. It agreed with every executor ruling and confirmed the strengths below:
- walk-forward holds;
- a forecast failure never loses the stats;
- schema v7 degrades gracefully;
- there are no NaN or infinite values in real data.

- **Fixed** (commit 44d16f0, each with a test that failed first):
  - A team on bye in the upcoming week lost its players' rest of season. The Player card now says "Bye this week" and keeps rest of season. Tests: `ForecastEngineTest` "a team on bye in the upcoming week keeps its rest of season", `ProjectionCardTest`, `ThisWeekCardTest`.
  - A `games.csv` download error failed the whole refresh. It's now a warning, and the build falls back to the kept copy. Tests: `IngestPipelineTest` × 2.
- **Critical, not fixed here:** backups are projected like starters, team totals are about doubled, and stars are pulled down early. This is layer 2's design. The user chose to fix it in the next session. Spec amendment written; plan `2026-09-26-projection-share-fix.md`; CLAUDE.md Known Gaps updated.
- **Important, not fixed here:** the model loses to the season-to-date average (numbers above). It's largely the same cause, and sub-project 2's gate owns it.

**Layer-2 fix** (`docs/superpowers/plans/2026-09-26-projection-share-fix.md`, 2026-09-26). After Task 2, with `GRIDIRON_STATS_DB` set to a fresh 2024–2026 Kotlin build: `./gradlew :core:forecast:test :core:ingest:test :core:data:test` → 52 + 126 + 123 pass; `./gradlew :app:testDebugUnitTest :feature:projections:testDebugUnitTest` → 39 + 23 pass.

- [x] Task 1: Shares shrunk toward the player, a starter-only pass share, and team normalization (commit 3097c75; `./gradlew :core:forecast:test` → 49/49 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `shares`, `Shares`, `normalizeShares`).
- [x] Task 2: The projector projects a team at a time; real-data contract (`./gradlew :core:forecast:test` → 52/52; `ProjectionsContractTest` → 2/2 on real data).
  - Watched failing first: "only the starting QB is projected to pass" (QB2_A projected too) and "a player who hasn't played for his team lately isn't projected" (8 rows for WR_X).
  - Ruling: the third new test, "a team's players split exactly its targets and carries", already passed before Step 3 (the brief expected it to fail). In this fixture each team has exactly the players who get its targets and carries, and Task 1's shrink toward each player's own last season is linear, so their shares already summed to one. The test stays as written: it would have failed before Task 1, and it guards the invariant. The Task 1 `normalizeShares` unit test and the real-data contract test cover normalization directly. Cost if wrong: an engine-level normalization regression in a fixture this simple would be caught only by those two tests.
  - Real data (2024–2026 build, 19 s, `projections: ok`, `forecast_version` 2; upcoming week 2026 week 3):
    - QBs projected over 20 pass attempts: **32** (was 73).
    - Max team pass attempts: **39.8** (was 123). Team totals (min / average / max): attempts 23.5 / 32.9 / 39.8, targets 23.7 / 29.9 / 36.1, carries 17.1 / 26.9 / 37.0. Real averages are about 34, 30 and 27.
    - Ja'Marr Chase's targets: **7.5** final, 7.8 baseline (was 6.8).
- Final review: self-review only (no fresh reviewer for this 2-task plan); the user decides whether that's enough before merge.
- Deferred minor: `Rates.passShare` (`League.kt`) no longer has a caller.

Record each task as: `- [x] Task N: <name> (commit <sha>; <test command> → <result>)`, then `Ruling: <what> — <why> — <cost if wrong>` for any deviation.

---

# Previous project: Live Data Refresh (complete, PR #3 merged)

**How the user wants to work:** one fresh session per batch of **4 tasks** (changed 2026-09-26 from one task per session; the user may ask for more, as on 2026-09-26 when a session ran 5), with `/clear` after each. Every new session starts by reading this file, runs the next 4 tasks starting at **Next step** below, updates this file (tick each task, fill in the next one), commits, pushes, and stops.

**Branch:** `claude/dreamy-euler-phbdq1`. It is based on `claude/relaxed-hypatia-73hhub`, which serves as the repo's main branch. PR #2 was merged.

## Where things stand

- [x] **Design approved.** See `docs/superpowers/specs/2026-09-25-live-data-refresh-design.md`. Summary:
  - No stats come from the repo. The phone pulls stats from nflverse/ffopportunity and injuries/news from ESPN at refresh time.
  - Projections come later, in their own follow-up project.
  - Seasons are selectable in Settings.
- [x] **Plan 1 written.** `docs/superpowers/plans/2026-09-25-kotlin-ingest.md` has 12 tasks and covers the `:core:ingest` module, which ports the Python ETL to Kotlin. It includes a CI parity gate and an early "Time a stats build" menu item.
- [x] **Plan 2 written.** `docs/superpowers/plans/2026-09-25-live-refresh-app.md` has 11 tasks:
  1. `ReopenableQueryExecutor`
  2. Grid/Compare reload on a data version
  3. Seasons choice
  4. ESPN parser and HTTP client
  5. `live.db` store
  6. `LiveRepository` and `PlayerDirectory`
  7. `RefreshCoordinator`
  8. App wiring, Load stats and Settings, removing the bundled database
  9. News, Player page and live Injury report
  10. Grid injury badges
  11. Removing `etl.yml` and the benchmark, plus docs

  Its ESPN test fixtures are real responses recorded 2026-09-25, cut down and embedded in Task 4.
- [x] **Plans reviewed and approved** by the user (2026-09-26). **Execution method: native.**
  - Each session implements one task itself with the `executing-plans` skill: no per-task subagents or reviewers.
  - After Plan 2's last task, one fresh reviewer on the most capable model checks the whole branch.

## Next step

**Plan 2 is complete, and so is its final whole-branch review and fix pass** (see the end of this file). Nothing from the plans is left to execute.

1. **Wait for CI to go green on PR #3**, then hand the branch to the user with the `finishing-a-development-branch` skill. Merging PR #3 is the user's decision.
2. **The user checks the build on their phone** (non-blocking). The APK from dcd832a or later is the first with no bundled data. Install it over the current app and check:
   - The existing stats still show, with the "Stats now build on your phone" prompt.
   - **Refresh now** shows progress under the Grid, ends with a "Stats updated for …" toast, and the Grid reloads without a restart. Record the time the toast reports; the target is under a minute per season.
   - ☰ → News shows headlines, and tapping a player opens their Player page.
   - Injured players have a badge on the Grid.
   - ☰ → Settings lists seasons from 2012 onward.
3. **After that:** the deferred minors below (the user decides which), then the on-device projections follow-up. That project needs its own spec and plan.

## Execution progress

**Plan 1** (`2026-09-25-kotlin-ingest.md`):
- [x] Task 1: `:core:ingest` module and CSV reader (commit 8f2536c; `./gradlew :core:ingest:test` → 9/9 pass).
  - Ruling: the byte order mark is written as a `\uFEFF` escape, not a literal invisible character. The runtime bytes are the same. Cost if wrong: none.
- [x] Task 2: Metric registry (commit 28068e2; `./gradlew :core:ingest:test` → 14/14 pass).
  - No rulings. A throwaway dump of all 81 `Metric` rows diffed identical to Python's `METRICS` on every field, case preserved.
- [x] Task 3: Play-by-play reader and per player-week aggregation (commit 8dce2dd; `./gradlew :core:ingest:test` → 36/36 pass, 22 of them new).
  - No rulings. The brief's code blocks were written verbatim, and the tests were watched failing (unresolved `Play`) before the implementation went in.
- [x] Task 4: Derived rates and the long fact shape (commit 8e7c954; `./gradlew :core:ingest:test` → 52/52 pass, 16 of them new).
  - No rulings. The brief's code blocks were written verbatim, and the tests were watched failing (unresolved `weeklyPlayerStats`, `toFacts`, `Fact`) before the implementation went in.
- [x] Task 5: Upstream sources and an HTTP fetcher that asks "changed since?" (commit c8423a7; `./gradlew :core:ingest:test` → 61/61 pass, 9 of them new).
  - No rulings. The brief's code blocks were written verbatim, and the tests were watched failing (unresolved `Sources`, `Input`, `Validators`, `FetchResult`, `HttpFetcher`) before the implementation went in.
- [x] Task 6: Early phone timing — "Time a stats build" menu item (commit 74fef46; `./gradlew :core:ingest:test :app:testDebugUnitTest` → 64/64 ingest + 6/6 app pass; `:app:assembleRelease` BUILD SUCCESSFUL).
  - No rulings. Code written verbatim; `BenchmarkTest` watched failing (unresolved `benchmarkSeason`, `currentSeason`) first.
  - **Checkpoint (non-blocking), for the user:** once CI publishes the APK from this commit, install it, tap **☰ → Time a stats build**, and report the numbers. If crunch time exceeds about 60 s, record it here for the Plan 2 design. Not yet reported.
- [x] Task 7: Snap share (commit 6f2925d; `./gradlew :core:ingest:test` → 69/69 pass, 5 new).
  - No rulings. Code written verbatim; `SnapsTest` watched failing (unresolved `SnapRow`, `teamOffenseSnaps`, `attachSnapShare`, `readSnaps`) first.
- [x] Task 8: Players, injury reports, expected points and team defense (commit 1de46f4; `./gradlew :core:ingest:test` → 75/75 pass, 6 new).
  - No rulings. Code written verbatim; the four test classes watched failing (unresolved readers and `TeamDefenseAggregator`) first.
- [x] Task 9: The database writer (commit 0de55c0; `./gradlew :core:ingest:test` → 80/80 pass, 5 new).
  - No rulings. Code written verbatim; `StatsDbWriterTest` watched failing (unresolved `StatsDbWriter`, `readMeta`) first.
- [x] Task 10: Validation (commit 2d1da60; `./gradlew :core:ingest:test` → 101/101 pass, 21 new).
  - No rulings. Code written verbatim; both test classes watched failing (unresolved `crossCheck`, `FUMBLE_POLICY`, `validateDatabase`) first.
- [x] Task 11: The ingest pipeline (commit d88e6f6; `./gradlew :core:ingest:test` → 110/110 pass, 9 new).
  - No rulings. Code written verbatim; `IngestPipelineTest` watched failing (unresolved `IngestPipeline`) first.
- [x] Task 12: CLI, parity gate and CI switch (commit 2249afc; `etl` pytest 118/118; `./gradlew :core:ingest:test` → 111/111; parity for 2025: **OK** on all 5 tables, 274,373 facts; `./gradlew test` green on a Kotlin-built 2024–2026 DB).
  - Ruling: `buildStatsDb` used `the<SourceSetContainer>()`. Inside `tasks.register<JavaExec>` that resolves against the task and fails configuration, so the task uses the project's `sourceSets["main"]` accessor instead. Cost if wrong: none.
  - Ruling: the first parity run had 1 `snap_share` mismatch. The cause was the Python ETL reading a stale `~/.cache/gridiron/snap_counts_2025.csv` (0.12, where upstream now says 0.13), not the port. With `--force`, the result was `parity: OK`. Cost if wrong: none, because CI runners have no cache.
  - Ruling (a code change beyond the brief): bundled SQLite's `ANALYZE` writes `sqlite_stat4`, and Python's doesn't. `sqlite_stat4` steered scored grids off `idx_pws_metric_season_week`, which failed 2 contract tests (index unused; 524 ms against a 250 ms budget). `StatsDbWriter.finish` now drops `sqlite_stat4`, with a new `StatsDbWriterTest` test (RED→GREEN). Cost if wrong: the planner loses the stat4 samples, which matches the Python-built DB the app shipped before.
  - Ruling: the "scoring a full season … is fast" timing test (250 ms budget) fails under a full parallel `./gradlew build` in this 4-CPU container, with the **Python**-built DB too (395 ms). So it's CPU contention, not the port. Verified instead: `./gradlew test` (CI's command) is green with the Kotlin DB (median 242 ms), and so is `./gradlew build -x :core:statquery:test`. Cost if wrong: the margin is thin, so the test may flake on a slow CI runner. That thin margin was already there before this work.

The executing-plans ledger lives in git-ignored `.superpowers/` and does not survive the container. Rulings are copied here so the final reviewer sees them.

**Plan 2** (`2026-09-25-live-refresh-app.md`):
- [x] Task 1: `ReopenableQueryExecutor` (commit 39ea0ea; `./gradlew :core:database:test :app:compileDebugKotlin` → green, 4/4 new tests).
  - Ruling: `runCurrent()` is `@ExperimentalCoroutinesApi` and fails `-Werror`, so the one test that uses it has `@OptIn(ExperimentalCoroutinesApi::class)`, the repo's existing pattern (see `GridViewModelTest`). Cost if wrong: none.
- [x] Task 2: Screens reload on a new data version (commit c6d9b27; `./gradlew :core:data:test :feature:players:testDebugUnitTest :feature:compare:testDebugUnitTest` → green; 3 new Grid tests (18/18 in `GridViewModelTest`) and 1 new Compare test (7/7), none skipped).
  - No rulings. Code written verbatim; the tests were watched failing first (`No parameter with name 'dataVersion'`, `Unresolved reference 'rebase'`).
- [x] Task 3: A seasons choice in preferences (commit c47d430; `./gradlew :core:datastore:test :core:data:test` → green; `SettingsRepositoryTest` 7/7, `UserPrefsStoreTest` 6/6).
  - No rulings. Code written verbatim; watched failing first (unresolved `SeasonChoice`, `SettingsRepository`).
- [x] Task 4: Reading ESPN's news and injuries (commit 4bec92d; `./gradlew :core:data:test` → green; `EspnTest` 5/5, `UrlConnectionHttpGetTest` 3/3).
  - No rulings. Code and recorded fixtures written verbatim; watched failing first (unresolved `EspnParser`).
- [x] Task 5: The `live.db` store (commit ac74118; `./gradlew :core:data:test` → green; `LiveStoreTest` 10/10).
  - No rulings. Code written verbatim; watched failing first (unresolved `LiveDb`).
- [x] Task 6: `LiveRepository` and `PlayerDirectory` (commit 7485cf9; `./gradlew :core:data:test` → green; `PlayerDirectoryTest` 3/3, `LiveRepositoryTest` 7/7).
  - No rulings. Code written verbatim; watched failing first (unresolved `PlayerDirectory`).
- Batch check: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test :app:assembleRelease` → BUILD SUCCESSFUL after Task 6.
- [x] Task 7: `RefreshCoordinator` (commit d7aa620; `./gradlew :app:testDebugUnitTest --tests "*RefreshTextTest*" --tests "*RefreshCoordinatorTest*"` → 12/12 pass).
  - No rulings. Code written verbatim; watched failing first (unresolved `RefreshCoordinator`, `StatsBuilder`, `progressText`).
- [x] Task 8: App wiring, Load stats, refresh bar, Settings; no bundled database (commit ffb2794; `GRIDIRON_STATS_DB=… ./gradlew :app:testDebugUnitTest` → 27/27; `env -u GRIDIRON_STATS_DB ./gradlew :app:assembleRelease` → BUILD SUCCESSFUL; `assets/stats.db` entries in the APK: 0).
  - No rulings. Code written verbatim; watched failing first (unresolved `LoadStatsScreen`, `SettingsScreen`, `No parameter with name 'refresher'`).
- [x] Task 9: News, Player page, live Injury report (commit 8c9adca; `./gradlew :core:data:test :app:testDebugUnitTest` → 114/114 core:data, 36/36 app).
  - No rulings. Code written verbatim; watched failing first (unresolved `playerId`, `formatWhen`, `injuryReport`).
- [x] Task 10: Grid injury badges (commit 7378e76; `./gradlew :feature:players:testDebugUnitTest :app:testDebugUnitTest` → 45/45 players, 36/36 app). The recorded `11_injury_badge.png` shows a "Q" after the first player's name.
  - Ruling: Task 9 merged its new imports alphabetically, putting `java.time.Instant` ahead of `kotlinx.*` in `TeamScreens.kt`, against the repo's java-last order. The Task 10 commit moved it back. Cost if wrong: none (import order only).
  - Note: Roborazzi writes `build/outputs/roborazzi/*.png` only in record mode (`./gradlew :feature:players:recordRoborazziDebug`), not on a plain `testDebugUnitTest` run.
- Batch check: `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test :app:assembleRelease` → BUILD SUCCESSFUL after Task 10.
- [x] Task 11: Remove the repo data release, and update the docs (commit dcd832a; `./gradlew :core:ingest:test :app:compileDebugKotlin` → green; `SeasonsTest` watched failing (`Unresolved reference 'currentSeason'`) first; ETL pytest 118/118; `./gradlew test` green).
  - Ruling: the full parallel `./gradlew build` failed only the "scoring a full season … is fast" timing test (265 ms against a 250 ms budget). It's the same CPU-contention case as Plan 1 Task 12. `./gradlew build -x :core:statquery:test` was green, and so was `:core:statquery:test` run alone. Cost if wrong: the thin margin may flake on CI.
  - Ruling: Step 5's search matched only the git-ignored `.superpowers/` briefs and `NavigationTest`'s `assertDoesNotExist("Time a stats build")` guard. Both stand. With them excluded, the search is clean. Cost if wrong: none.
  - Ruling: two Python comments still name `.github/workflows/etl.yml` (`etl/gridiron_etl/validate.py:215`, `etl/tests/test_expected.py:63`). They are left as-is: they're outside the brief's file list, and the Python ETL is now only the parity reference. Cost if wrong: a stale comment.

### Final whole-branch review (442a3e9..dcd832a)

The reviewer ran on Opus, because Fable needs usage credits. It found **0 Critical, 5 Important and 10 Minor** issues. Verdict: "With fixes". It judged none of the recorded rulings wrong. All five Important findings were fixed in one pass (commit b98a0db). Each fix has a test that failed first. After the pass, `./gradlew test` passed 479/479, and `./gradlew build -x :core:statquery:test` (lint and the APK) was green.

**Fixed:**
- **A truncated optional nflverse file failed every refresh.** nflverse's `snap_counts_2012.csv.gz` is truncated, so checking 2012 made every refresh fail. Such a file is now left out with a warning. A bad play-by-play file now fails with an error that names its season. A real 2012 build now succeeds. Tests: `IngestPipelineTest` "a truncated optional file is left out…" and "a truncated play-by-play file fails the build naming its season".
- **A built season was dropped when its play-by-play briefly returned 404.** It is now kept from the last build. Test: `IngestPipelineTest` "a built season is kept when its play-by-play briefly disappears".
- **A per-entry ESPN shape change wiped the injury list.** ESPN entries that all fail to parse are now a `LiveFormatException`, so the last good data stays. Test: `EspnTest` "entries that all changed shape are a format error".
- **`live.db` errors crashed the live screens.** `LiveRepository` no longer throws to a screen: failed reads come back empty, and a refresh that can't save says why. Test: `LiveRepositoryTest` "a live_db that can't be opened never throws to a screen".
- **An unopenable `stats.db` left no way to refresh.** The Grid's failed state now offers Refresh stats and Settings. `stats.db.new` is also fsynced before it replaces `stats.db`. Test: `GridScreenTest.aDatabaseThatWontOpenStillOffersARefresh`.

**Rulings:**
- Final: the fsync has no failing test, because durability isn't observable on the JVM. The recovery button is the tested half of the finding. Cost if wrong: a regression that removes the fsync would go unnoticed.
- Final: the second half of Important 2 is not fixed. That case is a prior season's optional file returning 404 while its play-by-play changed, which rebuilds the season without that file. It needs both at once and heals on the next refresh. Cost if wrong: a season briefly lacks snaps, xFP or injuries.
- Final: a `live.db` corrupt past its header is not recreated. It now degrades to empty data plus "couldn't save live data" instead of a crash. Cost if wrong: live data stays empty until the user clears app data.
- Final (declined to judge by the reviewer; each stands):
  - The app-scope coroutine refresh: the spec accepts it.
  - Player-page news comes only from the 50-item league feed: that's the spec's endpoint.
  - The on-device timing was never measured: the toast reports it, and the user checkpoint asks for it.
  - Parity runs on 2025 only: manual Kotlin builds of 2012, 2013, 2016 and 2019 succeed.
  - One mutex for all stats queries: this matches the previous single-connection model.

**Deferred minors (the user decides):**
- Stale comments: `GridScreen.kt` says "Tap opens the projection", and `AndroidManifest.xml` says "Data ships inside the app".
- After process death, `stats.db.new` and `ingest-work/*.part` stay behind until the next refresh.
- `StatsDbWriter.copySeasonFrom`:
  - `DETACH` in `finally` can mask the real error.
  - `ATTACH` is not opened `mode=ro`.
  - A Kotlin-built database with corrupt pages but readable meta fails the copy on every refresh.
- `LiveRepository.fetchedAt` needs both feeds, so the Injury report shows no "as of" when only injuries arrived.
- Injury notes are keyed on `(espn_id, noted_at)`, so a changed comment is dropped when ESPN's date doesn't change.
- Team defense, the official Injury report and the Player page don't reload after a swap until they are reopened.
- Wording:
  - "No injury report published yet" appears for past seasons nflverse never publishes.
  - `MissingColumnsException` says "nflverse" for ffopportunity files.
- The 250 ms timing test has a thin margin on CI.
