# Compare and custom scoring: design

Date: 2026-09-22 · Status: implemented (see plan docs/superpowers/plans/2026-09-22-compare-and-scoring.md) · Implements PRODUCT_SPEC §6.2 (Comparison) and the scoring half of §4.1

## Intent

Gridiron is a personal, free, feature-packed NFL fantasy stats app used on one Galaxy S24 Ultra. The Grid (v0.1) ranks and filters every player. This phase adds the second way to use the data: **putting two to four players side by side**, scored under **the user's own league rules**.

What the user asked for, in their words: the whole of §6.2 (compare tray, percentile bars, head-to-head table, radar, xFP scatter) and **full custom scoring now**, not a PPR toggle. League sync, projections and alerts are later phases and out of scope here.

Success looks like:

- Long-press two players in the Grid, tap Compare, and see where each ranks on every relevant stat within his position, for any week range, including a player against himself in another season.
- Fantasy points, expected fantasy points (xFP) and FPOE computed under a profile that matches the user's ESPN or Sleeper league, everywhere: Grid, Compare, per-game mode, any week range.
- The xFP scatter answers "who is due to regress" at a glance.

## Workstreams

| Stream | Scope | Depends on |
|---|---|---|
| A. Data | New actual components from play-by-play, expected components from ffopportunity, cross-check, schema v3 | none |
| B. Scoring | `ScoringProfile`, presets, storage, scoring SQL, three fantasy columns, Fantasy pack, profile editor | A's component IDs (pinned in task 1) |
| C. Compare | Navigation 3, tray, `:core:charts`, Compare screen with four tabs | B for the Scoring group and scatter only |

## A. Data

### New actual components (from nflverse play-by-play, already downloaded)

All are internal (hidden from the metric catalog): they exist to feed scoring.

| Metric id | Meaning |
|---|---|
| `passing_first_downs`, `rushing_first_downs`, `receiving_first_downs` | First downs gained by pass (credited to passer), rush, reception |
| `passing_2pt`, `rushing_2pt`, `receiving_2pt` | Successful two-point conversions |
| `fumbles_lost` | Fumbles lost, every play type including sack fumbles |
| `passing_tds_40`, `passing_tds_50` | Passing TDs of at least 40 / 50 yards |
| `rushing_tds_40`, `rushing_tds_50` | Rushing TDs of at least 40 / 50 yards |
| `receiving_tds_40`, `receiving_tds_50` | Receiving TDs of at least 40 / 50 yards |

A 55-yard TD counts in both the 40 and 50 components. Scoring rules decide whether both bonuses apply (see B).

### New expected components (internal, from ffopportunity)

Source: `https://github.com/ffverse/ffopportunity/releases/download/latest-data/ep_weekly_{season}.parquet`, one row per player-game, keyed on the same GSIS `player_id` as nflverse. Verified 2026-09-22: 2024-2026 exist, 2026 current through week 2, expected values stored to 2 decimals.

`x_completions`, `x_receptions`, `x_passing_yards`, `x_rushing_yards`, `x_receiving_yards`, `x_passing_tds`, `x_rushing_tds`, `x_receiving_tds`, `x_passing_2pt`, `x_rushing_2pt`, `x_receiving_2pt`, `x_passing_first_downs`, `x_rushing_first_downs`, `x_receiving_first_downs`, `x_interceptions`.

Rules:

- Rows whose player is not in the pruned `player` table (null position, linemen, punters on trick plays) are dropped.
- `x_interceptions` takes `pass_interception_exp` only. `rec_interception_exp` (targets intercepted) is not charged to receivers, which matches every mainstream scoring system.
- Expected fumbles, sacks and bonuses do not exist; they count as zero in xFP. FPOE therefore credits long plays and charges fumbles.
- The source is registered in `sources.py` with the existing `--skip-missing` behavior. Attribution goes in the README beside nflverse.

### Validation (added to `validate.py`)

- **Cross-check:** for every player-week present in both sources, our receptions, completions, yards, TDs, 2-pt and first downs equal ffopportunity's actual columns within 1. Failures fail the build.
- **Fumbles:** our `fumbles_lost` ≥ ffopportunity's `rec_fumble_lost + rush_fumble_lost` for every player-week (ours adds sack fumbles).
- **Fantasy contract (Python side):** our components scored with the reference profile below equal ffopportunity `total_fantasy_points` within 0.01, after removing fumbles from both sides; our expected components scored the same way equal `total_fantasy_points_exp` within 0.1.
- Range checks for each new metric (non-negative counts; `_50 ≤ _40 ≤ tds`).

Reference profile, verified against ffopportunity 2026 data to machine precision: reception 1, passing yard 0.04, rushing/receiving yard 0.1, passing TD 4, rushing/receiving TD 6, two-point conversion 2, interception −2, fumble lost −2 (rush and receiving fumbles only).

### Housekeeping

- `SCHEMA_VERSION` 2 → 3. The database ships inside the APK built from the same commit, so the app and schema always match; a runtime version check arrives with in-app data updates (a later phase).
- The unused `fpoe` metric (registered, never populated: 0 rows) stays in `metrics.py` but is flagged `computed`, beside two new computed rows `fantasy_points` and `expected_fantasy_points`: the app takes each column's name and definition from the registry, and a contract test requires every column to have a visible row. A new `metric.computed` column marks them; validation fails if a computed metric has stored facts.
- Scoring inputs are stored sparse: zero values are omitted (absent means zero), because they are zero for most player-weeks and storing them would roughly double the database.
- Report the database size change after the first full build.

## B. Scoring

### Model (`:core:model`)

```kotlin
data class ScoringProfile(
    val id: String,              // stable UUID
    val name: String,            // "ESPN league"
    val weights: Map<ScoringRule, Double>,
    val receptionByPosition: Map<Position, Double>, // RB, WR, TE; overrides RECEPTION
    val yardageBonuses: List<YardageBonus>,
)
data class YardageBonus(val stat: BonusStat, val min: Int, val maxExclusive: Int?, val points: Double)
```

`ScoringRule` (points per unit):

| Group | Rules |
|---|---|
| Passing | `PASS_YARD`, `PASS_TD`, `INTERCEPTION`, `PASS_2PT`, `COMPLETION`, `INCOMPLETION`, `PASS_FIRST_DOWN`, `SACK_TAKEN`, `PASS_TD_40`, `PASS_TD_50` |
| Rushing | `RUSH_YARD`, `RUSH_TD`, `RUSH_2PT`, `CARRY`, `RUSH_FIRST_DOWN`, `RUSH_TD_40`, `RUSH_TD_50` |
| Receiving | `RECEPTION`, `REC_YARD`, `REC_TD`, `REC_2PT`, `REC_FIRST_DOWN`, `REC_TD_40`, `REC_TD_50` |
| Turnovers | `FUMBLE_LOST` |

`BonusStat` is `PASSING_YARDS`, `RUSHING_YARDS`, `RECEIVING_YARDS`, `RUSH_REC_YARDS`. A bonus applies once per game when `min ≤ stat < maxExclusive` (open-ended when null). ESPN and Sleeper express tiers as exclusive ranges ("100-199", "200+"), so ranges are the primitive; overlapping ranges are allowed and each that matches applies.

`INCOMPLETION` is scored as `attempts − completions`. Receptions for a player whose position has no entry in `receptionByPosition` use `RECEPTION`.

Validation (in the model, tested): weights finite; name non-blank; bonus `min ≥ 0` and `maxExclusive > min`.

### Presets

PPR, Half-PPR, Standard, using ESPN defaults: passing yard 0.04, passing TD 4, interception −2, rushing/receiving yard 0.1, rushing/receiving TD 6, two-point conversion 2 (all three), fumble lost −2; reception 1 / 0.5 / 0; everything else 0; no bonuses. Presets are immutable; "Duplicate" creates an editable copy. On first launch the active profile is PPR.

### Storage (`:core:datastore`)

DataStore holding one JSON document (kotlinx.serialization) with a `formatVersion`, the user's profiles and the active profile id. A missing or unreadable file falls back to presets with PPR active, and the unreadable file is kept as `*.corrupt` rather than overwritten silently. Unknown rule names in stored JSON are ignored so older builds can read newer files.

### Query (`:core:statquery`)

`StatQuerySpec` gains `scoring: ScoringProfile?`. Fantasy columns require it; the builder throws if one is requested without a profile.

When the spec contains a fantasy column, the builder adds a `wk` CTE ahead of `agg`:

1. Pivot the needed components per `(player_id, season, week)` over the requested range with `SUM(CASE WHEN metric_id = ? THEN value END)`.
2. Compute per week `fp = Σ weightᵢ · componentᵢ + Σ bonus CASE` and `xfp = Σ weightᵢ · expectedᵢ` (expected components exist only for the rules listed in A; others contribute 0). Reception weight is a `CASE` on `player.position`.
3. `agg` sums `fp`, `xfp` and counts games like any `Total`.

Every weight, threshold and metric id is a bound parameter. Rules with weight 0 are still bound (the SQL shape depends only on the number of bonuses), so the statement text is stable per bonus count. `SqlSafetyTest` fuzzing extends to profile names, weights and bonuses.

New `StatColumn`s: `FANTASY_POINTS`, `EXPECTED_FANTASY_POINTS`, `FPOE` (`fp − xfp`). All three scale with games in per-game mode and rank like other columns.

Performance: scoring every player for a full regular season with the Fantasy pack must run in under 250 ms on the JVM, measured in a test against the real database.

### Grid

- New **Fantasy** pack: `FANTASY_POINTS`, `EXPECTED_FANTASY_POINTS`, `FPOE`, `TARGETS`, `CARRIES`, `TARGET_SHARE`, `CARRY_SHARE`, `SNAP_SHARE`; default sort `FANTASY_POINTS`; population qualifier `OFFENSE_SNAPS`.
- A profile chip in the title bar shows the active profile name. Tap: choose a profile or "Edit profiles". Switching re-runs the current query.

### Editor (`:feature:scoring`)

List screen: profiles with the active one marked; actions Duplicate, Rename, Delete (not presets, not the last profile), Set active. Edit screen: sections Passing, Rushing, Receiving (with RB/WR/TE reception fields), Turnovers, Bonuses (add/remove yardage ranges; long-TD rules live in their stat groups). Decimal fields accept negatives; invalid input shows an inline error and is not saved. "Reset to preset" restores the preset the profile was duplicated from.

## C. Compare

### Tray

- Long-press a Grid **row** adds that player; long-press on a **header** still explains the stat. Haptic confirmation; a full tray (4) shows a snackbar instead.
- A `CompareSlot` is `(playerId, season, weekRange)`, taken from the Grid's current season and range when added. The same player may occupy several slots with different seasons or ranges (self-comparison).
- The tray bar sits at the bottom of the Grid, above the system navigation bar (it lives in `:feature:players`; the sheets and profile chip that Grid and Compare share live in `:core:ui`, so no feature module depends on another): slot chips (tap to edit season/range, ✕ to remove) and a Compare button (enabled at 2+ slots).
- Tray state is persisted in `:core:datastore` alongside profiles.

### Ranking

Each slot is ranked against its own position, over its own season and range, using the existing Grid query filtered to the slot's player after percentiles are computed. Population qualifiers per position: QB `DROPBACKS`, RB `CARRIES`, WR and TE `TARGETS`, with the existing per-game thresholds. A slot below its qualifier shows values with a "small sample" label and no percentiles.

### Stat sets and groups

Chosen per position; a mixed-position comparison shows the union with "—" where a stat does not apply.

| Group | QB | RB | WR / TE |
|---|---|---|---|
| Opportunity | `DROPBACKS`, `ATTEMPTS`, `CARRIES`, `QB_RUSH_INSIDE_5`, `SNAP_SHARE` | `CARRIES`, `CARRY_SHARE`, `TARGETS`, `TARGET_SHARE`, `WEIGHTED_OPPORTUNITIES`, `RZ_CARRIES`, `GL_CARRIES`, `SNAP_SHARE` | `TARGETS`, `TARGET_SHARE`, `AIR_YARDS_SHARE`, `WOPR`, `RZ_TARGETS`, `EZ_TARGETS`, `SNAP_SHARE` |
| Efficiency | `EPA_PER_DROPBACK`, `CPOE`, `SACKS_TAKEN`, `INTERCEPTIONS` | `RUSH_SUCCESS_RATE`, `RUSH_EPA_PER_CARRY`, `CATCH_RATE` | `ADOT`, `RACR`, `CATCH_RATE`, `YAC` |
| Scoring | `FANTASY_POINTS`, `EXPECTED_FANTASY_POINTS`, `FPOE`, `PASSING_TDS`, `RUSHING_TDS` | `FANTASY_POINTS`, `EXPECTED_FANTASY_POINTS`, `FPOE`, `RUSHING_TDS`, `RECEIVING_TDS` | `FANTASY_POINTS`, `EXPECTED_FANTASY_POINTS`, `FPOE`, `RECEIVING_TDS` |
| Context | `PASSING_YARDS`, `OFFENSE_SNAPS`, `TOTAL_EPA` | `RUSHING_YARDS`, `RECEIVING_YARDS`, `OFFENSE_SNAPS`, `TOTAL_EPA` | `RECEIVING_YARDS`, `RECEPTIONS`, `OFFENSE_SNAPS`, `TOTAL_EPA` |

These lists live in one table in `:core:data` so they can be tuned without touching UI code. Lower-is-better stats (`INTERCEPTIONS`, `SACKS_TAKEN`) already invert their percentile via `higherIsBetter`.

Group composite: mean percentile of the group's ranked stats for that slot; null when none are ranked.

### Screen (`:feature:compare`)

Header: one column per slot (name, team, position, season and range, player color), the profile chip, and a per-game toggle that behaves as it does in the Grid. Four tabs:

- **Bars (default).** Per group: a composite row, then one row per stat: label, and per slot a horizontal 0-100 bar in the slot color with the formatted value. Long-press or hover a label for the stat definition (the existing `MetricSheet` text).
- **Table.** Stats as rows, slots as columns, each cell value plus percentile. Two slots: a difference column (slot 1 − slot 2, formatted in the stat's units). Three or four: the best value per row is bold. Toggle "Only real differences" hides rows where max − min percentile ≤ 10.
- **Radar.** Two slots maximum; with more, a selector picks two. Axes fixed per position: QB `EPA_PER_DROPBACK`, `CPOE`, `DROPBACKS`, `CARRIES`, `PASSING_TDS`, `FPOE`; RB `CARRY_SHARE`, `TARGET_SHARE`, `RUSH_SUCCESS_RATE`, `RUSH_EPA_PER_CARRY`, `GL_CARRIES`, `SNAP_SHARE`, `FPOE`; WR/TE `TARGET_SHARE`, `AIR_YARDS_SHARE`, `ADOT`, `RACR`, `YAC`, `RZ_TARGETS`, `FPOE`. Mixed positions fall back to the first slot's axes. Scale is percentile 0-100 with rings at 25/50/75.
- **Scatter.** Population: every qualified player at the first slot's position over the first slot's season and range. X = xFP per game, Y = fantasy points per game, a y = x diagonal, "Sell high" above and "Buy low" below. Population dots are neutral grey; slots are drawn in their colors with name labels. Tap (or hover) a dot for name and both values; an "Add to compare" action in that popup.

Landscape: Bars and Table side by side. Charts expose a spoken summary (for example "Radar: Puka Nacua higher on 5 of 7 axes") for TalkBack; the Table tab is the complete accessible form.

### Charts (`:core:charts`)

Compose Canvas implementations of `PercentileBars`, `RadarChart`, `ScatterChart` taking plain immutable data (labels, values 0-100 or x/y pairs, colors). No charting library, no database or domain types. Four slot colors are added to the design system, each distinguishable from the others in light and dark themes and with color-vision deficiency.

## App structure

- **Navigation 3** with destinations Grid (start), Compare, Scoring list, Scoring edit.
- New modules: `:core:datastore`, `:core:ui`, `:core:charts`, `:feature:compare`, `:feature:scoring`.
- `:core:data` gains `ScoringRepository`, `CompareTrayRepository`, `CompareRepository` (one Grid query per slot, run concurrently on the single database dispatcher).
- Manual DI in `GridironApplication` stays; no Hilt.
- Library versions (Navigation 3, DataStore, kotlinx.serialization) are checked against the published artifacts at plan time, not assumed.

## Error handling

- Database failures: existing behavior (error state with the reason).
- Corrupt preferences file: presets restored, file kept as `*.corrupt`, a one-time snackbar says profiles were reset.
- A slot whose player has no rows in its range: the slot shows "No games in this range" and is excluded from charts.
- A deleted active profile: the first remaining profile becomes active.

## Testing

| Layer | Tests |
|---|---|
| ETL | Unit tests for each new component; cross-check, fumble and fantasy-contract validations run in every build |
| Scoring model | Preset values, validation rules, JSON round-trip, unknown-rule tolerance |
| Scoring SQL | Builder unit tests; fuzzed SQL safety; contract test on the real DB against the ffopportunity reference profile; bonus range edges (99, 100, 199, 200); position-specific reception weights; 250 ms performance test |
| DataStore | Round-trip; corrupt file fallback |
| View models | Grid with Fantasy pack and profile switching; Compare with 2 and 4 slots, self-comparison, small-sample slot, real DB |
| Screens | Robolectric screenshots at the S24 Ultra size, light and dark: each Compare tab, the tray, the scoring list and editor. Every screenshot is looked at before the work is called done |

## Out of scope

Kicker, defense/special teams and IDP scoring (no data); projections; league sync and importing scoring settings from ESPN or Sleeper; alerts; team comparison; sharing or exporting comparisons.
