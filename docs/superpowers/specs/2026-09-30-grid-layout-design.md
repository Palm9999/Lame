# Grid layout: more table, less clutter

**Goal.** Show more of the table and read it faster. Four changes on the Grid, chosen from mockups (top bar 3, player cell 1, numbers 1): a two-row top bar that slides away on scroll, a "View & filters" sheet that takes the rarely used controls, a leaner player cell, and quieter number columns. No new data, and the forecast, Compare, the Player page, packs and columns are untouched.

**Why.** Estimated on a 360 x 800 dp phone, today's title bar, search field, three chip rows and summary line take about 300 dp, leaving about 8 rows. The target is about 12 rows with the bar showing and about 14 with it hidden. Row counts are estimates until measured on the phone.

## Decisions (from the user)

- Top bar: two slim rows plus a Filters sheet, sliding away on scroll down (option 3).
- Player cell: narrower, trend line dropped (option 1).
- Numbers: units in the header, no zebra, sorted-column tint (option 1).
- One correction to the mockup: the trend line is not "moved" to the Player page. That page already has a week-by-week game log, so the Grid just drops it.

## 1. Top bar

New file `feature/players/.../GridChrome.kt` holds the bar; `GridScreen.kt`'s `GridContent` calls it in place of `TitleBar`, the search field, the three `ChipRow`s and `Summary`.

- **Row 1 (48 dp):** "Gridiron", profile chip, weeks, season, a search icon, the ☰ menu. The "Data through week N" caption moves into the summary line.
- **Row 2 (48 dp), one chip row:** pack chip (a dropdown of the packs for the current position chip, today's `positions.packs`), a segmented position control All / QB / RB / WR / TE plus a final "More ▾" segment that opens FLEX, K and D/ST, and one "Filters (n)" chip. `n` counts active advanced filters plus every non-default control inside the sheet (teams, roster, snap floor, per game off, heat off, non-default density).
- **Summary line (28 dp):** "212 players · threshold · filter descriptions" as today, plus the data-through caption. `testTag("summary")` stays. The 2 dp refresh bar stays under it.
- **Search:** the icon opens a full-width field over row 2, keeping `testTag("search")`. Closing it does not clear the query; while a name search is active the Filters chip shows a dot and the icon is filled. Clearing works from the field's ✕.
- **Slide away:** a `NestedScrollConnection` on the table's list observes scroll direction and drives a chrome offset, `0` (shown) to `-chromeHeight` (hidden). It never consumes scroll. Scrolling down hides it after 24 dp of travel; any upward scroll shows it; reaching the top of the list shows it; a new request (sort, filter, pack) shows it. The table's own header stays pinned. The hidden bar is not in the accessibility tree; TalkBack focus reaching the top of the list, or a scroll-up action, reveals it.
- Approach: a custom connection rather than Material's `enterAlwaysScrollBehavior`, because that needs a `Scaffold` top bar and the table header has to stay pinned below a moving bar.

## 2. "View & filters" sheet

`FilterSheets.kt`'s `FilterSheet` becomes the "View & filters" sheet.

- **Top section, applies instantly** (each control calls its existing `GridEvent`): Presets (opens the existing presets sheet, with its count), Teams (opens `TeamSheet`), Roster (the current `RosterChip` choices, only when a roster exists), Snap floor (not shown for K and D/ST, as today), Per game, Heat, Row height (Comfortable 48 dp / Compact 40 dp), and Export (the current CSV flow).
- **Below it:** today's advanced filters, unchanged: draft, live count, Apply.
- Test tags move with their controls: `chip:teams`, `chip:roster`, `chip:snaps`, `chip:presets`, `chip:export` become row tags in the sheet; `chip:filters` stays on the bar's chip.
- **Row height** is saved as `gridDensity` in the prefs JSON: enum `RowDensity { COMFORTABLE, COMPACT }`, default `COMFORTABLE`, `formatVersion` stays 3 (an absent key or unknown value reads as the default, like `gridPresets`). A small `GridDisplayRepository(prefs: PrefsSource)` in `:core:data` exposes `density: Flow<RowDensity>` and `setDensity`; `GridViewModel` merges it into `GridUiState.Ready` as `density`. It is a display setting, never part of `GridRequest`, and presets do not store it.

## 3. Player cell

- Frozen column 172 sp to 148 sp; the stat columns get the difference.
- Rank gutter 26 dp to 20 dp. Line 1: star (if rostered) and name, ellipsized. Line 2: position · team. The injury letter becomes a small outlined pill at the cell's right edge (colors as today).
- **Trend line removed.** `GridUiState.Ready.sparklines`, the sparkline `MutableStateFlow`, and the `repository.sparklines(page)` call after each page are removed, and so is the sparkline line in `rowDescription`. Dead code goes with it (`StatsRepository.sparklines`, `Sparkline` in `:core:data`) if nothing else references them; `:core:charts`' `Sparkline` composable stays if a test uses it. This also removes one query per page load.

## 4. Numbers

- Cells show the number only for percent columns; the header reads "CATCH %". `CellUi` gains `display: String` (digits only for percent columns, otherwise equal to `text`); `text` keeps the units, and `rowDescription`, the CSV export and filters keep using `text`.
- Tabular figures: `NumberStyle` sets `fontFeatureSettings = "tnum"`.
- Zebra off; a 1 dp `outlineVariant` line under each row instead. Heat stays a toggle, its maximum alpha stays 0.55.
- The sorted column gets a background tint (`primary` at 7% alpha, under the heat color) and a 2 dp primary underline in its header; its cell weight stays semibold.
- Column width 78 sp to 72 sp.

## Data flow

`GridViewModel` state gains `density`; loses `sparklines`. `GridScreen` reads `density` for `RowHeight` (48 sp or 40 sp) and passes it to `StatTable`. Scroll direction lives in the composition (`GridChrome`'s own state), not the view model, so a config change resets it to shown.

## Errors and edge cases

- A saved `gridDensity` the app doesn't know reads as Comfortable.
- If the density write fails, the control keeps its previous value and the sheet shows the existing snackbar message path.
- An empty page, a Failed state and the Loading state keep today's layouts (no chrome slide when there is nothing to scroll).
- Very small screens: if the chip row's contents don't fit, it scrolls sideways as today's rows do.
- Large font scale: rows and columns use sp, so they grow with the user's setting; the bar's row heights use `wrapContentHeight` with the 48 dp minimum.

## Testing

- **`GridScreenTest`** (Compose, Robolectric): the two-row bar shows the pack chip, position segments and the Filters chip; the controls moved into the sheet are reachable from it and fire their events; the bar hides after scrolling down and returns on scrolling up and at the top; the search icon opens the field and a query keeps the dot after closing; percent cells show digits and the header shows "%"; the sorted column carries the tint.
- **`GridViewModelTest`:** density flows into state and is written on change; `repository.sparklines` is never called; the Filters count includes the instant controls.
- **`:core:datastore`:** `gridDensity` round trip, absent key and unknown value read as the default.
- **`:core:data`:** `GridDisplayRepository` tests; `CellUi.display` for percent and non-percent columns.
- **Screenshots:** re-record the Roborazzi Grid images (`:feature:players:recordRoborazziDebug`) and read them before committing.
- **Gates:** `./gradlew test` against the real database, and `./gradlew :app:assembleRelease`. No ingest, forecast or accuracy change.

## Out of scope

Compare and the Player page, packs and columns, theme colors, per-user column choice, and saving row height inside presets.

## Open judgments

- 24 dp of scroll travel before the bar hides, and any upward scroll showing it, are starting values to tune on the phone.
- "Filters (n)" counting the instant controls is a judgment: it says "something here is not default".
- Row counts in this spec are estimates; measure on the phone.
