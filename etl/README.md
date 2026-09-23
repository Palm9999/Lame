# Gridiron ETL

Turns [nflverse](https://github.com/nflverse/nflverse-data) releases into a compact, pre-indexed SQLite database for the Android app. Expected-points components come from [ffopportunity](https://github.com/ffverse/ffopportunity), an ffverse project.

The app never touches upstream sources. A single season of play-by-play is ~98 MB of CSV; this pipeline reduces three full seasons (two complete, one in progress) to **8.0 MB gzipped**.

## Usage

```bash
pip install -r requirements.txt
python -m gridiron_etl.build --seasons 2024 2025 --out build/stats.db
```

Downloads are cached in `~/.cache/gridiron` (override with `GRIDIRON_CACHE`). Pass `--force` to ignore the cache.

```bash
python -m pytest tests/ -q
```

## Data sources

| Source | Provides | License |
|---|---|---|
| [nflverse](https://github.com/nflverse/nflverse-data) | Play-by-play, snap counts, weekly rosters, schedules, players, Next Gen Stats — everything the `x_*` prefix isn't. | CC-BY 4.0, per the repository's [`LICENSE.md`](https://raw.githubusercontent.com/nflverse/nflverse-data/master/LICENSE.md) |
| [ffopportunity](https://github.com/ffverse/ffopportunity) | Weekly expected-points components (`x_*` metrics): what an average player would have produced from the same plays. Fetched from `https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_{season}.parquet`, keyed on the same gsis player ids as nflverse. | GPL (>= 3), per the package's [`DESCRIPTION`](https://raw.githubusercontent.com/ffverse/ffopportunity/main/DESCRIPTION) and [`LICENSE.md`](https://raw.githubusercontent.com/ffverse/ffopportunity/main/LICENSE.md) |

## Output

Measured on 2024 + 2025 + 2026 (through week 2):

| | |
|---|---|
| Facts | 577,407 |
| Players | 832 |
| Metrics | 81 (41 visible, 40 internal, including 15 `x_*` expected components) |
| On disk | 44.1 MB |
| Shipped (gzip -9) | 8.0 MB |
| Build time (cached) | ~11 s |

Query performance is measured by the contract tests in [`:core:statquery`](../core/statquery/README.md), which run the app's actual generated SQL against this database.

## Schema (version 3)

Long/narrow by design: **adding a metric is an `INSERT`, not a migration.**

| Table | Purpose |
|---|---|
| `player_week_stat` | `(player_id, season, week, team, metric_id, value)`. `WITHOUT ROWID`. |
| `metric` | Registry: name, definition, formula, tier, what it predicts, stability, and whether it is `internal`. Drives info sheets and column search in the app. |
| `player` | Players with at least one stat in the built seasons. |
| `schema_meta` | Schema version, seasons, source attribution (nflverse and ffopportunity), and `expected_through_week:<season>`: the last week through which ffopportunity's expected components cover the season's play-by-play. |

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

**Expected components are cross-checked against play-by-play, twice.** `validate.cross_check` compares our play-by-play actuals against ffopportunity's own actual columns, stat by stat — two independent derivations of the same plays, so a mismatch usually means one of them is wrong. `validate.fantasy_contract` separately confirms that both our actuals *and* ffopportunity's expected components reproduce the file's own fantasy-point totals under the reference scoring profile, catching component/total drift that a per-stat comparison alone can't see. Both run on every build under one three-tier `ComparisonPolicy` (see `validate.py`): a mismatch within a per-stat tolerance is agreement; a larger one is logged as a WARNING with the player-week and both values, but doesn't fail the build; the build fails only when a single row exceeds the check's hard cap or one season collects more outliers than its budget. The scheduled build must not stop over one upstream quirk, but many small mismatches, or one wildly wrong row, still fail it. Each tolerance is traced to specific real plays (lateral-play yardage, one Super Bowl LIX two-point conversion, a handful of misattributed fumbles).

**Expected coverage is checked per week.** The cross-checks inner-join the two sources, so a play-by-play week that ffopportunity hasn't processed yet would otherwise go unnoticed, with every player's xFP for it silently 0. The build logs a WARNING naming any such week and records `expected_through_week:<season>` in `schema_meta`.

**Kneels and spikes: box score versus signal.** A QB kneel is a carry in the box score (and in ffopportunity's totals), and a spike is a pass attempt, so box-score counts and every scoring input include them. Rates (success rate, EPA per carry or dropback, CPOE, every share) and usage signals (red-zone, green-zone and goal-line carries, designed QB runs inside the 5, weighted opportunities) exclude them: a kneel inside the 5 is a clock-killer, not a scoring opportunity.

## Validation

Every build runs [`validate.py`](gridiron_etl/validate.py). The database checks below fail the build loudly. Wrong numbers are otherwise silent: a bad share still inserts cleanly and still renders in a table.

- **Range checks** encode real football rather than convenient assumptions. The first version asserted aDOT ≥ −10; live data produced −12, because a player whose only target was a deep screen inherits that play's air yards. The bound now derives from the observed play-level range (−19 to 64).
- **Coherence checks** test relationships range checks can't see: targets within team targets, snaps within team snaps, and derived snap share agreeing with the published one to within one 0.01 rounding step. That last check caught the max-snaps shortcut. It tolerates exactly one step because the source is occasionally self-inconsistent: 2024 TB week 19 lists 44 snaps at 0.91, and no integer team total produces that.
- **Referential checks**: no orphaned player or metric ids, no nulls, no week without share data.
- **Cross-source checks** (`cross_check`, `fantasy_contract`) compare our play-by-play actuals and ffopportunity's expected components against ffopportunity's own actual and expected fantasy-point totals. Unlike the checks above, these warn on isolated mismatches and fail only past a hard cap or a season budget; see the note above.
- **Expected coverage** (`expected_coverage`) warns when a play-by-play week has no ffopportunity rows.

## Known gaps

- **Only 39 of ~450 catalogued metrics** are implemented. These are the play-by-play and snap-count metrics; Next Gen Stats, FTN charting, injuries and schedules (Vegas lines, weather) are wired in `sources.py` but not yet transformed.
- **`opportunity_share`** (RB backfield share) is registered but not computed. `fpoe` is now computable on-device, since the ETL loads ffopportunity's `x_*` expected components alongside the actuals.
- **Pre-aggregated season rollups** (season totals, L3/L5/L8 splits) are specified but not built. They're the next performance step: a full-season Grid query is ~85 ms today, and a rollup would serve the most common view without aggregating weekly facts at all.
