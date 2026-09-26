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
