# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Gridiron** is a personal-use Android app for NFL fantasy football analytics. It computes ~450+ stats from open nflverse data and displays them in a real mobile stat table with filtering, sorting, and comparison tools. The phone builds its own SQLite stats database from nflverse and ffopportunity when the user taps Refresh, and works offline between refreshes. Injuries and news come live from ESPN.

**Starting a session:** `git fetch origin claude/relaxed-hypatia-73hhub` and fast-forward (the user also commits to this branch, so a container checkout goes stale; never plan from an unfetched copy or call a file current without having fetched), then read `docs/superpowers/HANDOFF.md`: where things stand, what is open, the rulings that still bind and what to do next.

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

**Modules:** `:core:model` (shared types), `:core:statquery` (`StatQuerySpec` → parameterized SQL), `:core:database` (read-only SQLite, `ReopenableQueryExecutor`), `:core:datastore` (prefs JSON, `formatVersion` 5), `:core:testing` (JDBC fixtures), `:core:projections` (`score()`, `projectedScore`, Monte Carlo, `backtest()`), `:core:ingest` (builds `stats.db` on the phone and the JVM, runs `:core:forecast`), `:core:forecast` (projection model, constants in `ForecastConstants.kt`), `:core:data` (packs, repositories, `live` ESPN/props), `:core:table`, `:core:charts`, `:core:ui`, `:core:designsystem`, `:feature:players` (Grid), `:feature:scoring`, `:feature:compare`, `:feature:projections`, `:app` (entry, refresh, Player page, News, Injury report, Settings). `etl/` is Python, CI's parity reference only.

**Rules that hold everywhere:** raw stat components are stored and league scoring is applied on-device; rates are recomputed over the range, never averaged; `stats.db` is replaced whole and opened read-only; no year in table or column names; Navigation 3, no Hilt.

**Data flow:** Refresh → `IngestPipeline` builds `stats.db.new` (nflverse, ffopportunity, ESPN's weekly projections, forecast) → `RefreshCoordinator` swaps it in and screens reload; ESPN news and injuries go to `live.db`.

## Working rules

- **Plans:** for native execution, write a short plan: tasks, interfaces and test names, no full code. The spec carries the design.
- **PRs:** one PR per feature. HANDOFF updates ride in that PR: no docs-only PRs, no check-ins or subscriptions for docs-only changes.
- **Specs and plans** are deleted once executed; they stay in git history (`git log --diff-filter=D -- docs/superpowers`).
- **Branch:** work directly on `claude/relaxed-hypatia-73hhub`; never create a new branch.
- **Execution:** run plans with `executing-plans` in the current session, always, unless the user says otherwise; don't offer subagents.

## Testing Strategy

**Three test tiers in `:core:statquery`:**

1. **SQL Execution** — In-memory SQLite with hand-computed expected values
2. **Safety & Determinism** — No database; validates SQL injection safety and deterministic output
3. **Contract Tests** (Real Database) — Against a real ETL-built database; runs 413k+ player-week-column values through the Kotlin query builder and verifies they match ETL stored values

To run contract tests locally, set `GRIDIRON_STATS_DB` before running tests (CI builds a fresh database first).

**Accuracy gate** — CI's parity job builds 2024–2025 and runs `AccuracyGateTest`. The gate fails if the model's 2025 MAE under PPR isn't below the season-to-date average's at QB, RB, WR, TE, K and D/ST, and the job prints the table. It's never skipped: if it fails, tune the forecast's constants.

## Performance Notes

- Full-season 12-column FLEX grid: ~86 ms from weekly facts, ~36 ms from the season rollup (`player_window_stat`) on the JVM; a scored Fantasy grid ~135 ms weekly, ~29 ms rollup (not measured on-device)
- `stats.db` is pre-indexed, `ANALYZE`d, and `VACUUM`ed, so the query planner has statistics on first launch
- Covering index on `(metric_id, season, week, value)` eliminates secondary lookups for common aggregation queries
- All queries use parameterized binds (`?`), no string interpolation

## Known Gaps

Features and where they live: `docs/ARCHITECTURE.md` (Features). Gaps that still hold:

- Of the ~450 catalogued metrics only play-by-play, snap count, ffopportunity, Next Gen Stats (ten headline metrics) and FTN charting (twenty, from 2022) are implemented (registry: `core/ingest/.../Metrics.kt`). NGS and FTN show in the Grid, Compare, the Player page's season line and its "Charted by week" table. FTN's trick plays, starting hash and backfield count are not stored; its read is kept only as the first-read rate, QB location only as the shotgun rate
- **ESPN's endpoints are unofficial and keyless**; a shape change shows as "Not updated: ESPN changed its … format" with the last data kept (`core/data/.../live/Espn.kt`, recorded responses in `core/data/src/test/resources/espn/`). The fantasy league shapes (`mMatchup`, `mTransactions2`, teams, rosters) are from memory and unverified against a live league; only `mSettings` was checked
- **ESPN's projections are blended in** (`EspnBlend.kt`, `ESPN_WEIGHT`: QB 0.55, RB 0.6, WR 0.65, TE 0.5, fitted leaving one season out on 2022-2025); the accuracy page and CI gate measure the blend. Without ESPN the model projects alone and the refresh warns. K and D/ST are model-only
- **Not modeled:** weather (out of scope by the user's call) and a player's chance of playing: an Out or Doubtful player from nflverse's injury report gets no projection and his teammates take his share (his rest of season counts each coming game times the chance such a player is back by then, `returnCurve`), while a Questionable one is projected at what he scores if he plays times how often such a player plays at his Friday practice level (`QUESTIONABLE_PLAYS`: full 0.87, limited 0.78, none 0.52), his teammates not taking any of it; an RB, WR or TE who hasn't played in his team's last two games is projected again once ESPN projects him for 3+ PPR points (`RETURN_MIN_ESPN_POINTS`); from the upcoming week on, the starting QB is the one ESPN projects for 8+ points when nflverse lists none, and ESPN overrides a listed starter it projects under 3 (`STARTER_ESPN_POINTS`)
- **Judgments, not fits:** K and D/ST constants (tuned only as far as the gate needs), `MARKET_VARIANCE_RATIO` and `ONE_SIDED_OVERROUND` (props can't be backtested: no historical props; the accuracy page and gate measure the model alone), `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY`, `DST_YA_CV` (no posted yards line), Rising roles weights, `BENCH_WEIGHT`, the FAAB curve, draft suggestions
- Yards-allowed tiers default to ESPN's, unverified against ESPN's own table
- **A refresh runs in an application-scope coroutine that a WorkManager `RefreshWorker` joins**: if Android kills the process mid-build, the old database stays and WorkManager restarts the build from the beginning (the game-day refresh, Thursday, Saturday night, Sunday morning and Monday, is `GameDayRefreshWorker`)
- **The accuracy page's backtest is kept in memory** per season and profile until the next refresh or app restart
- APK signing uses a committed keystore (`app/gridiron.keystore`, intentional for a never-published personal app)

## Codebase Notes

- **Kotlin style** — Official Kotlin style guide (enforced by linting)
- **Android SDK** — Platform 37, JDK 17+
- **Gradle caching & configuration cache** enabled (`gradle.properties`)
- **Build artifact** — APK auto-published to [releases/download/app/gridiron.apk](https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk) on every push and every Tuesday morning
- **Data attribution** — nflverse (CC BY 4.0), FTN Data via nflverse (CC BY-SA 4.0), ffopportunity, ESPN (news, injuries and weekly projections), The Odds API (player props, with the user's own key), Fantasy Football Calculator ADP, FantasyCalc (dynasty and redraft values), US National Weather Service
