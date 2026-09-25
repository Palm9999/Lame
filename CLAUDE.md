# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Gridiron** is a personal-use Android app for NFL fantasy football analytics. It computes ~450+ stats from open nflverse data and displays them in a real mobile stat table with filtering, sorting, and comparison tools. The app ships as a prebuilt SQLite database within the APK, so it works offline.

## Common Commands

### Data Pipeline (Python/ETL)
```bash
cd etl
pip install -r requirements.txt

# Build the stats database (required before running Android code)
python -m gridiron_etl.build --seasons 2024 2025 2026 --out build/stats.db

# Run ETL tests
python -m pytest tests/ -q
```

### Android Tests & Builds (requires JDK 17+, Android SDK with platform 37)
```bash
# Set the stats database path (required for all tests and builds)
export GRIDIRON_STATS_DB=etl/build/stats.db

# Run all tests (executes against the real ETL database via the JDBC fixture)
./gradlew test

# Run tests for a single module
./gradlew :core:statquery:test

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
- `:core:database` — Read-only SQLite access via the bundled driver; wraps the JDBC executor
- `:core:testing` — Test fixtures: JDBC executor over the real database. Proves results match what the phone's SQLite driver will return
- `:core:projections` — Pure `score()` (the in-memory twin of `StatQueryBuilder`'s SQL scoring), factor attribution, and single-player Monte Carlo (floor/ceiling) for the Projections feature

**Android Modules**:
- `:app` — App entry point; ships `stats.db` inside the APK and copies it on first launch
- `:feature:players` — The Grid screen (main UI) and its ViewModel
- `:core:table` — Frozen-column stat table with shared horizontal scroll state
- `:core:designsystem` — Theme, dark mode, colorblind-safe heat scale
- `:core:data` — Stat packs, qualifying bars, formatting, repository pattern
- `:feature:projections` — The Projections waterfall card and the accuracy ("trust page") screen; wired into the nav graph as `ProjectionsKey`/`AccuracyKey` but not yet reachable from any UI (see Known Gaps)

### Data Flow

1. **ETL Pipeline** (`etl/gridiron_etl/`) downloads nflverse data, applies validation (range checks, coherence, referential integrity), and builds a compact pre-indexed SQLite database (`stats.db`)
2. **Database Shipping** — `stats.db` is committed to the repo (via Git LFS or as prebuilt artifact) and bundled into the APK
3. **App Runtime** — On first launch, the app copies `stats.db` to the device; queries run through the JDBC executor in tests, or through Android's SQLite driver on the phone
4. **Query Layer** — `:core:statquery` generates parameterized SQL for any stat grid query (columns, filters, week ranges, percentiles)

### Key Design Decisions

**Stat Components, Not Fantasy Points** — The database ships raw stats (receptions, yards, touchdowns). League scoring is applied on-device, so any league format works offline.

**Rates Recomputed, Never Averaged** — Target share over weeks 1–8 is `Σ targets / Σ team targets`, not the mean of eight weekly values. Internal metrics (team totals, component sums) are stored to enable recomputation over any range.

**Frozen-Column Table** — The Grid has one shared horizontal scroll state across all columns, making sticky-column synchronization straightforward.

**Read-Only Database at Build Time** — `stats.db` is immutable at runtime; user state (presets, rosters) will live in a separate `user.db` when implemented.

**No Hilt or Navigation Yet** — Current single-screen setup. Hilt and Navigation 3 will arrive with the second feature.

### Database Schema (Version 4)

Long/narrow design: adding a metric is an `INSERT`, not a migration.

| Table | Purpose |
|---|---|
| `player_week_stat` | Facts: `(player_id, season, week, team, metric_id, value)`, indexed as covering index on `(metric_id, season, week, value)` |
| `metric` | Metric registry: name, definition, formula, tier, predictive use, stability, internal flag, plus `dist_family` (distribution for on-device Monte Carlo/percentile reconstruction) and `zero_inflated` |
| `player` | Players with at least one stat in the built seasons |
| `schema_meta` | Schema version, seasons, attribution |
| `player_week_projection` | Per (player, week, metric, stage) projected mean/variance — `stage` is `baseline` (post volume-cascade) or `final` (fully adjusted) |
| `player_week_projection_factor` | Per (player, week, factor) log-space attribution multiplier for one projection adjustment stage |
| `player_ros_projection` | Rest-of-season aggregate: summed weekly mean/variance per (player, metric), no per-week detail |
| `projection_snapshot` | Projected mean/variance frozen at snapshot time, never overwritten — joined against `player_week_stat` once actuals land to compute accuracy |
| `accuracy_summary` | Precomputed MAE/RMSE/bias/R² per (position, season, metric, baseline), refreshed each ETL run |

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
- **Projections feature is wired but not reachable**: `:feature:projections`'s `ProjectionsRoute`/`AccuracyRoute` are valid, working nav destinations (`ProjectionsKey`/`AccuracyKey`), but no button or menu item anywhere navigates to them yet — reaching them today needs a manual nav-graph edit. Adding a real entry point should land together with the two gaps below, since all three are "is the feature actually usable" work.
- **`ProjectionsRoute` hardcodes `ScoringPresets.PPR` and `position = null`** instead of the viewer's real league scoring profile and the player's actual position — `receptionWeight(null)` skips TE-premium scoring rules, and non-PPR leagues see PPR numbers. Needs `ScoringRepository` threaded through the route (the pattern `CompareRoute` already uses) plus a player-position lookup that doesn't exist yet at that call site.
- **Every projection component is simulated as `DistributionFamily.GAMMA`**, not each metric's real `dist_family`/`zero_inflated` from the `metric` registry (the column exists from the ETL plan's schema v4, but isn't yet threaded through `:core:data`'s `Catalog`/`MetricInfo`)
- **K/DST fantasy scoring is out of scope** for `:core:projections`'s `score()` — `ScoringRule` structurally covers QB/RB/WR/TE only
- **No contract test** runs real ETL-shaped projection output end-to-end through `ProjectionsRepository`/`ProjectionsViewModel` the way `:core:statquery`'s three-tier strategy does for the Grid — today's Android-side projection tests use hand-inserted fixture rows only, and would not have caught the final-stage-rows-mostly-missing issue the final whole-branch review found (since fixed: `ProjectionsViewModel` now merges baseline values forward for any component with no final-stage adjustment)

## Codebase Notes

- **Kotlin style** — Official Kotlin style guide (enforced by linting)
- **Android SDK** — Platform 37, JDK 17+
- **Gradle caching & configuration cache** enabled (`gradle.properties`)
- **Build artifact** — APK auto-published to [releases/download/app/gridiron.apk](https://github.com/Palm9999/Lame/releases/download/app/gridiron.apk) on every push and every Tuesday morning
- **Data attribution** — nflverse (CC BY 4.0), Fantasy Football Calculator ADP, US National Weather Service
