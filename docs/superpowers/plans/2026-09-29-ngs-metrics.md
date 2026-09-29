# NGS Metrics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Per CLAUDE.md this plan lists tasks, interfaces and test names, not full code; the spec carries the design.

**Goal:** Ten Next Gen Stats metrics in the Grid, built by the phone's Kotlin ingest and matched by the Python ETL.

**Architecture:** Three all-seasons NGS files become sparse per-player-week components (weighted sums beside weights); the Grid recomputes each average as a `Ratio` over the range. NGS is a shared input like the player list; only the newest built season depends on it for reuse.

**Tech Stack:** Kotlin (`:core:ingest`, `:core:statquery`, `:core:data`), Python/polars (`etl/`), JUnit, pytest.

**Spec:** `docs/superpowers/specs/2026-09-29-ngs-metrics-design.md`

## Global Constraints

- Branch `claude/dreamy-euler-phbdq1`; one PR; HANDOFF update rides in it.
- Rates recomputed over the range from stored components, never averaged.
- NGS `week = 0` rows are skipped; NGS week 23 becomes 22 only when the season has no NGS week 22.
- All NGS components are sparse (absent = no NGS data, never zero); a null average or weight <= 0 stores nothing.
- The build never fails on NGS: a missing, corrupt or renamed-column file leaves that group out with a warning.
- No year in table or column names; `INGEST_VERSION` 5 to 6; forecast and accuracy gate untouched.
- Registry counts: 100 metrics become 122 (10 visible + 12 internal).

## Review Focus

Each line below has its test in the owning task.
1. A file with a renamed or dropped column (`MissingColumnsException`): group skipped, warning, build succeeds (Task 3).
2. NGS row for a player with no play-by-play that week, or absent from the player table: fact kept only if the player exists (Task 2).
3. Season with both NGS week 22 and 23: no remap (Task 2).
4. NGS 404 or unchanged mid-refresh while a season is reused: reused season keeps its previous NGS facts (Task 3).
5. Percent-like values are already 0-100 points: shown as points, not multiplied again (Task 1).

## File Structure

| File | Responsibility |
|---|---|
| `core/ingest/.../Metrics.kt` (modify) | 22 registry entries |
| `core/ingest/.../Ngs.kt` (create) | readers, week filter/remap, rows to `PlayerWeek` |
| `core/ingest/.../Sources.kt` (modify) | `Input.NGS_PASSING/RUSHING/RECEIVING` |
| `core/ingest/.../IngestPipeline.kt` (modify) | fetch, reuse rule, attach, warnings |
| `core/ingest/.../db/StatsDbWriter.kt` (modify) | `INGEST_VERSION` 6 |
| `core/statquery/.../Component.kt`, `StatColumn.kt` (modify) | components and ten columns |
| `core/data/.../StatPack.kt`, `StatFormat.kt` (modify) | "Next Gen" pack, decimals |
| `etl/gridiron_etl/ngs.py` (create), `metrics.py`, `build.py` (modify) | Python twin |

## Task 1: Registry, components and Grid wiring

**Files:** Modify `Metrics.kt`, `etl/gridiron_etl/metrics.py`, `Component.kt`, `StatColumn.kt`, `StatFormat.kt`, `StatPack.kt`; tests `MetricsTest.kt`, `StatsDbWriterTest.kt`, `etl/tests/test_registry.py`, a new `NgsColumnsTest` in `:core:statquery`.

**Interfaces:**
- Produces internal components: weights `ngs_attempts`, `ngs_carries`, `ngs_targets`, `ngs_receptions`; weighted sums `ngs_ttt_w`, `ngs_aggr_w`, `ngs_iay_w`, `ngs_eff_w`, `ngs_box_w`, `ngs_sep_w`, `ngs_cush_w`, `ngs_yacoe_w`.
- Produces visible metrics (tier B, sparse): `ngs_time_to_throw` TTT, `ngs_aggressiveness` AGG%, `ngs_intended_air_yards` IAY (QB); `ngs_ryoe` RYOE, `ngs_ryoe_per_att` RYOE/A, `ngs_rush_efficiency` EFF (`higherIsBetter=false`), `ngs_stacked_box_pct` 8+ BOX% (RB, QB); `ngs_separation` SEP, `ngs_cushion` CUSH, `ngs_yac_over_expected` YACOE (WR, TE, RB). Group "ngs". `ngs_ryoe` is stored directly (a Total); the other nine visible are `Ratio(<sum>, <weight>)`, RYOE/A is `Ratio(ngs_ryoe, ngs_carries)`.
- `StatColumn` entries named `NGS_TIME_TO_THROW` ... `NGS_YAC_OVER_EXPECTED` with the same metric ids; a "Next Gen" `StatPack` reachable from the QB, RB, WR and TE chips (never K or D/ST).

- [ ] Write the failing tests: `MetricsTest` size 122 and the ten are tier B, sparse, none internal; the internal 12 are internal; `test_registry.py` count 122 and the same ids in both languages; `NgsColumnsTest` "a two-week range is the weighted average, not the mean of the two weekly averages" (in-memory SQLite, hand-computed), "no NGS rows gives a null cell", "percent-like columns are shown as points, not fractions" (Review Focus 5: `StatFormat` leaves them out of `PERCENT`).
- [ ] Run them: expect FAIL.
- [ ] Add the registry entries (Kotlin and Python, identical definitions; write `definition` and `predicts` text; leave `stability` null), components, columns, decimals (TTT 2, AGG 1, IAY 1, RYOE 1, RYOE/A 2, EFF 2, BOX 1, SEP 2, CUSH 2, YACOE 2) and the pack.
- [ ] Run `./gradlew :core:ingest:test :core:statquery:test :core:data:test` and `pytest etl/tests -q`: PASS.
- [ ] Commit: `feat: register ten NGS metrics and Grid columns`.

## Task 2: Kotlin NGS reader and transform

**Files:** Create `Ngs.kt`, `NgsTest.kt`; modify `Sources.kt`, `SourcesTest.kt`, `Fixtures.kt` (an `ngsCsv` helper).

**Interfaces:**
- Produces `Input.NGS_PASSING`, `NGS_RECEIVING`, `NGS_RUSHING` (`perSeason = false`, labels "NGS passing" and so on), URLs `<NFLVERSE>/nextgen_stats/ngs_{passing,rushing,receiving}.csv.gz`.
- Produces three readers, `readNgsPassing(input: InputStream, source: String)`, `readNgsRushing(...)`, `readNgsReceiving(...)`, each returning `List<PlayerWeek>` for weeks 1+ only, with components per Task 1's table (`avg x weight`), and `internal fun remapPostseasonWeeks(rows: List<PlayerWeek>): List<PlayerWeek>` applying "23 to 22 only when the season has no 22".
- `internal fun mergeNgs(vararg groups: List<PlayerWeek>): List<PlayerWeek>` merges rows sharing `(season, week, playerId)` into one `PlayerWeek` (team from NGS).

- [ ] Write the failing tests in `NgsTest`: week 0 skipped; missing average stores nothing; weight 0 stores nothing; components equal `avg x weight` (hand-computed row); week 23 becomes 22; season with both 22 and 23 unchanged (Review Focus 3); a renamed column throws `MissingColumnsException`; a player in two files merges into one row; a player unknown to the player table is dropped by the existing FK step (Review Focus 2, tested through `toFacts` plus a writer with an unknown id).
- [ ] Run: FAIL. Implement. Run: PASS. `SourcesTest` for the three URLs and `metaKey`.
- [ ] Commit: `feat: read NGS files into player-week components`.

## Task 3: Pipeline integration

**Files:** Modify `IngestPipeline.kt`, `db/StatsDbWriter.kt` (`INGEST_VERSION = 6`), `validate/*` (NGS range checks); tests `IngestPipelineTest.kt`, new `NgsValidationTest`.

**Interfaces:**
- Consumes Task 2's readers and `mergeNgs`.
- Produces in `Run`: `fetchNgs()` (once per build, validators in `schema_meta` under `Sources.metaKey(input)`), a `ngsChanged` flag, and the reuse rule: a season is `unchanged` only if its inputs are unchanged and (`!ngsChanged` or it is not the newest requested season). `crunch` attaches NGS facts for its season via `toFacts`. `reuse` copies the season's NGS facts with it and carries the NGS meta keys.
- Validation: ranges from the spec (warning for a value out of range, hard failure only for impossible ones: negative weights, percent over 100 or below 0, time to throw <= 0); a coverage warning per season with no NGS rows.

- [ ] Write the failing tests with `FakeFetcher`: NGS unchanged and season unchanged (reused, NGS facts present); NGS changed, newest season rebuilt; NGS changed, older season reused, and its NGS facts kept; NGS 404 with a reused season keeps facts and warns (Review Focus 4); NGS 404 on a first build succeeds with a warning and no NGS facts; NGS file with a missing column skips that group, warns, the others load (Review Focus 1); `ingest_version` 6 makes a v5 previous database ignored; range-check cases (out of range warns, impossible fails).
- [ ] Run: FAIL. Implement. Run `./gradlew :core:ingest:test`: PASS.
- [ ] Commit: `feat: build NGS facts in the pipeline`.

## Task 4: Python twin

**Files:** Create `etl/gridiron_etl/ngs.py`, `etl/tests/test_ngs.py`; modify `build.py`, `sources.py` only if a helper is needed (the three downloads exist).

**Interfaces:** `ngs.components(passing, rushing, receiving) -> pl.DataFrame` (columns `player_id, season, week, team` plus the 22 component ids, same rules as Task 2), `ngs.remap_postseason_weeks(df)`. `build.py` fetches once (not per season), left-joins nothing: it appends `transform.to_long(ngs_frame, metric_ids, sparse_metric_ids())` to `frames`, wrapped in try/except with a warning, filtered to built seasons.

- [ ] Write failing `test_ngs.py` mirroring `NgsTest` (same fixtures and expected numbers so the two implementations are pinned to each other).
- [ ] Run: FAIL. Implement. Run `pytest etl/tests -q`: PASS.
- [ ] Commit: `feat(etl): NGS components`.

## Task 5: Parity, real database and rebuild

**Files:** `etl/tools/parity.py` (only if it needs to know the new ids), `etl/tests/test_parity.py`, a new real-database test `NgsRealDatabaseTest` in `:core:data` (skipped without `GRIDIRON_STATS_DB`, like the other contract tests), `.github` workflow only if the parity step needs a flag.

- [ ] Rebuild both databases with the Kotlin builder (`stats.db` for 2024-2026 and `accuracy.db` for 2024-2025, commands in CLAUDE.md) and the Python one into `etl/build/parity/py.db`, then run `python etl/tools/parity.py etl/build/parity/py.db etl/build/parity/kt.db` for 2025: must report no differences in NGS components.
- [ ] Write `NgsRealDatabaseTest`: for a named 2025 QB and WR (chosen from the CSV during the task), the Grid column over weeks 1-8 equals the weighted average computed from the raw CSV rows.
- [ ] Run `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test` (the known `scoring a full season ... is fast` timing failure is not this change) and the accuracy gate command from CLAUDE.md: PASS.
- [ ] Commit: `test: NGS parity and real-database checks`.

## Task 6: Docs and handoff

**Files:** `docs/superpowers/HANDOFF.md`, `docs/ARCHITECTURE.md` (sources and metric groups), `CLAUDE.md` (Known Gaps line about NGS), delete this plan and the spec in the final commit (they stay in git history).

- [ ] HANDOFF: NGS done, next is FTN or saved presets or season rollups; add the phone check "open Grid, Next Gen pack, confirm columns after the first refresh (first refresh rebuilds: INGEST_VERSION 6)".
- [ ] Run the full commands once more, then commit `docs: NGS metrics shipped` and open the PR.

## Self-Review

- **Spec coverage:** source facts and week rules (Task 2), weights and storage (Tasks 1, 2), ten metrics (1), pipeline and reuse (3), Python parity (4, 5), validation (3), tests (each task), docs (6). The spec's "cross-check against play-by-play totals within 10%" is folded into Task 3's validation as a warning; add it there, not as a failure.
- **Placeholders:** none.
- **Names:** component and metric ids match Task 1 in Tasks 2-5.
