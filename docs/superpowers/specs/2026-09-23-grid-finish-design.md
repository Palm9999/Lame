# Grid finish: design

Date: 2026-09-23 · Status: approved design, not yet planned · Completes PRODUCT_SPEC §6.1 (The Grid) for this phase

## Intent

Grid work that's still missing from Phase 3 of the build order, so the Grid can be treated as done before league sync starts. The user chose four pieces:

- **Team and snap-share quick filters**
- **Sparklines**
- **Advanced filter sheet**
- **CSV export**

Success looks like this:

- "WRs on the Chiefs with 70%+ snap share" is three taps.
- "RBs with 12+ carries per game and a success rate under 40%" is an advanced filter, and the sheet shows how many players match before you apply it.
- Every row shows the sorted stat's last six weeks at a glance.
- The current view can be shared as a CSV.

**Out of scope:** saved presets (the user declined them), S Pen hover, remembering filters after the app restarts, the 120Hz table benchmark, and Sleeper sync (next spec).

## What already exists

- `StatQuerySpec.teams` filters by team, and `StatQuerySpec.filters` filters on any `StatColumn`, including columns that aren't displayed.
- Filters apply to values as displayed (per game in per-game mode) and after percentiles, so they never change anyone's percentile.
- `StatQueryBuilder.count(spec)` already exists to back a live match count.
- `StatColumn.SNAP_SHARE` and `OFFENSE_SNAPS` exist.

**Consequence:** no query-builder changes are needed. The work is in `:core:data`, `:feature:players`, a small `:core:charts` addition and `:app` wiring.

## 1. Request model (`:core:data`)

`GridRequest` gains three fields:

| Field | Type | Default | Meaning |
|---|---|---|---|
| `teams` | `Set<String>` | empty | Team abbreviations; empty means all teams |
| `minSnapShare` | `Double?` | null | 0.25, 0.50 or 0.75; null means any |
| `filters` | `List<Filter>` | empty | Advanced filters, ANDed together |

`StatsRepository.grid` maps them onto the spec:

- `teams` goes to `spec.teams`.
- `minSnapShare` becomes a `Filter(SNAP_SHARE, AtLeast(x))`, placed ahead of `filters`.
- `filters` go to `spec.filters`.

The name search still overrides the qualifiers, as it does today. It does **not** override these filters: filters are something the user asked for explicitly.

**Team list:** a new `CatalogQueries.teams` returns `SELECT DISTINCT team FROM player WHERE team IS NOT NULL ORDER BY team`, loaded with the catalog into `Catalog.teams`. Team filtering matches the player's current team (`player.team`), which is how `spec.teams` already works. A player traded mid-season shows under his new team for every week. The team sheet notes this in one line.

## 2. Quick filters (`:feature:players`)

A third chip row goes under the position chips:

- **Team chip** reads "All teams", "KC" or "3 teams". Tapping opens a bottom sheet with a grid of team toggles plus "All teams". Changes apply as you tap.
- **Snap chip** reads "Any snaps", "25%+ snaps", "50%+" or "75%+". Tapping cycles a small menu of those four choices.
- **Filters chip** reads "Filters" or "Filters (2)" and opens the advanced sheet (section 3). It's highlighted when any filter is active.

The Summary line that shows the qualifier (e.g. "min 24 targets") also lists active filters in short form, e.g. "· TGT ≥ 50 · Catch% ≥ 65". That way no filter is ever invisible.

New `GridEvent`s:

- `TeamsSelected(Set<String>)`
- `MinSnapShareSelected(Double?)`
- `FiltersApplied(List<Filter>)`

Changing packs keeps all three.

## 3. Advanced filter sheet (`:feature:players`)

A modal bottom sheet that edits a **draft** copy of the filters. The Grid doesn't change until Apply.

**Rows.** Each filter row has four parts:

- **Stat:** a picker listing every `StatColumn` with its catalog name. Columns in the current pack come first; the rest are grouped as the catalog groups them.
- **Operator:** `≥`, `≤` or `between`. That covers every use found so far; strict `>` and `<` add nothing for continuous stats.
- **Value (one or two):** a number field.
- **Remove button.**

**Units.** Values are entered as displayed:

- Percent columns (the `StatFormat.PERCENT` set) are typed as percentages and divided by 100 when stored.
- In per-game mode, counting columns mean per game. The sheet header says "Values are per game" whenever per-game mode is on.

**Parsing.** Values use the same rules as the scoring editor:

- `,` or `.` as the decimal separator.
- Empty, `-`, `.`, `NaN`, infinity or non-finite input leaves the row incomplete.
- Incomplete rows are shown in the error color and skipped by Apply.
- For `between`, `min > max` counts as incomplete. The values are never silently swapped.

Parsing logic is shared by extracting the scoring editor's `DecimalInput` (`:feature:scoring`) into `:core:data` as `DecimalInput`.

**Live count.**

- `StatsRepository.count(request)` runs `StatQueryBuilder.count` against the spec the draft would produce.
- It's debounced 250ms and cancelled when superseded.
- It's shown as "124 players match".
- If the count query fails, the line reads "Count unavailable" and Apply still works.

**Actions.**

- **Add filter** adds a row, defaulting to the current sort column with `≥` and an empty value.
- **Clear all** empties the draft.
- **Apply** sends `FiltersApplied(completeRows)` and closes the sheet.
- Dismissing the sheet or tapping **Cancel** discards the draft.

**Limit:** 8 filters, which keeps the generated SQL small. The Add button is disabled at the limit.

## 4. Sparklines

**What they show:** the sorted column's value for each of the last six played weeks inside the selected range. Those weeks run from `max(weeks.first, last − 5)` to `last`, where `last = min(weeks.last, season.lastWeek)`.

- Each value is that single week's figure, so rates are that week's rate and fantasy points are that week's points under the active profile.
- Per-game mode doesn't change sparklines, because one week is already one game.
- A missing week (bye, injury, inactive) leaves a gap in the line; it's never drawn as zero.
- With fewer than two points, the sparkline is omitted.

**Query approach (chosen):** reuse `StatQueryBuilder.grid` once per week. For week `w`, the spec is:

- `weeks = w..w`, `columns = [sort]`, `playerIds` = the page's rows.
- `qualifiers = []`, `includeUnqualified = true`, `minGames = 1`.
- `percentiles = false`, `mode = TOTAL`, and the request's `scoring`.
- Teams, snap share and filters are **not** applied: the row set is already decided by the page.

This adds no new SQL paths, so per-week rates and scoring are exactly right by construction. The alternative, a new builder query grouped by week, was rejected because it would change the scoring SQL, the most sensitive code in the builder. If the speed test fails, that alternative is the fallback.

**API.**

- `StatsRepository.sparklines(page: GridPage): Map<String, Sparkline>`, with `data class Sparkline(val weeks: IntRange, val values: List<Double?>)`.
- The six week queries run sequentially on the repository's dispatcher.

**ViewModel.**

- After a page arrives, the ViewModel loads its sparklines.
- The table renders immediately and rows fill in when sparklines land.
- A new page cancels an in-flight sparkline load.
- A failure is logged and leaves sparklines empty. It never surfaces as a Grid error.

**Drawing.**

- A new `Sparkline` composable in `:core:charts`, drawn with Compose Canvas.
- About 44×14dp, placed at the end of the row's second line (after "WR · KC · 17 g"), so the frozen column doesn't get wider.
- The last point is drawn as a dot.
- It uses `onSurfaceVariant` in both themes, with no heat color.
- The row's accessibility description appends "last 6 weeks: 5, 7, –, 9, 4, 8".

**Speed budget:** six week queries for a 1,000-row page (the Grid's limit) finish within 300ms on the CI machine against the real database. The budget is enforced by a test in `:core:data`, using the same CI-aware budget as the scoring speed test in `RealDatabaseContractTest`.

## 5. CSV export

**Content.** `CsvExport.build(page: GridPage, profileName: String): String` in `:core:data`, pure JVM:

- First line: `# Gridiron · 2025 · Wk 1–8 · Receiving · WR · PPR · per game · TGT ≥ 50`. This describes the view. Spreadsheet apps show it as a first row.
- Header row: `Rank,Player,Pos,Team,Games,` followed by each column's abbreviation.
- One row per loaded row, with values exactly as displayed (`CellUi.text`). A missing value (`–`) is exported as an empty field.
- Fields follow RFC 4180: quoted when they contain `,`, `"` or a newline, with `"` doubled. Lines end with CRLF.
- Final line: `# Data: nflverse (CC BY 4.0)`.

**Structured fields.** Position, team and games come from new structured fields on `GridRowUi` (`position`, `team`, `games`). `detail` stays as is for display.

**Sharing.**

- An export action in the Grid's top bar writes the CSV to `cacheDir/exports/gridiron-<season>-<pack>.csv`.
- It shares the file with `ACTION_SEND`, type `text/csv`, through an `androidx.core.content.FileProvider`.
- The FileProvider is declared in `:app` with authority `${applicationId}.exports`, limited to `cache/exports/`.
- Each export overwrites the previous file of the same name.
- When no page has loaded yet, the action is disabled.

## Error handling

| Case | Behavior |
|---|---|
| Count query fails | "Count unavailable"; Apply still allowed |
| Sparkline query fails | No sparklines for that page, error logged; table unaffected |
| Filter value invalid | Row marked incomplete, skipped on Apply, never crashes |
| Selected team has no rows in the season | Normal empty state, "No players match." |
| Export write fails | Snackbar "Couldn't export"; no partial file shared |
| No app can receive the share | Android's chooser handles it; nothing extra |

## Testing

- **`:core:data` against the real database** (JDBC fixture):
  - Team and snap-share filtering returns only matching rows.
  - The count equals the size of the grid result for the same filters.
  - Sparkline values for one player equal single-week grid values, including a fantasy column and a rate column.
  - Byes come back as null.
  - The sparkline speed test.
- **`:core:data` unit tests:**
  - `CsvExport`: quoting, commas in names, empty cells, the header line, CRLF.
  - `DecimalInput.parse`: the scoring editor's existing cases carried over.
  - Filter summary text.
  - Percent conversion.
- **`GridViewModel` on virtual time:**
  - Filters survive pack changes.
  - Apply replaces the filters; Cancel leaves them alone.
  - The count is debounced and cancelled when superseded.
  - Sparklines load after the page and are cancelled by a newer page.
  - A sparkline failure doesn't set a Grid error.
- **Roborazzi screenshots:** the chip row with active filters, the team sheet, the filter sheet with a complete, an incomplete and a `between` row, and Grid rows with sparklines, in light and dark.
- **Accessibility check:** the row description includes sparkline values, and the sheet fields have labels.
