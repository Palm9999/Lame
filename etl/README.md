# Gridiron ETL

Turns [nflverse](https://github.com/nflverse/nflverse-data) releases into a compact, pre-indexed SQLite database for the Android app.

The app never touches upstream sources. A single season of play-by-play is ~98 MB of CSV; this pipeline reduces two full seasons to **6.3 MB gzipped**.

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
| Facts | 466,718 |
| Players | 776 |
| Metrics | 50 (39 visible, 11 internal) |
| On disk | 34.5 MB |
| Shipped (gzip -9) | 6.3 MB |
| Build time (cached) | ~11 s |

Query performance is measured by the contract tests in [`:core:statquery`](../core/statquery/README.md), which run the app's actual generated SQL against this database.

## Schema (version 2)

Long/narrow by design: **adding a metric is an `INSERT`, not a migration.**

| Table | Purpose |
|---|---|
| `player_week_stat` | `(player_id, season, week, team, metric_id, value)`. `WITHOUT ROWID`. |
| `metric` | Registry: name, definition, formula, tier, what it predicts, stability, and whether it is `internal`. Drives info sheets and column search in the app. |
| `player` | Players with at least one stat in the built seasons. |
| `schema_meta` | Schema version, seasons, source attribution. |

Two rules are enforced structurally: **no table or column name contains a year**, so a new season is data rather than schema; and this builds only `stats.db`, the reconstructible database. User state lives in a separate `user.db` on the device.

The database ships pre-indexed, `ANALYZE`d and `VACUUM`ed, so the query planner has statistics on first launch.

## Design notes

**Stat components, never fantasy points.** The pipeline emits receptions, yards, touchdowns, never scored points. League scoring is applied on the device, so any format works offline.

**Rates must be recomputable over any week range.** Target share over weeks 1–8 is `Σ targets / Σ team targets`, not the mean of eight weekly shares, which would weigh a 3-target blowout the same as a 12-target shootout. So besides each finished weekly rate, the pipeline stores the components behind it as **internal metrics**: team targets, team air yards, team carries, team snaps, rush successes, EPA sums, CPOE sum and count, and a games counter `g`. The app never shows them as columns.

**Air yards share can legitimately leave [0, 1].** About 18% of pass attempts carry negative air yards (screens, checkdowns behind the line). A screen-heavy role produces a negative share, and a teammate can then exceed 1.0. The raw metric preserves this. **WOPR clamps its inputs**, because it is defined as an opportunity *rating* and a negative rating is meaningless.

**Team snaps are solved, not assumed.** In some games nobody plays every snap: 2025 SF week 11 ran 55 plays, but the most any player logged was 53. So team snaps are not taken as the max, but solved as the integer that agrees with every player's published (two-decimal) snap percentage.

**Snap counts need an ID crosswalk.** `snap_counts` keys on `pfr_player_id` while play-by-play uses gsis ids. The join routes through `players.csv` (91% coverage) and leaves unmapped rows with null snap data rather than dropping them.

**Search names fold accents.** Names are NFKD-decomposed before non-alphanumerics are stripped, so "Tomás" is stored as `tomas` rather than `toms`. The Kotlin `normalizeSearch` must match exactly; a contract test checks every player.

**Index budget.** The fact table has a four-column text primary key, so every secondary index carries that whole key. There is exactly one: `(metric_id, season, week, value)`. Including `value` makes the Grid's aggregation a covering-index read, which measured 139 ms → 54 ms for a full-season 12-component query. A `(season, week)` index measured 29% of the file and served no query the app issues, so it was removed.

## Validation

Every build runs [`validate.py`](gridiron_etl/validate.py) and fails loudly. Wrong numbers are otherwise silent: a bad share still inserts cleanly and still renders in a table.

- **Range checks** encode real football rather than convenient assumptions. The first version asserted aDOT ≥ −10; live data produced −12, because a player whose only target was a deep screen inherits that play's air yards. The bound now derives from the observed play-level range (−19 to 64).
- **Coherence checks** test relationships range checks can't see: targets within team targets, snaps within team snaps, and derived snap share agreeing with the published one to within one 0.01 rounding step. That last check caught the max-snaps shortcut. It tolerates exactly one step because the source is occasionally self-inconsistent: 2024 TB week 19 lists 44 snaps at 0.91, and no integer team total produces that.
- **Referential checks**: no orphaned player or metric ids, no nulls, no week without share data.

## Known gaps

- **Only 39 of ~450 catalogued metrics** are implemented. These are the play-by-play and snap-count metrics; Next Gen Stats, FTN charting, injuries and schedules (Vegas lines, weather) are wired in `sources.py` but not yet transformed.
- **No expected-points models yet.** `fpoe` and `opportunity_share` are registered but not computed.
- **Pre-aggregated season rollups** (season totals, L3/L5/L8 splits) are specified but not built. They're the next performance step: a full-season Grid query is ~85 ms today, and a rollup would serve the most common view without aggregating weekly facts at all.
