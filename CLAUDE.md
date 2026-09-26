# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Gridiron** is a personal-use Android app for NFL fantasy football analytics. It computes ~450+ stats from open nflverse data and displays them in a real mobile stat table with filtering, sorting, and comparison tools. The phone builds its own SQLite stats database from nflverse and ffopportunity when the user taps Refresh, and works offline between refreshes. Injuries and news come live from ESPN.

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

# Build the APK
./gradlew :app:assembleRelease

# Record or update Roborazzi screenshot tests for the Grid screen
./gradlew :feature:players:recordRoborazziDebug

# Clean build (if configuration issues arise)
./gradlew clean
```

## Architecture

### Module Structure

**JVM Modules** (plain Kotlin, platform-independent):
- `:core:model` — Shared types used across the app
- `:core:statquery` — Query builder that turns `StatQuerySpec` into SQL. Guarantees rate recomputation, SQL injection safety, determinism, and stable percentiles. Three test tiers: in-memory SQLite, safety/determinism checks, and contract tests against the real ETL database
- `:core:database` — Read-only SQLite access via the bundled driver. `ReopenableQueryExecutor` closes and reopens the connection when a refresh swaps in a new `stats.db`, and bumps a version flow the Grid and Compare reload on
- `:core:testing` — Test fixtures: JDBC executor over the real database. Proves results match what the phone's SQLite driver will return
- `:core:projections` — Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring), factor attribution, and single-player Monte Carlo (floor/ceiling) for the Projections feature
- `:core:ingest` — Builds `stats.db` from nflverse and ffopportunity: a streaming CSV reader, Kotlin ports of the ETL's transforms and validation, and a pipeline that re-downloads only files whose ETag changed and copies unchanged seasons from the previous build. It also downloads nflverse's schedule (`games.csv`) into the `game` table and runs `:core:forecast` after validation; a forecast failure leaves the stats and records why. Runs on the phone and on the JVM (`./gradlew :core:ingest:buildStatsDb -Pseasons="2025" -Pout=etl/build/stats.db`); CI's parity job holds it to the Python ETL's values
- `:core:forecast` — The projection model. Reads a freshly built stats.db and writes weekly, rest-of-season and waterfall-factor projections for QB/RB/WR/TE, walk-forward (each week only from the games before it). Seven layers: team volume, shrunk share, shrunk efficiency, expected TDs, opponent ratings (ridge), game script from nflverse's lines, distributions. Every constant is in `ForecastConstants.kt`; bump `FORECAST_VERSION` when one changes

**Android Modules**:
- `:app` — App entry point. `RefreshCoordinator` builds `stats.db` on the phone with `:core:ingest` and swaps it in without a restart; News, Player page, live Injury report, Settings (seasons) and Load stats screens
- `:feature:players` — The Grid screen (main UI) and its ViewModel
- `:core:table` — Frozen-column stat table with shared horizontal scroll state
- `:core:designsystem` — Theme, dark mode, colorblind-safe heat scale
- `:core:data` — Stat packs, qualifying bars, formatting, repositories; `SettingsRepository` (which seasons to build); `PlayerDirectory` (ESPN id → player via `player_xref`); and the `live` package: the ESPN news/injuries parser and client, the writable `live.db` store, and `LiveRepository`
- `:feature:projections` — The Projections list (☰ → Projections), the Player page's "This week" card, the waterfall screen (`ProjectionsKey`), and the accuracy ("trust page") screen (`AccuracyKey`, not yet reachable; see Known Gaps)

### Data Flow

1. **Refresh on the phone** — ☰ → Refresh stats (or Load stats on a fresh install) runs `:core:ingest`'s `IngestPipeline` for the seasons chosen in Settings. It downloads nflverse and ffopportunity files with conditional GETs, copies unchanged seasons from the current database, crunches the rest, validates, and writes `stats.db.new`
2. **Forecast** — the same build projects every regular-season week of the chosen seasons into the projection tables (the upcoming week with both stages and factors, past weeks' final stage for the backtest, rest of season summed); the refresh toast says if projections are unavailable
3. **Swap** — `RefreshCoordinator` renames `stats.db.new` over `stats.db` inside `ReopenableQueryExecutor.swap`; screens reload on the version bump. A failed build leaves `stats.db` untouched
4. **Live data** — the same refresh (and the News, Player and Injury report screens, when data is over 15 minutes old) fetches ESPN's news and injuries into `live.db`, linked to players through `player_xref`, pruned at 30 days
5. **Query Layer** — `:core:statquery` generates parameterized SQL for any stat grid query (columns, filters, week ranges, percentiles). Tests run it through the JDBC executor against a Kotlin-built database (`GRIDIRON_STATS_DB`); the phone runs it through the bundled SQLite driver
6. **Python ETL** (`etl/`) — kept only as CI's parity reference for the Kotlin port; nothing it builds reaches the app

### Key Design Decisions

**Stat Components, Not Fantasy Points** — The database ships raw stats (receptions, yards, touchdowns). League scoring is applied on-device, so any league format works offline.

**Rates Recomputed, Never Averaged** — Target share over weeks 1–8 is `Σ targets / Σ team targets`, not the mean of eight weekly values. Internal metrics (team totals, component sums) are stored to enable recomputation over any range.

**Frozen-Column Table** — The Grid has one shared horizontal scroll state across all columns, making sticky-column synchronization straightforward.

**Stats Database Replaced Whole, Never Edited** — The app opens `stats.db` read-only; a refresh builds a complete new file beside it and swaps it in atomically. Live ESPN data lives in a separate `live.db` that is updated in place and can be deleted at any time. User state (presets, rosters) will live in `user.db` when implemented.

**No Hilt or Navigation Yet** — Current single-screen setup. Hilt and Navigation 3 will arrive with the second feature.

### Database Schema (Version 7)

Long/narrow design: adding a metric is an `INSERT`, not a migration.

| Table | Purpose |
|---|---|
| `player_week_stat` | Facts: `(player_id, season, week, team, metric_id, value)`, indexed as covering index on `(metric_id, season, week, value)` |
| `metric` | Metric registry: name, definition, formula, tier, predictive use, stability, internal flag, plus `dist_family` (distribution for on-device Monte Carlo/percentile reconstruction) and `zero_inflated` |
| `player` | Players with at least one stat in the built seasons |
| `player_xref` | ESPN athlete id → `player_id` for every player nflverse lists, stats or not; links ESPN news and injuries |
| `schema_meta` | Schema version, seasons, attribution |
| `game` | nflverse schedule for the built seasons: opponents, results, spread and total, starting QBs, head coaches; the forecast's matchups and game script |
| `player_week_projection` | Per (player, week, metric, stage) projected mean/variance, written by `:core:forecast` — `stage` is `baseline` (post volume-cascade) or `final` (fully adjusted) |
| `player_week_projection_factor` | Per (player, week, factor) log-space attribution multiplier for one projection adjustment stage |
| `player_ros_projection` | Rest-of-season aggregate: summed weekly mean/variance per (player, metric), no per-week detail |
| `team_week_defense` | Per (team, season, week) points/yards allowed, sacks, INTs, fumbles recovered, defensive TDs |
| `injury_report` | Per (player, season, week) nflverse injury report status/injury/practice |

**`live.db`** (separate file, `PRAGMA user_version` 1): `news_item`, `news_player` (ESPN id, name, nullable `player_id`), `injury_status` (current snapshot), `injury_note` (appended when a comment changes), `live_meta` (fetch times). Rows older than 30 days are pruned; an unreadable file is recreated.

**No year in table/column names** — New seasons are data rows, not schema changes.

## Testing Strategy

**Three test tiers in `:core:statquery`:**

1. **SQL Execution** — In-memory SQLite with hand-computed expected values
2. **Safety & Determinism** — No database; validates SQL injection safety and deterministic output
3. **Contract Tests** (Real Database) — Against a real ETL-built database; runs 413k+ player-week-column values through the Kotlin query builder and verifies they match ETL stored values

To run contract tests locally, set `GRIDIRON_STATS_DB` before running tests (CI builds a fresh database first).

## Performance Notes

- Full-season 12-column FLEX grid with percentiles: ~85 ms on CI JVM (not yet measured on-device)
- `stats.db` is pre-indexed, `ANALYZE`d, and `VACUUM`ed, so the query planner has statistics on first launch
- Covering index on `(metric_id, season, week, value)` eliminates secondary lookups for common aggregation queries
- All queries use parameterized binds (`?`), no string interpolation

## Known Gaps & Next Steps

- Only 39 of ~450 catalogued metrics are implemented (play-by-play and snap count; Next Gen Stats, FTN charting, injuries/schedules are wired but not yet transformed)
- Pre-aggregated season rollups are specified but not built (next performance target for the common full-season view)
- Hilt dependency injection and Navigation 3 architecture arrive with the second feature
- User database (`user.db`) for presets and rosters not yet implemented
- APK signing uses a committed keystore (`app/gridiron.keystore`, intentional for a never-published personal app)
- **Grid entry points**: tapping a Grid row opens the Player page (ESPN status, injury notes, tagged news, and a "This week" projection card that opens the waterfall); the ☰ menu opens Projections (the upcoming week or rest of season by position, scored with the active profile), News, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats. `AccuracyKey` stays unreachable until the accuracy sub-project.
- **ESPN's endpoints are unofficial and keyless**; a shape change shows as "Not updated: ESPN changed its … format" with the last data kept. Parsing lives in `core/data/.../live/Espn.kt`, tested against recorded responses in `core/data/src/test/resources/espn/`.
- **Refresh runs in an application-scope coroutine, not WorkManager**: if Android kills the process mid-build, the old database stays and the next refresh starts over.
- **Projection model sub-projects 2–4 are not built yet**: the accuracy page (backtest), Odds API props and K/DST. See `docs/superpowers/specs/2026-09-26-projection-model-design.md`.
- **Not modeled:** weather (wind is only known after kickoff) and shifting an injured player's share to teammates; an Out/IR player just shows Out.
- **K/DST fantasy scoring is out of scope** for `:core:projections`'s `score()` — `ScoringRule` structurally covers QB/RB/WR/TE only

## Codebase Notes

- **Kotlin style** — Official Kotlin style guide (enforced by linting)
- **Android SDK** — Platform 37, JDK 17+
- **Gradle caching & configuration cache** enabled (`gradle.properties`)
- **Build artifact** — APK auto-published to [releases/download/app/gridiron.apk](https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk) on every push and every Tuesday morning
- **Data attribution** — nflverse (CC BY 4.0), Fantasy Football Calculator ADP, US National Weather Service
