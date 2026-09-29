# NGS metrics: design

**Goal.** Add ten Next Gen Stats (NGS) metrics to the Grid, built by the phone's Kotlin ingest and matched by the Python ETL. Display only: the forecast doesn't use them, so no forecast or accuracy-gate change. FTN is a later PR.

**Out of scope.** Compare and Player page (a follow-up), FTN, any forecast use, NGS fields that duplicate ones we have (CPOE, completion %, air distance, passer rating).

## Source facts (checked against the published files, 2016-2026)

- Three all-seasons files at `nflverse-data/releases/download/nextgen_stats/`: `ngs_passing.csv.gz`, `ngs_rushing.csv.gz`, `ngs_receiving.csv.gz`. About 1.9 MB together; they update Tuesdays.
- One row per player-week, keyed by `player_gsis_id`, the id the pipeline already uses. No crosswalk.
- `week = 0` rows are season aggregates. Skip them: they would double-count and break week filters.
- Postseason weeks: NGS numbers the Super Bowl week 23, play-by-play 22 (2025). Remap NGS 23 to 22 when the season has no NGS week 22; other postseason weeks agree.
- Columns are published per-week averages, not counts.
- Positions vary by file (2025 receiving is WR/TE only); each metric keeps its registry position list, and a position mismatch just yields no row in the Grid.

## Storage: weights, never averaged averages

Rule that holds everywhere: rates are recomputed over the range. Each NGS average is stored as an internal **weighted sum** (`avg x weight`) beside an internal **weight** component, and the Grid divides them over the chosen range, as `cpoe` does (`Ratio(CPOE_SUM, CPOE_N)`).

Weights are NGS's own counts, not ours, so numerator and denominator come from one row:

| Weight component | From | Used by |
|---|---|---|
| `ngs_attempts` | passing `attempts` | passing averages |
| `ngs_carries` | rushing `rush_attempts` | rushing averages, RYOE per carry |
| `ngs_targets` | receiving `targets` | separation, cushion |
| `ngs_receptions` | receiving `receptions` | YAC over expected (a per-catch average) |

A weight of 0 or a missing average stores no row (all NGS components are sparse: absent means no NGS data, not zero).

## The ten metrics (tier B)

| Metric id | Abbr | Positions | Aggregate | Higher is |
|---|---|---|---|---|
| `ngs_time_to_throw` | TTT | QB | `ngs_ttt_w / ngs_attempts` | neutral |
| `ngs_aggressiveness` | AGG% | QB | `ngs_aggr_w / ngs_attempts` | better |
| `ngs_intended_air_yards` | IAY | QB | `ngs_iay_w / ngs_attempts` | better |
| `ngs_ryoe` | RYOE | RB, QB | Total of `rush_yards_over_expected` | better |
| `ngs_ryoe_per_att` | RYOE/A | RB, QB | `ngs_ryoe / ngs_carries` | better |
| `ngs_rush_efficiency` | EFF | RB, QB | `ngs_eff_w / ngs_carries` | **worse** (yards of travel per rushing yard; lower is more north-south) |
| `ngs_stacked_box_pct` | 8+ BOX% | RB, QB | `ngs_box_w / ngs_carries` | neutral |
| `ngs_separation` | SEP | WR, TE, RB | `ngs_sep_w / ngs_targets` | better |
| `ngs_cushion` | CUSH | WR, TE, RB | `ngs_cush_w / ngs_targets` | neutral |
| `ngs_yac_over_expected` | YACOE | WR, TE, RB | `ngs_yacoe_w / ngs_receptions` | better |

Neutral metrics (a style, not a quality) are still `higherIsBetter = true` in the registry; percentile shading is the existing behavior for such columns. Definitions and stability notes (`predicts`, `stability`) are written in the registry entries; a stability figure is given only where a published one exists, otherwise left null.

Internal components (never shown): the four weights above plus `ngs_ttt_w`, `ngs_aggr_w`, `ngs_iay_w`, `ngs_eff_w`, `ngs_box_w`, `ngs_sep_w`, `ngs_cush_w`, `ngs_yacoe_w`. `ngs_ryoe` is stored as a plain visible component.

## Pipeline

- **Kotlin** (`:core:ingest`): new `Input`s `NGS_PASSING`, `NGS_RUSHING`, `NGS_RECEIVING` (not per season, like `PLAYERS`), new `Ngs.kt` (readers, week filter and remap, row to `PlayerWeek` components, join by `(season, week, playerId)`), registry entries in `Metrics.kt`, `INGEST_VERSION` 5 to 6.
- **Fetching:** fetched once per build with saved validators (`source:ngs_*.csv.gz` in `schema_meta`), like the player list. A file that fails to download or read leaves that group out with a warning; the build never fails on NGS.
- **Reuse:** a season's own inputs still decide reuse, and NGS is copied with it. The exception is the newest built season: it is also rebuilt when any NGS file changed, because NGS posts on its own schedule. Older seasons are final, so an NGS update doesn't rebuild them.
- **Facts** for players not in the player table are dropped (the existing foreign-key rule).
- **Python** (`etl/gridiron_etl`): the same transform in `ngs.py`, metrics in `metrics.py`, the download already exists in `sources.py`. The parity job compares the two databases, including these components.
- **Statquery/Data:** `Components`, `StatColumn` (Ratio/Total per the table), `StatFormat` decimals, and `StatPack` placement: a "Next Gen" group on the QB, RB and WR/TE chips. Flex and TE chips show the receiving three. K and D/ST chips leave them out.

## Validation

- Ranges (hard failure only for impossible values, warning otherwise): time to throw 1.5-4.5 s, aggressiveness 0-60%, intended air yards -5-25, separation 0-8 yd, cushion 0-15 yd, stacked box 0-100%, RYOE per carry -8-8.
- Coverage warning per season when NGS has no rows (before the first NGS posting each September).
- Cross-check against play-by-play totals: NGS `rush_attempts`/`targets`/`attempts` for a player-week within 10% of ours in most rows; a warning, not a failure, since NGS counts differ slightly (kneels, spikes).

## Tests

- **Kotlin unit:** `NgsTest` (week-0 skipped, week-23 remap, weights and sums, missing values, unknown players); `MetricsTest` and `StatsDbWriterTest` counts; `StatColumn` ratio for a two-week range equals the hand-computed weighted average (not the mean of the two averages).
- **Pipeline:** fake fetcher cases: NGS unchanged and season unchanged (reused, NGS carried), NGS changed and newest season rebuilt, NGS changed and an old season reused, an NGS 404 or corrupt file (warning, build succeeds).
- **Python:** `test_ngs.py` and the registry count in `test_registry.py`.
- **Real database:** in the contract test, a Grid column over a week range matches a weighted average computed straight from the CSV for a named player.
- **CI:** parity job passes with the new components; accuracy gate unchanged.

## Docs

`HANDOFF.md` (next task becomes presets or rollups), `docs/ARCHITECTURE.md` (source list, metric groups), `CLAUDE.md` "Known Gaps" line about NGS, and attribution unchanged (NGS ships through nflverse, already credited).

## Open judgments

- The Grid's neutral metrics (TTT, AGG%, cushion, box%) get percentile shading like the others; a "no shading" flag is not added this round.
- RB separation and cushion appear only if NGS publishes RB rows; otherwise the columns are empty for RBs.
