# Season rollups: design

## Goal

Serve the common Grid view (whole season, or the last 3, 5 or 8 weeks, no fantasy column) from pre-aggregated component sums instead of aggregating `player_week_stat` on every query. The user chose to build it as groundwork; phone timing is unmeasured.

## Out of scope

Fantasy columns (they need weekly rows for per-game bonuses and tiers, and scoring stays on-device), percentile speedups, Compare and the Player page, phone timing.

## Data

- New table `player_window_stat (player_id, season, window, metric_id, value)`. `window` is `S` (whole regular season), `L3`, `L5` or `L8`.
- Rows are component sums, including the games count. Rates are never stored; they are recomputed from the sums on the phone.
- `L` windows are the season's latest N played weeks, the same `weeks.first..weeks.last` range the Grid sends for "last N weeks". They must equal the weekly path's numbers exactly, byes and missed games included.
- Covering index on `(metric_id, season, window, value)`.
- Schema 8 → 9, `INGEST_VERSION` 8. Built in `:core:ingest` (Kotlin) and `etl/` (Python); `etl/tools/parity.py` compares the table.

## Query

`StatQueryBuilder`'s `agg` step reads `player_window_stat` (`WHERE metric_id IN (...) AND season = ? AND window = ?`) when all hold:

1. the spec's week range equals a stored window,
2. no fantasy column is planned,
3. the window is current (the latest played week still matches).

Otherwise it reads `player_week_stat` as today. A stale or missing table falls back silently. `base`, `scored`, `ranked` and the outer query are unchanged.

## Tests

- `:core:statquery` contract tier: rollup query equals weekly query, column for column, for `S`, `L3`, `L5`, `L8`.
- Fallback tests: fantasy column, custom week range, missing table, stale window each use `player_week_stat`.
- `:core:ingest`: hand-made weeks give exact sums, games counts and windows around a bye.
- `parity.py`: includes `player_window_stat`.
- Speed: the real-database test times rollup vs weekly for a full-season 12-column grid; no pass/fail threshold.

## Errors

A failed rollup build fails the refresh like any other table; the old `stats.db` stays.
