# Session Handoff: Live Data Refresh

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

**Execute Plan 2, Tasks 7–11** (`docs/superpowers/plans/2026-09-25-live-refresh-app.md`), natively, with the `executing-plans` skill. These are the plan's last five tasks:
- Task 7: `RefreshCoordinator`
- Task 8: App wiring, Load stats and Settings, removing the bundled database
- Task 9: News, Player page and live Injury report
- Task 10: Grid injury badges
- Task 11: Removing `etl.yml` and the benchmark, plus docs

The standing batch size is 4, but the 2026-09-26 session ran 5 at the user's request. If the next session also runs 5, it finishes the plan. After that comes the final whole-branch review: one fresh reviewer on the most capable model.

1. For each task in the batch, follow the task's steps exactly: failing test, implementation, passing test, commit.
2. Tick each task under **Execution progress** below.
3. Set this section to the task after the batch.
4. Commit, push, and stop.

First, check that CI is green on this handoff commit. CI was green on 5605a11, including the `parity` job, before this batch started.

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
- Tasks 7–11 not started.
