# Season Rollups Implementation Plan

> **For agentic workers:** use superpowers:executing-plans (native) or superpowers:subagent-driven-development. Steps are checkboxes.

**Goal:** Serve whole-season and last 3/5/8-week Grid views with no fantasy column from pre-aggregated component sums.

**Architecture:** Both builders fill `player_window_stat` from `player_week_stat` with one `INSERT ... SELECT ... GROUP BY` at the end of a build, so parity is structural. A small `window_def` table records each window's week bounds. `StatQueryBuilder` reads the rollup when the spec's week range equals a stored window and no fantasy column is planned.

**Tech Stack:** Kotlin (`:core:ingest`, `:core:statquery`, `:core:data`), Python (`etl/`), SQLite.

**Spec:** `docs/superpowers/specs/2026-09-30-season-rollups-design.md`

**Deviation from spec:** the spec's "window is current" check needs bounds the pure query builder can't see. `window_def (season, window, first_week, last_week)` holds them; the repository passes them to the spec as `rollups`.

## Global Constraints

- Schema 8 → 9 (`SCHEMA_VERSION`), `INGEST_VERSION` 7 → 8; Python `SCHEMA_VERSION` 6 → 7.
- Rates are never stored; only component sums and games.
- Rollup results must equal weekly results exactly.
- No year in table or column names. Every value in SQL is a bound `?`.
- Scoring stays on-device; fantasy columns never use the rollup.

## Review Focus

- Season with fewer than N played weeks (week 2 of the current season, L5): window still defined, bounds clipped to played weeks, equals weekly path.
- Bye week or missed game inside a window: a player has no row that week; sums and games must match the weekly path.
- Current season after a refresh adds a week: the `L` bounds move (rebuilt with the database); the old bounds never match a stale spec.
- Playoff weeks (19+) in facts: `S` must equal the Grid's whole-season range, not silently include or drop them.
- Old database without the table: Grid works via the weekly path.

---

### Task 1: Schema and build (Kotlin)

**Files:** Modify `core/ingest/.../db/Schema.kt`, `db/StatsDbWriter.kt`, `IngestPipeline.kt`; Test `core/ingest/src/test/.../db/StatsDbWriterTest.kt`.

**Interfaces:**
- Produces: tables `player_window_stat (player_id, season, window, metric_id, value)` and `window_def (season, window, first_week, last_week)`, index `idx_pws_window ON player_window_stat (metric_id, season, window, value)`; `StatsDbWriter.writeWindows()`; consts `WINDOW_SEASON = "S"`, `WINDOWS_LAST = listOf(3, 5, 8)`.

- [ ] Find the whole-season week range the Grid sends (grep `WeekRange(1,` in `core/data`, `feature/players`); `S` uses exactly that range.
- [ ] Failing tests: `windowsMatchWeeklySums`, `lastWindowClipsToPlayedWeeks`, `byeWeekIsSkipped`, `playoffWeeksFollowGridRange` (hand-made facts).
- [ ] Add DDL, index, `writeWindows()`: per season, `last_week` = max played week within the `S` range, `L N` = `last_week-N+1 .. last_week` clipped at 1; fill with `INSERT ... SELECT player_id, season, ?, metric_id, SUM(value) ... WHERE week BETWEEN ? AND ? GROUP BY`; include the games metric.
- [ ] Call it in `IngestPipeline` after facts are final (after `writePlayers` orphan cleanup); bump `SCHEMA_VERSION` 9 and `INGEST_VERSION` 8; update the doc comment.
- [ ] Run `./gradlew :core:ingest:test`; commit.

### Task 2: Python twin and parity

**Files:** Modify `etl/gridiron_etl/schema.py`, `build.py`; `etl/tools/parity.py`; Test `etl/tests/test_parity.py`, new `etl/tests/test_windows.py`.

**Interfaces:** Consumes Task 1's DDL and window rules. Produces identical rows.

- [ ] Failing test `test_windows_match_hand_sums` and `test_parity_compares_window_tables`.
- [ ] Same DDL and `INSERT ... SELECT`, `SCHEMA_VERSION = 7`; add both tables to `parity.py`'s table map (keys: `player_id, season, window, metric_id` / `season, window`).
- [ ] `python -m pytest etl/tests -q`; build both databases for 2025 and run `python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db`; commit.

### Task 3: Query builder path

**Files:** Modify `core/statquery/.../StatQuerySpec.kt`, `StatQueryBuilder.kt`; Test `StatQueryBuilderTest.kt`.

**Interfaces:**
- Produces: `data class RollupWindow(val window: String, val weeks: WeekRange)`; `StatQuerySpec.rollups: List<RollupWindow> = emptyList()`.

- [ ] Failing tests: `rollupUsedForWholeSeason`, `rollupUsedForLast5`, `fantasyColumnUsesWeeklyPath`, `customRangeUsesWeeklyPath`, `noRollupsUsesWeeklyPath`, `rollupSqlBindsWindow`.
- [ ] In `aggregateAndBase`, when `!plan.scored` and a `RollupWindow` has `weeks == spec.weeks`, emit `FROM player_window_stat s WHERE s.metric_id IN (...) AND s.season = ? AND s.window = ?` (sums of one row per metric, same `SUM(CASE ...)` shape so `base` is unchanged); else current SQL.
- [ ] Update the class KDoc (step 1). Run `./gradlew :core:statquery:test`; commit.

### Task 4: Repository wiring and equality contract

**Files:** Modify `core/data/.../StatsRepository.kt` (and `PlayerStatsRepository.kt`, `CompareRepository.kt` only if they should use it: leave as is); Test `RealDatabaseContractTest.kt`, `StatsRepositoryTest`.

- [ ] Failing tests: `rollupGridEqualsWeeklyGridForEveryWindow` (real database; S, L3, L5, L8; 12 columns incl. rates and percentiles), `oldDatabaseWithoutWindowTablesFallsBack`.
- [ ] `StatsRepository.grid` reads `window_def` once per open database (catch missing table → empty), sets `spec.rollups` for the requested season.
- [ ] Add a timing printout (rollup vs weekly, full-season 12 columns) to the real-database test, no threshold.
- [ ] `export GRIDIRON_STATS_DB=etl/build/stats.db`; rebuild it first (schema changed); `./gradlew test`; commit.

### Task 5: Docs and handoff

**Files:** Modify `docs/ARCHITECTURE.md` (schema table, version 9), `CLAUDE.md` (Known Gaps line, `INGEST_VERSION`), `etl/README.md`, `core/statquery/README.md`, `docs/superpowers/HANDOFF.md`; delete the spec and this plan.

- [ ] Update each file; HANDOFF: built, version numbers, phone check ("first refresh rebuilds every season; report Grid full-season time"), next task.
- [ ] Push; open PR; subscribe.
