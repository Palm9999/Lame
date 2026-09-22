# Gridiron ETL

Turns [nflverse](https://github.com/nflverse/nflverse-data) releases into a compact, pre-indexed SQLite database for the Android app.

The app never touches upstream sources. A single season of play-by-play is ~98 MB of CSV; this pipeline reduces two full seasons to **4.2 MB gzipped**.

## Usage

```bash
pip install -r requirements.txt
python -m gridiron_etl.build --seasons 2024 2025 --out build/stats.db
```

Downloads are cached in `~/.cache/gridiron` (override with `GRIDIRON_CACHE`). Pass `--force` to ignore the cache.

```bash
python -m pytest tests/ -q
```

## Output

Measured on 2024 + 2025:

| | |
|---|---|
| Facts | 372,187 |
| Players | 776 |
| Metrics | 39 |
| On disk | 26.4 MB |
| Shipped (gzip -9) | 4.2 MB |
| Build time (cached) | ~11 s |

Query latency on the built database:

| Query | Time | Plan |
|---|---|---|
| Grid: WRs sorted by target share over a week range | 4.2 ms | `idx_pws_metric_season_week` |
| Player detail: every stat for one player | 0.34 ms | primary key |
| Name search (prefix range) | 0.02 ms | `idx_player_search` |

## Schema

Long/narrow by design — **adding a metric is an `INSERT`, not a migration.**

| Table | Purpose |
|---|---|
| `player_week_stat` | `(player_id, season, week, team, metric_id, value)`. `WITHOUT ROWID`. |
| `metric` | Registry: name, definition, formula, tier, what it predicts, stability. Drives info sheets and column search in the app. |
| `player` | Players with at least one stat in the built seasons. |
| `schema_meta` | Schema version, seasons, source attribution. |

Two rules are enforced structurally: **no table or column name contains a year**, so a new season is data rather than schema; and this builds only `stats.db`, the reconstructible database. User state lives in a separate `user.db` on the device.

The database ships pre-indexed, `ANALYZE`d and `VACUUM`ed, so the query planner has statistics on first launch.

## Design notes

**Stat components, never fantasy points.** The pipeline emits receptions, yards, touchdowns — never scored points. League scoring is applied on the device, so any format works offline.

**Air yards share can legitimately leave [0, 1].** About 18% of pass attempts carry negative air yards (screens, checkdowns behind the line). A screen-heavy role produces a negative share, and a teammate can then exceed 1.0. The raw metric preserves this. **WOPR clamps its inputs**, because it is defined as an opportunity *rating* and a negative rating is meaningless.

**Snap counts need an ID crosswalk.** `snap_counts` keys on `pfr_player_id` while play-by-play uses gsis ids. The join routes through `players.csv` (91% coverage) and leaves unmapped rows with null snap data rather than dropping them.

**Index budget.** Because the fact table has a four-column text primary key, every secondary index stores that full key and ends up nearly table-sized. A `(season, week)` index measured at 29% of the file and served no query the app issues, so it was removed.

## Validation

Every build runs [`validate.py`](gridiron_etl/validate.py) and fails loudly on range violations, orphaned player or metric references, null values, or weeks with no share data. Wrong numbers are silent otherwise — a bad share still inserts cleanly and still renders in a table.

Ranges encode real football rather than convenient assumptions. The first version asserted aDOT ≥ −10; live data produced −12, because a player whose only target in a week was a deep screen inherits that play's air yards. The bound now derives from the observed play-level range (−19 to 64).

## Known gaps

- **Only 39 of ~450 catalogued metrics** are implemented. These are the play-by-play and snap-count metrics; Next Gen Stats, FTN charting, injuries and schedules (Vegas lines, weather) are wired in `sources.py` but not yet transformed.
- **No expected-points models yet.** `fpoe` is registered but not computed — it needs the xFP model from the projection phase.
- **Integer surrogate keys** would shrink the file substantially further. Deferred: it changes the contract with the Android query builder, so it should be decided alongside `:core:statquery`.
- **Pre-aggregated rollups** (season totals, L3/L5/L8 splits, per-column percentiles) are specified but not yet built.
