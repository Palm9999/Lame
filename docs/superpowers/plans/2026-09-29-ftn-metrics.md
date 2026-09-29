# FTN Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Per CLAUDE.md this plan lists tasks, interfaces and test names, not full code; the spec carries the design.

**Goal:** Ten FTN charting metrics in the Grid, built by the phone's Kotlin ingest and matched by the Python ETL.

**Architecture:** FTN's per-season play file joins play-by-play on (game id, play id); flags are attributed to the target receiver and the passer inside the same play loop as the other aggregators, stored as counts beside FTN's own denominators plus each weekly rate. Per-season reuse works unchanged.

**Tech Stack:** Kotlin (`:core:ingest`, `:core:statquery`, `:core:data`), Python/polars (`etl/`), JUnit, pytest.

**Spec:** `docs/superpowers/specs/2026-09-29-ftn-metrics-design.md`

## Global Constraints

- Branch `claude/dreamy-euler-phbdq1`; one PR; the HANDOFF update rides in it and after each task (so a `/clear` can resume).
- Rates recomputed over the range from stored counts, never averaged; nothing FTN is sparse (a 0-drop week stores 0).
- Only plays with an FTN row count, for numerators and denominators alike; kneels, spikes and two-point tries are excluded.
- The build never fails on FTN: a missing, unreadable or renamed-column file leaves FTN out of that season with a warning; pre-2022 seasons 404 silently.
- FTN files are uncompressed CSV: `<NFLVERSE>/ftn_charting/ftn_charting_<season>.csv`.
- `INGEST_VERSION` 6 to 7; registry 123 to 143 metrics (10 visible + 10 internal); forecast and accuracy gate untouched.
- Credit: "FTN Data via nflverse" (CC BY-SA 4.0).
- Run pytest from `etl/`; run Kotlin tests with `env -u GRIDIRON_STATS_DB` until Task 5 rebuilds the databases (an empty value resolves to the repo root and fails).
- Never `rm` with a glob in a shell that may have reset to the repo directory.

## Review Focus

1. FTN covering only part of a season (it lags play-by-play mid-season): rates use FTN's own denominators, so uncovered weeks don't drag them to 0 (Task 2, Task 3).
2. A play with an FTN row but no receiver or passer (8 drops in 2025 have no receiver): nothing is credited and nothing crashes (Task 2).
3. Blank or malformed flag cells and a play with a null `play_id` in play-by-play: not matched, not fatal (Task 2).
4. A pre-2022 season and a 2022+ season with no file: silent vs one warning, and reuse still works when the file is 404 both times (Task 3).
5. A file with a renamed column: FTN left out of that season with a warning, the build succeeds (Task 3).

## File Structure

| File | Responsibility |
|---|---|
| `core/ingest/.../Metrics.kt` (modify) | 20 registry entries |
| `core/ingest/.../Ftn.kt` (create) | `readFtn`, `FtnFlags`, `FtnAggregator` |
| `core/ingest/.../pbp/Play.kt` (modify) | `play_id` column and `Play.playId` |
| `core/ingest/.../Sources.kt` (modify) | `Input.FTN` |
| `core/ingest/.../IngestPipeline.kt` (modify) | fetch, aggregate, warnings, coverage |
| `core/ingest/.../db/Schema.kt` (modify) | `INGEST_VERSION` 7, `SOURCE_NOTE` credit |
| `core/statquery/.../Component.kt`, `StatColumn.kt` (modify) | components and ten columns |
| `core/data/.../StatPack.kt`, `StatFormat.kt` (modify) | `FTN_PASSING`, `FTN_RECEIVING`, PERCENT set |
| `etl/gridiron_etl/ftn.py` (create), `metrics.py`, `sources.py`, `transform.py`, `build.py` (modify) | Python twin |

## Task 1: Registry, components and Grid wiring

**Files:** Modify `Metrics.kt`, `etl/gridiron_etl/metrics.py` (Group gains `"ftn"`), `Component.kt`, `StatColumn.kt`, `StatFormat.kt`, `StatPack.kt`; tests `MetricsTest.kt`, `StatsDbWriterTest.kt`, `etl/tests/test_registry.py`, `FtnColumnsTest` (`:core:statquery`), `StatFormatTest.kt`.

**Interfaces:**
- Internal components (tier B, not sparse, group "ftn", positions per the spec): `ftn_targets`, `ftn_catchable`, `ftn_contested` (RB, WR, TE); `ftn_dropbacks`, `ftn_attempts`, `ftn_pa_db`, `ftn_blitz_db`, `ftn_oop_db`, `ftn_throwaway`, `ftn_int_worthy` (QB).
- Visible metrics (tier B, not sparse): `ftn_catchable_rate` CATCH%, `ftn_drop_rate` DRP% (lower better), `ftn_contested_rate` CTD%, `ftn_drops` DRP (lower better), `ftn_created_rec` CRT (RB, WR, TE); `ftn_play_action_rate` PA%, `ftn_blitz_rate` BLZ%, `ftn_out_of_pocket_rate` OOP%, `ftn_throwaway_rate` TA% (lower better), `ftn_int_worthy_rate` IW% (lower better) (QB). Every definition says FTN charting starts in 2022.
- `StatColumn.FTN_CATCHABLE_RATE ... FTN_INT_WORTHY_RATE` with `Ratio(numerator, denominator)` per the spec; `FTN_DROPS` and `FTN_CREATED_REC` are `Total`. `sample`: QB rates and `DROPBACKS`, INT-worthy on `ATTEMPTS`, receiver metrics on `TARGETS`.
- `StatPack.FTN_PASSING` ("FTN Passing": PA%, BLZ%, OOP%, TA%, IW%, then `DROPBACKS`; leads with PA%, population `DROPBACKS`), `StatPack.FTN_RECEIVING` ("FTN Receiving": CATCH%, DRP%, CTD%, DRP, CRT, then `TARGETS`; leads with CATCH%, population `TARGETS`). Default sort is the lead column.
- `StatFormat`: the eight rate columns (three receiver, five QB) join the PERCENT set (stored as fractions).

- [ ] Write failing tests: `MetricsTest` size 143, ten visible tier B not sparse with the spec's positions and `higherIsBetter`, ten internal and not sparse, every FTN definition mentions 2022; `test_registry.py` same ids in both languages; `FtnColumnsTest` "a two-week range is the count-weighted rate, not the mean of the weekly rates", "a player with no FTN rows has an empty cell, not zero", "the sample columns match the spec"; `StatFormatTest` "FTN rates render as percentages".
- [ ] Run them: expect FAIL. Implement. Run `env -u GRIDIRON_STATS_DB ./gradlew :core:ingest:test :core:statquery:test :core:data:test -q` and `cd etl && python -m pytest tests -q`: PASS.
- [ ] Update HANDOFF (task list for FTN) and commit: `feat: register ten FTN metrics and Grid columns`.

## Task 2: Kotlin FTN reader and aggregator

**Files:** Create `Ftn.kt`, `FtnTest.kt`; modify `pbp/Play.kt`, `Sources.kt`, `SourcesTest.kt`, `Fixtures.kt`.

**Interfaces:**
- Produces `Input.FTN` (`perSeason = true`, label "FTN charting"), URL `<NFLVERSE>/ftn_charting/ftn_charting_<season>.csv`, `metaKey` `source:ftn_charting_<season>.csv`.
- Produces `Play.playId: Int?` (from `play_id`) and `"play_id"` in `PBP_COLUMNS`.
- Produces `internal data class FtnKey(val gameId: String, val playId: Int)`; `internal class FtnFlags(catchable, contested, drop, created, playAction, outOfPocket, throwAway, intWorthy: Boolean, blitzers: Int)`; `internal fun readFtn(input: InputStream, source: String): Map<FtnKey, FtnFlags>` (columns `nflverse_game_id`, `nflverse_play_id` and the eleven flags, all required; `TRUE` is true, anything else false).
- Produces `internal class FtnAggregator(index: Map<FtnKey, FtnFlags>)` with `fun add(p: Play)` (same eligibility as `PlayerWeekAggregator.add`: REG or POST, scrimmage play types, `twoPointAttempt == 0`, efficiency plays only) and `fun rows(): List<PlayerWeek>` (team = `posteam`; a receiver-week with `ftn_targets > 0` carries the 5 receiver components and the 3 rates; a QB-week with `ftn_dropbacks > 0` carries the QB components and the 4 dropback rates, plus `ftn_int_worthy_rate` when `ftn_attempts > 0`; zeros are kept).

- [ ] Write failing tests: `FtnTest` "reads TRUE and FALSE flags and requires every column" (`MissingColumnsException` on a renamed column), "a blank flag reads as false", "the target receiver is credited catchable, contested, drop and created", "the passer is credited with the dropback weight (attempt + sack + scramble)", "int-worthy counts only on attempts", "a sack counts as a dropback but not an attempt", "kneels, spikes and two-point tries count nowhere", "a play with no FTN row counts nowhere, denominators included", "a play with no receiver or passer credits nobody and does not crash", "a null play id matches nothing", "a zero-drop week stores zeros and the weekly rates", "two weeks give separate rows"; `SourcesTest` for the URL and meta key.
- [ ] Run: FAIL. Implement. Run `env -u GRIDIRON_STATS_DB ./gradlew :core:ingest:test -q`: PASS.
- [ ] Update HANDOFF and commit: `feat: read FTN charting into player-week components`.

## Task 3: Pipeline integration

**Files:** Modify `IngestPipeline.kt` (`SEASON_INPUTS` gains `Input.FTN`; `crunch` builds the index and runs `FtnAggregator` in the play loop; facts via `toFacts`), `db/Schema.kt` (`INGEST_VERSION = 7`, `SOURCE_NOTE` gains "FTN Data via nflverse (CC BY-SA 4.0)"), a coverage check in `validate/FtnChecks.kt` (`internal fun ftnCoverageWarning(season: Int, attempts: Int, covered: Int): String?`, warns under 90%); tests `IngestPipelineTest.kt`, `FtnValidationTest`.

**Interfaces:** Consumes Task 2's `readFtn` and `FtnAggregator`. `crunch` reads FTN through the existing `readOptional` (a truncated or renamed-column file leaves FTN out with a warning). A season below 2022 with no file adds no warning; 2022+ adds `"<season>: no FTN charting yet"`.

- [ ] Write failing tests with `FakeFetcher` (add a `serveFtn(season, version)` helper; the play fixtures need `play_id` and matching game ids): "a first build stores FTN components and weekly rates and records the file's version", "FTN unchanged and season unchanged is copied with its FTN facts", "FTN changed rebuilds only that season", "a 2022+ season without an FTN file builds with one warning", "a 2019 season without an FTN file builds silently and reuses on the next build" (Review Focus 4), "a renamed column leaves FTN out with a warning and the build succeeds" (Review Focus 5), "a season whose FTN file covers part of its pass attempts keeps the rates of the covered weeks" (Review Focus 1), "the previous database's ingest version 6 is ignored", `FtnValidationTest` "coverage under 90% warns once".
- [ ] Run: FAIL. Implement. Run `env -u GRIDIRON_STATS_DB ./gradlew :core:ingest:test :core:data:test :core:statquery:test -q`: PASS.
- [ ] Update HANDOFF and commit: `feat: build FTN facts in the pipeline`.

## Task 4: Python twin

**Files:** Create `etl/gridiron_etl/ftn.py`, `etl/tests/test_ftn.py`; modify `sources.py` (`"ftn": Source("ftn", "ftn_charting", "ftn_charting_{season}.csv")`), `transform.py` (`PBP_COLUMNS` gains `"play_id"`), `build.py` (per season, inside the loop after `weekly`: fetch FTN, `ftn.components(lf, path)`, `to_long`, append to `frames`; wrapped in try/except with a warning; a 404 for seasons below 2022 is silent).

**Interfaces:** `ftn.components(pbp: pl.LazyFrame, ftn_csv: pl.DataFrame) -> pl.DataFrame` (columns `player_id, season, week, team` plus the 20 metric ids; same eligibility and attribution as Task 2; zeros kept), `ftn.load(path) -> pl.DataFrame` (the ids and eleven flags, cast to booleans).

- [ ] Write failing `test_ftn.py` mirroring `FtnTest` (same fixtures, same expected numbers).
- [ ] Run: FAIL. Implement. Run `cd etl && python -m pytest tests -q`: PASS.
- [ ] Update HANDOFF and commit: `feat(etl): FTN components`.

## Task 5: Parity, real database, rebuild

**Files:** `etl/tests/test_parity.py` if it needs the new ids; new `FtnRealDatabaseTest` in `:core:statquery` (skipped without the Python download cache).

- [ ] Build 2025 in Python (`etl/build/parity/py.db`) and Kotlin (`etl/build/parity/kt.db`); `python etl/tools/parity.py ...`: expect `parity: OK` (refresh a stale cached `players.csv` first if `player` differs, as NGS showed). Also build 2022 in both for a season with the earliest FTN data.
- [ ] Write `FtnRealDatabaseTest`: for a named QB and receiver, a Grid column over weeks 1-8 equals the ratio computed straight from the FTN CSV joined to play-by-play (the download cache holds both).
- [ ] Rebuild `etl/build/stats.db` (2024 2025 2026) and `accuracy.db` (2024 2025) with the Kotlin builder; run `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test --continue` (only the known timing tests may fail; rerun a lone timing failure by itself) and the accuracy gate from CLAUDE.md.
- [ ] Update HANDOFF and commit: `test: FTN parity and real-database checks`.

## Task 6: Docs

**Files:** `README.md` (Attribution), `CLAUDE.md` (Known Gaps line, Data attribution), `docs/ARCHITECTURE.md` (source list), `docs/superpowers/HANDOFF.md`.

- [ ] CLAUDE.md: FTN implemented (Grid packs FTN Passing and FTN Receiving; Grid only); attribution "FTN Data via nflverse (CC BY-SA 4.0)". README Attribution the same. ARCHITECTURE: FTN as a per-season input joined on play id. HANDOFF: FTN built, next is presets or rollups; record that ShareAlike matters only if the app or its stats.db is ever distributed.
- [ ] Run the full commands once more; commit `docs: FTN metrics in the docs`. The final review, the fix pass, deleting the spec and plan and opening the PR follow.

## Self-Review

- **Spec coverage:** source facts and attribution (Task 2), storage and registry (Task 1), pipeline, missing-file rules, coverage warning, version, credit (Task 3), Python twin and parity (Tasks 4, 5), real database (Task 5), docs (Task 6).
- **Placeholders:** none.
- **Names:** component and metric ids match the spec's table in Tasks 1-5; `FtnKey`, `FtnFlags`, `readFtn`, `FtnAggregator` are defined in Task 2 and consumed in Task 3.
