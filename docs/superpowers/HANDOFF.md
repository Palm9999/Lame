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
- [ ] **Final whole-branch review** (a fresh reviewer on the most capable model), then its fix pass.

## Next step: sub-project 2's final review

1. Run the final whole-branch review (`executing-plans` "Final Review") over a503a94..HEAD, with the plan's Review Focus and the rulings below. Fix Critical and Important findings in one pass, each RED→GREEN.
2. Wait for CI on the draft PR (https://github.com/Palm9999/Lame/pull/5, watched): the parity job now runs the accuracy gate.
3. **The user checks the build on their phone** (non-blocking): ☰ → Projection accuracy, season 2025. Report how long "Scoring every projected week…" shows, and whether the numbers match the gate table below under PPR. Over about 10 s → record it here (the fix would be caching per season and profile, out of this plan's scope).
4. `etl/build/accuracy.db` (2024–2025) and `etl/build/stats.db` (2024–2026) exist in this container. Rebuild them if the container is fresh (commands are in the plan).

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

**PR #4:** merged. The watch and its check-ins are cancelled.

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
