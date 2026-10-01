# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Gridiron** is a personal-use Android app for NFL fantasy football analytics. It computes ~450+ stats from open nflverse data and displays them in a real mobile stat table with filtering, sorting, and comparison tools. The phone builds its own SQLite stats database from nflverse and ffopportunity when the user taps Refresh, and works offline between refreshes. Injuries and news come live from ESPN.

**Starting a session:** read `docs/superpowers/HANDOFF.md` first. It says where things stand, what is open, the rulings that still bind and what to do next.

## Common Commands

### Data Pipeline (Python/ETL)
```bash
cd etl
pip install -r requirements.txt

# Build the stats database (required before running Android code)
python -m gridiron_etl.build --seasons 2024 2025 2026 --out build/stats.db

# Same database, built by the Kotlin code the phone runs
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025 2026" -Pout=etl/build/stats.db

# Prove the two agree (CI runs this for 2025)
python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db

# Run ETL tests
python -m pytest tests/ -q
```

### Android Tests & Builds (requires JDK 17+, Android SDK with platform 37)
```bash
# Set the stats database path (required for tests; the APK carries no data)
export GRIDIRON_STATS_DB=etl/build/stats.db

# Run all tests (executes against the real ETL database via the JDBC fixture)
./gradlew test

# Run tests for a single module
./gradlew :core:statquery:test
./gradlew :core:forecast:test

# Run tests for a specific test class
./gradlew :core:statquery:test --tests "StatQueryBuilderTest"

# CI's accuracy gate: the model must beat the season-to-date average in 2025 under PPR
./gradlew :core:ingest:buildStatsDb -Pseasons="2024 2025" -Pout=etl/build/accuracy.db
GRIDIRON_STATS_DB=etl/build/accuracy.db GRIDIRON_ACCURACY_GATE=2025 ./gradlew :core:data:test --tests "dev.gridiron.core.data.AccuracyGateTest"

# Build the APK
./gradlew :app:assembleRelease

# Record or update Roborazzi screenshot tests for the Grid screen
./gradlew :feature:players:recordRoborazziDebug

# Clean build (if configuration issues arise)
./gradlew clean
```

## Architecture

Detail (module behavior, data flow, design decisions, schema): `docs/ARCHITECTURE.md`. Read it before changing a module.

**Modules:** `:core:model` (shared types), `:core:statquery` (`StatQuerySpec` → parameterized SQL), `:core:database` (read-only SQLite, `ReopenableQueryExecutor`), `:core:datastore` (prefs JSON, `formatVersion` 3), `:core:testing` (JDBC fixtures), `:core:projections` (`score()`, `projectedScore`, Monte Carlo, `backtest()`), `:core:ingest` (builds `stats.db` on the phone and the JVM, runs `:core:forecast`), `:core:forecast` (projection model, constants in `ForecastConstants.kt`), `:core:data` (packs, repositories, `live` ESPN/props), `:core:table`, `:core:charts`, `:core:ui`, `:core:designsystem`, `:feature:players` (Grid), `:feature:scoring`, `:feature:compare`, `:feature:projections`, `:app` (entry, refresh, Player page, News, Injury report, Settings). `etl/` is Python, CI's parity reference only.

**Rules that hold everywhere:** raw stat components are stored and league scoring is applied on-device; rates are recomputed over the range, never averaged; `stats.db` is replaced whole and opened read-only; no year in table or column names; Navigation 3, no Hilt.

**Data flow:** Refresh → `IngestPipeline` builds `stats.db.new` (nflverse, ffopportunity, forecast) → `RefreshCoordinator` swaps it in and screens reload; ESPN news and injuries go to `live.db`.

## Working rules

- **Plans:** for native execution, write a short plan: tasks, interfaces and test names, no full code. The spec carries the design.
- **PRs:** one PR per feature. HANDOFF updates ride in that PR: no docs-only PRs, no check-ins or subscriptions for docs-only changes.
- **Specs and plans** are deleted once executed; they stay in git history (`git log --diff-filter=D -- docs/superpowers`).
- **Branch:** work directly on `claude/relaxed-hypatia-73hhub`; never create a new branch.
- **Execution:** run plans with `executing-plans` in the current session, always, unless the user says otherwise; don't offer subagents.
- **Start of every session:** `git fetch origin claude/relaxed-hypatia-73hhub` and fast-forward before reading `docs/superpowers/HANDOFF.md`. The user also commits to this branch, so a container checkout goes stale; never plan from an unfetched copy, and never say a file is current without having fetched.

## Testing Strategy

**Three test tiers in `:core:statquery`:**

1. **SQL Execution** — In-memory SQLite with hand-computed expected values
2. **Safety & Determinism** — No database; validates SQL injection safety and deterministic output
3. **Contract Tests** (Real Database) — Against a real ETL-built database; runs 413k+ player-week-column values through the Kotlin query builder and verifies they match ETL stored values

To run contract tests locally, set `GRIDIRON_STATS_DB` before running tests (CI builds a fresh database first).

**Accuracy gate** — CI's parity job builds 2024–2025 and runs `AccuracyGateTest`. The gate fails if the model's 2025 MAE under PPR isn't below the season-to-date average's at QB, RB, WR, TE, K and D/ST, and the job prints the table. It's never skipped: if it fails, tune the forecast's constants.

## Performance Notes

- Full-season 12-column FLEX grid with percentiles: ~85 ms on CI JVM (not yet measured on-device)
- `stats.db` is pre-indexed, `ANALYZE`d, and `VACUUM`ed, so the query planner has statistics on first launch
- Covering index on `(metric_id, season, week, value)` eliminates secondary lookups for common aggregation queries
- All queries use parameterized binds (`?`), no string interpolation

## Known Gaps & Next Steps

- Only the play-by-play, snap count, ffopportunity, Next Gen Stats (ten headline metrics; Grid packs NGS Passing, Rushing and Receiving) and FTN charting (ten headline metrics from 2022; Grid packs FTN Passing and FTN Receiving) metrics of the ~450 catalogued are implemented (registry: `core/ingest/.../Metrics.kt`). NGS and FTN show in the Grid, Compare (Efficiency group, plus FTN drops and created receptions under Context for WR and TE) and the Player page's season line (a row only when the player has the stat); the game log doesn't use them. FTN's other flags (screen, RPO, motion, no-huddle, box counts) are not stored
- Season rollups (`player_window_stat`: whole season and last 3, 4, 5 and 8 weeks) serve any Grid view whose weeks match a window, the Fantasy pack included (its points weight the window's sums; only yardage bonuses and points- and yards-allowed tiers read weekly facts); any other week range aggregates the weekly facts (a full-season 12-column FLEX grid: ~86 ms weekly, ~36 ms rollup on the JVM; a scored Fantasy grid ~135 ms weekly, ~29 ms rollup, not yet measured on-device)
- Saved Grid presets and rosters are stored in the `:core:datastore` prefs JSON (`UserPrefs.gridPresets`, `UserPrefs.rosters`), not a `user.db`; the Grid's Presets chip saves and applies views (pack, sort, position, filters and a whole-season or last-N-weeks rule, never the season or scoring). Rosters: ☰ → Rosters manages them, the Player page toggles membership, and the Grid's roster chip narrows to one (`GridRequest.onlyPlayers`) and stars rostered players; once an ESPN league is synced for the season shown, the chip's "Free agents" lists everyone on no league team (`GridRequest.excludePlayers`, from the last snapshot, still ranked)
- APK signing uses a committed keystore (`app/gridiron.keystore`, intentional for a never-published personal app)
- **Grid layout**: a two-row top bar that slides away on scroll, a "View & filters" sheet for the rarely used controls, row height saved as `gridDensity`, digits-only percent cells and no trend line (see `docs/ARCHITECTURE.md`).
- **Grid entry points**: tapping a Grid row opens the Player page (ESPN status, injury notes, tagged news, and a "This week" projection card that opens the waterfall, Season stats); the ☰ menu opens Projections (the upcoming week or rest of season by position, K and D/ST included, scored with the active profile), Projection accuracy, News, Scores, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats.
- **Scores**: ☰ → Scores lists a season's weeks (game table: schedule, final scores, spread; ESPN's scoreboard adds kickoff, clock and live scores, fetched when a week with games to play opens, every minute while one is live); a game opens both teams' players with that week's fantasy points under the active profile. `ScoresRepository` (`core/data`), `EspnParser.scoreboard`, `ScoresScreen.kt` (`app`). The scoreboard parse is tested against a recorded response (`core/data/src/test/resources/espn/scoreboard.json`, its live game built by hand).
- **ESPN fantasy league import**: ☰ → ESPN league reads standings and rosters from ESPN's unofficial fantasy API (a private league needs the user's `espn_s2` and `SWID` cookies, sent only to ESPN); parsing in `core/data/.../live/FantasyLeague.kt`, repository in `FantasyLeagueRepository.kt`. Unverified against a live league. **League matchups**: the Matchups button on that screen lists a week's head-to-heads with ESPN's points and the app's beside them (the app's under the active profile, from `stats.db`, so a dash while a game is live or before nflverse catches up); lineups open per matchup. The `mMatchup` shape is from memory and unverified.
- **ESPN's endpoints are unofficial and keyless**; a shape change shows as "Not updated: ESPN changed its … format" with the last data kept. Parsing lives in `core/data/.../live/Espn.kt`, tested against recorded responses in `core/data/src/test/resources/espn/`.
- **Refresh runs in an application-scope coroutine, not WorkManager**: if Android kills the process mid-build, the old database stays and the next refresh starts over.
- **Props can't be backtested**, because there are no historical props. The blend's weight (`MARKET_VARIANCE_RATIO`) and the one-sided anytime-TD margin (`ONE_SIDED_OVERROUND`) are judgments, not fits, and the accuracy page and CI gate measure the model alone.
- **The accuracy page recomputes on every open** and after a refresh (off the main thread; its phone time isn't measured yet); nothing is cached between visits.
- **Not modeled:** weather (out of scope by the user's call) and a player's chance of playing: an Out or Doubtful player from nflverse's injury report gets no projection and his teammates take his share, while a Questionable one is projected as playing; a player returning from injury isn't projected until he plays again.
- **K and D/ST constants are judgments** (`ForecastConstants`), tuned only as far as the gate needs.
- **Blocked kicks aren't scored** for a D/ST. Yards allowed are (net yards, in editable tiers, ESPN's defaults unverified against ESPN's own table). `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY` and `DST_YA_CV` are judgments, and there is no posted yards line to backtest against.

## Codebase Notes

- **Kotlin style** — Official Kotlin style guide (enforced by linting)
- **Android SDK** — Platform 37, JDK 17+
- **Gradle caching & configuration cache** enabled (`gradle.properties`)
- **Build artifact** — APK auto-published to [releases/download/app/gridiron.apk](https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk) on every push and every Tuesday morning
- **Data attribution** — nflverse (CC BY 4.0), FTN Data via nflverse (CC BY-SA 4.0), ffopportunity, ESPN (news and injuries), The Odds API (player props, with the user's own key), Fantasy Football Calculator ADP, US National Weather Service
