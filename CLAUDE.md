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

### Module Structure

**JVM Modules** (plain Kotlin, platform-independent):
- `:core:model` — Shared types used across the app
- `:core:statquery` — Query builder that turns `StatQuerySpec` into SQL. Guarantees rate recomputation, SQL injection safety, determinism, and stable percentiles. Three test tiers: in-memory SQLite, safety/determinism checks, and contract tests against the real ETL database
- `:core:database` — Read-only SQLite access via the bundled driver. `ReopenableQueryExecutor` closes and reopens the connection when a refresh swaps in a new `stats.db`, and bumps a version flow that every stats screen (Grid, Compare, Player page, Projections, waterfall, accuracy, Injury report, Team defense) reloads on
- `:core:datastore` — User prefs (profiles, rosters, settings) as one JSON document. `formatVersion` 2 migrated older profiles once to the kicking and D/ST defaults and ESPN's points-allowed tiers; `formatVersion` 3 gave every older profile ESPN's yards-allowed tiers once
- `:core:testing` — Test fixtures: JDBC executor over the real database. Proves results match what the phone's SQLite driver will return
- `:core:projections` — Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring; a D/ST's points and yards allowed score the profile's tiers) and `projectedScore` (projections: tiers in expectation, per game), factor attribution, and single-player Monte Carlo (floor/ceiling, widened per position by `RANGE_WIDENING` so the range holds about 80% of games; a D/ST's points and yards allowed are drawn per game, jointly at `DST_POINTS_YARDS_CORRELATION`, and scored through the tiers) for the Projections feature; `backtest()`, which scores the stored past-week projections against real games beside the season-to-date and last-4 averages (MAE, bias, R², floor-to-ceiling calibration)
- `:core:ingest` — Builds `stats.db` from nflverse and ffopportunity: a streaming CSV reader, Kotlin ports of the ETL's transforms and validation (kicking facts from play-by-play, with a zero week for a kicker who only kicked off; each team's defense as a `DST_<TEAM>` pseudo-player from `team_week_defense`, with points and yards allowed stored as numbers for the profile's tiers), and a pipeline that re-downloads only files whose ETag changed and copies unchanged seasons from the previous build. It also downloads nflverse's schedule (`games.csv`) into the `game` table and runs `:core:forecast` after validation; a forecast failure leaves the stats and records why. Runs on the phone and on the JVM (`./gradlew :core:ingest:buildStatsDb -Pseasons="2025" -Pout=etl/build/stats.db`); CI's parity job holds it to the Python ETL's values
- `:core:forecast` — The projection model. Reads a freshly built stats.db and writes weekly, rest-of-season and waterfall-factor projections for QB/RB/WR/TE, K and D/ST, walk-forward (each week only from the games before it). Seven layers: team volume, shrunk share (toward the player's last season; starting QB only; each team's shares sum to one), shrunk efficiency, expected TDs, opponent ratings (ridge), game script from nflverse's lines, distributions; then, for the upcoming week only, an inverse-variance blend with betting props when the user has an Odds API key (a `market` factor in the waterfall). K and D/ST have their own models (`Kicker.kt`, `Defense.kt`, run by `UnitProjector`). Kickers' field goal and extra point tries come from implied team points, with their distance mix and accuracy shrunk toward the league's. D/STs get their own sacks and takeaways times the opponent's, and points allowed from the opponent's implied points with a measured spread (stored as the variance, with `g` = 1 a game). Yards allowed are their own projected stat: the defense's rate shrunk toward the league's (`DST_YA_K`), the opponent's yards against defenses, a damped game script (`DST_YA_SCRIPT_ELASTICITY`) and a spread of `DST_YA_CV` × the mean. Every constant is in `ForecastConstants.kt`; bump `FORECAST_VERSION` when one changes

**Android Modules**:
- `:app` — App entry point. `RefreshCoordinator` builds `stats.db` on the phone with `:core:ingest` and swaps it in without a restart; News, Player page (status, injury notes, news, a 'This week' card, and Season stats: chips, a season line with position percentiles, a game log), live Injury report, Settings (seasons, Odds API key) and Load stats screens
- `:feature:players` — The Grid screen (main UI) and its ViewModel. The K and D/ST chips bring their own packs (Kicking, Defense); All and the offense's chips leave kickers and D/STs out (`StatsRepository`)
- `:feature:scoring` — Scoring profiles: the list and the editor (every rule, plus the points-allowed and yards-allowed tier editors)
- `:feature:compare` — The Compare screen: bars, head-to-head table, radar, xFP-vs-actual scatter
- `:core:table` — Frozen-column stat table with shared horizontal scroll state
- `:core:charts` — Compose Canvas charts (bars, radar, scatter); no charting library
- `:core:ui` — Shared screen chrome (profile chip, metric and weeks sheets) so no feature module depends on another
- `:core:designsystem` — Theme, dark mode, colorblind-safe heat scale
- `:core:data` — Stat packs, qualifying bars, formatting, repositories; `SettingsRepository` (which seasons to build); `PlayerDirectory` (ESPN id → player via `player_xref`); `PlayerStatsRepository` (the Player page's season line and game log, built with the Grid's query builder); and the `live` package: the ESPN news/injuries parser and client, the writable `live.db` store, `LiveRepository`, and `PropsRepository` (The Odds API: the upcoming week's player props, fetched within the credit budget)
- `:feature:projections` — The Projections list (☰ → Projections: the upcoming week or rest of season by position, K and D/ST included, scored with the active profile), the Player page's "This week" card, the waterfall screen (`ProjectionsKey`), and the accuracy page (☰ → Projection accuracy, `AccuracyKey`): each position's backtest for a season under the active profile, computed when the page opens

### Data Flow

1. **Refresh on the phone** — ☰ → Refresh stats (or Load stats on a fresh install) runs `:core:ingest`'s `IngestPipeline` for the seasons chosen in Settings. It downloads nflverse and ffopportunity files with conditional GETs, copies unchanged seasons from the current database, crunches the rest, validates, and writes `stats.db.new`
2. **Forecast** — before the build, if Settings has an Odds API key, the upcoming week's player props are fetched into `live.db` (games not yet kicked off and not fetched in 24 hours, never overdrawing the credits). The build then projects every regular-season week of the chosen seasons into the projection tables (the upcoming week with both stages and factors, blended with props when there are any; past weeks' final stage for the backtest; rest of season summed). The refresh toast says if projections are unavailable, how many projections props moved, and why props weren't updated
3. **Swap** — `RefreshCoordinator` renames `stats.db.new` over `stats.db` inside `ReopenableQueryExecutor.swap`; screens reload on the version bump. A failed build leaves `stats.db` untouched
4. **Live data** — the same refresh (and the News, Player and Injury report screens, when data is over 15 minutes old) fetches ESPN's news and injuries into `live.db`, linked to players through `player_xref`, pruned at 30 days
5. **Query Layer** — `:core:statquery` generates parameterized SQL for any stat grid query (columns, filters, week ranges, percentiles). Tests run it through the JDBC executor against a Kotlin-built database (`GRIDIRON_STATS_DB`); the phone runs it through the bundled SQLite driver
6. **Python ETL** (`etl/`) — kept only as CI's parity reference for the Kotlin port; nothing it builds reaches the app

### Key Design Decisions

**Stat Components, Not Fantasy Points** — The database ships raw stats (receptions, yards, touchdowns). League scoring is applied on-device, so any league format works offline.

**Rates Recomputed, Never Averaged** — Target share over weeks 1–8 is `Σ targets / Σ team targets`, not the mean of eight weekly values. Internal metrics (team totals, component sums) are stored to enable recomputation over any range.

**Frozen-Column Table** — The Grid has one shared horizontal scroll state across all columns, making sticky-column synchronization straightforward.

**Stats Database Replaced Whole, Never Edited** — The app opens `stats.db` read-only; a refresh builds a complete new file beside it and swaps it in atomically. Live ESPN data lives in a separate `live.db` that is updated in place and can be deleted at any time. User state (profiles, rosters, settings) lives in the `:core:datastore` prefs JSON. A `user.db` is planned for saved Grid presets but not built.

**Navigation 3, No Hilt** — Screens navigate through Navigation 3 (`app/.../GridironNavHost.kt`, `NavKeys.kt`). Repositories are wired by hand in `GridironApplication`; Hilt is not used.

### Database Schema (Version 8)

Long/narrow design: adding a metric is an `INSERT`, not a migration.

| Table | Purpose |
|---|---|
| `player_week_stat` | Facts: `(player_id, season, week, team, metric_id, value)`, indexed as covering index on `(metric_id, season, week, value)` |
| `metric` | Metric registry: name, definition, formula, tier, predictive use, stability, internal flag, plus `dist_family` (distribution for on-device Monte Carlo/percentile reconstruction) and `zero_inflated` |
| `player` | Players with at least one stat in the built seasons, plus a `DST_<TEAM>` pseudo-player per team |
| `player_xref` | ESPN athlete id → `player_id` for every player nflverse lists, stats or not; links ESPN news and injuries |
| `schema_meta` | Schema version, seasons, attribution |
| `game` | nflverse schedule for the built seasons: opponents, results, spread and total, starting QBs, head coaches; the forecast's matchups and game script |
| `player_week_projection` | Per (player, week, metric, stage) projected mean/variance, written by `:core:forecast` — `stage` is `baseline` (post volume-cascade) or `final` (fully adjusted) |
| `player_week_projection_factor` | Per (player, week, factor) log-space attribution multiplier for one projection adjustment stage |
| `player_ros_projection` | Rest-of-season aggregate: summed weekly mean/variance per (player, metric), no per-week detail |
| `team_week_defense` | Per (team, season, week) points/yards allowed, sacks, INTs, fumbles recovered, defensive TDs, safeties, kickoff-return TDs |
| `injury_report` | Per (player, season, week) nflverse injury report status/injury/practice |

**`live.db`** (separate file, `PRAGMA user_version` 1): `news_item`, `news_player` (ESPN id, name, nullable `player_id`), `injury_status` (current snapshot), `injury_note` (appended when a comment changes), `prop_event` and `prop_line` (the upcoming week's player props by game, book, market, player and line; pruned 12 hours after kickoff), `live_meta` (fetch times, Odds API credits left, the last props error). Rows older than 30 days are pruned; an unreadable file is recreated.

**No year in table/column names** — New seasons are data rows, not schema changes.

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

- Only the play-by-play, snap count and ffopportunity metrics of the ~450 catalogued are implemented (registry: `core/ingest/.../Metrics.kt`); Next Gen Stats and FTN charting are wired but not yet transformed
- Pre-aggregated season rollups are specified but not built (next performance target for the common full-season view)
- Saved Grid presets not yet implemented (planned home: `user.db`). Rosters are stored in the `:core:datastore` prefs JSON (`UserPrefs.rosters`), not a `user.db`: ☰ → Rosters manages them, the Player page toggles membership, and the Grid's roster chip narrows to one (`GridRequest.onlyPlayers`) and stars rostered players
- APK signing uses a committed keystore (`app/gridiron.keystore`, intentional for a never-published personal app)
- **Grid entry points**: tapping a Grid row opens the Player page (ESPN status, injury notes, tagged news, and a "This week" projection card that opens the waterfall, Season stats); the ☰ menu opens Projections (the upcoming week or rest of season by position, K and D/ST included, scored with the active profile), Projection accuracy, News, Injury report (ESPN's live list with nflverse practice for the current season; the official list for past seasons), Team defense, Settings and Refresh stats.
- **ESPN's endpoints are unofficial and keyless**; a shape change shows as "Not updated: ESPN changed its … format" with the last data kept. Parsing lives in `core/data/.../live/Espn.kt`, tested against recorded responses in `core/data/src/test/resources/espn/`.
- **Refresh runs in an application-scope coroutine, not WorkManager**: if Android kills the process mid-build, the old database stays and the next refresh starts over.
- **Props can't be backtested**, because there are no historical props. The blend's weight (`MARKET_VARIANCE_RATIO`) and the one-sided anytime-TD margin (`ONE_SIDED_OVERROUND`) are judgments, not fits, and the accuracy page and CI gate measure the model alone.
- **The accuracy page recomputes on every open** and after a refresh (off the main thread; its phone time isn't measured yet); nothing is cached between visits.
- **Not modeled:** weather (out of scope by the user's call) and shifting an injured player's share to teammates; an Out/IR player just shows Out, and a player returning from injury isn't projected until he plays again.
- **K and D/ST constants are judgments** (`ForecastConstants`), tuned only as far as the gate needs.
- **Blocked kicks aren't scored** for a D/ST. Yards allowed are (net yards, in editable tiers, ESPN's defaults unverified against ESPN's own table). `DST_YA_K`, `DST_YA_SCRIPT_ELASTICITY` and `DST_YA_CV` are judgments, and there is no posted yards line to backtest against.

## Codebase Notes

- **Kotlin style** — Official Kotlin style guide (enforced by linting)
- **Android SDK** — Platform 37, JDK 17+
- **Gradle caching & configuration cache** enabled (`gradle.properties`)
- **Build artifact** — APK auto-published to [releases/download/app/gridiron.apk](https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk) on every push and every Tuesday morning
- **Data attribution** — nflverse (CC BY 4.0), ffopportunity, ESPN (news and injuries), The Odds API (player props, with the user's own key), Fantasy Football Calculator ADP, US National Weather Service
