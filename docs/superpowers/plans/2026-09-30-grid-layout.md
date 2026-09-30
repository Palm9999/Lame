# Grid Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. Per CLAUDE.md this plan lists tasks, interfaces and test names, not full code; the spec carries the design.

**Goal:** A two-row Grid top bar that slides away on scroll, a "View & filters" sheet for the rarely used controls, a leaner player cell and quieter number columns, with row height saved as a setting.

**Architecture:** `GridChrome.kt` (bar) and `ChromeScroll.kt` (a scroll-direction connection that never consumes scroll) replace the title bar, search field, three chip rows and summary in `GridScreen.kt`. `FilterSheet` grows an instant-apply section on top of today's draft/apply filters. Row density lives in the prefs JSON behind a small repository; `CellUi` gains a digits-only `display`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Robolectric Compose tests, Roborazzi, kotlinx.serialization prefs JSON.

**Spec:** `docs/superpowers/specs/2026-09-30-grid-layout-design.md`

## Global Constraints

- Branch `claude/dreamy-euler-phbdq1`; one PR; HANDOFF updates ride in it and after each task, so a `/clear` can resume.
- Kotlin builds fail on any warning (`-Werror`): no unused variables, imports or parameters.
- Widths and row heights are in `sp` (they grow with the user's font size); the bar's rows use a 48 dp minimum, not a fixed height.
- Prefs `formatVersion` stays 3; `gridDensity` absent or unknown reads as `COMFORTABLE`; one bad prefs entry never drops the others.
- Keep existing test tags `summary`, `search`, `grid`, `header:<COLUMN>`, `chip:filters`, `menu`; the moved controls keep `chip:teams`, `chip:roster`, `chip:snaps`, `chip:presets`, `chip:export` as row tags inside the sheet.
- `text` on `CellUi` keeps its units; only the Grid's on-screen cell uses `display`. CSV export, filters and TalkBack row descriptions keep `text`.
- Run Kotlin tests with `GRIDIRON_STATS_DB=etl/build/stats.db`; if that file is missing, rebuild it (command in CLAUDE.md). `RealDatabaseContractTest > scoring a full season for every player is fast` fails here on timing with or without changes: report it, don't fix it.
- The forecast, ingest, Compare, the Player page, packs and columns do not change.

## Review Focus

1. **Large font scale (200%).** The bar's chip row and position segments must stay reachable (scroll sideways) and the table rows must not clip: Task 5 test.
2. **A new request while the bar is hidden** (sort, filter, pack, preset applied) must show the bar again, or the user changes a filter and can't see it: Task 5 test.
3. **Empty, Loading and Failed states** keep their layouts and never hide the bar (nothing scrolls): Task 5 test.
4. **A saved density the app doesn't know**, and prefs with one bad entry among good ones, must read as the default without losing the rest: Task 1 tests.
5. **TalkBack.** A hidden bar is out of the accessibility tree; a row still reads "81.4%" with its unit even though the cell shows "81.4": Tasks 3 and 5 tests.

## File Structure

| File | Responsibility |
|---|---|
| `core/datastore/.../UserPrefs.kt`, `UserPrefsJson.kt`, new `RowDensity.kt` | `RowDensity`, `UserPrefs.gridDensity`, JSON |
| `core/data/.../GridDisplayRepository.kt` (new) | `density` flow and `setDensity` |
| `core/data/.../GridModels.kt`, `StatsRepository.kt`, `Sparkline.kt` | `CellUi.display`, percent header rule, sparkline removal |
| `feature/players/.../GridViewModel.kt` | `density` in state, `DensitySelected`, no sparklines, `viewChanges` count |
| `core/designsystem/.../Theme.kt` | `NumberStyle` tabular figures |
| `core/table/.../StatTable.kt` | optional zebra, row divider, sorted-column tint, header underline |
| `feature/players/.../GridScreen.kt` | player cell, numbers, density, calls `GridChrome` |
| `feature/players/.../GridChrome.kt`, `ChromeScroll.kt` (new) | two-row bar, search field, slide-away |
| `feature/players/.../FilterSheets.kt` | "View & filters" sheet |
| `app/.../GridironApplication.kt`, `GridironNavHost.kt` | wire `GridDisplayRepository` |

## Task 1: Density setting and repository (data layer)

**Files:** Create `RowDensity.kt`, `GridDisplayRepository.kt`, `GridDisplayRepositoryTest.kt`; modify `UserPrefs.kt`, `UserPrefsJson.kt`, `UserPrefsStoreTest.kt`.

**Interfaces:**
- Produces `enum class RowDensity { COMFORTABLE, COMPACT }` (in `:core:datastore`), `UserPrefs.gridDensity: RowDensity = COMFORTABLE`.
- Produces `class GridDisplayRepository(prefs: PrefsSource)` with `val density: Flow<RowDensity>` (distinct) and `suspend fun setDensity(density: RowDensity)`; a failed write throws, the caller shows it.
- The JSON DTO stores `gridDensity: String? = null`; decode with `RowDensity.entries.firstOrNull { it.name == value } ?: COMFORTABLE`; encode `name`.

- [ ] Write failing tests: `UserPrefsStoreTest` "gridDensity round-trips", "an absent gridDensity reads as COMFORTABLE", "an unknown gridDensity reads as COMFORTABLE and keeps presets and rosters" (Review Focus 4); `GridDisplayRepositoryTest` "density starts COMFORTABLE", "setDensity is read back through the flow", "the flow doesn't re-emit an unchanged value".
- [ ] Run `./gradlew :core:datastore:test :core:data:test`: expect FAIL. Implement. Run again: PASS.
- [ ] Update HANDOFF (task list) and commit `feat: save the Grid's row density`.

## Task 2: Cell display and sparkline removal (data layer)

**Files:** Modify `GridModels.kt`, `StatsRepository.kt`, `Sparkline.kt`, `StatsRepositoryTest.kt`.

**Interfaces:**
- `CellUi(val text: String, val heat: Float?, val display: String = text)`; for a column where `StatFormat.isPercent(column)` is true, `display = text.removeSuffix("%")`.
- Percent columns' `ColumnUi.header` is the metric's abbreviation with a trailing `%` guaranteed (append `" %"` only when it doesn't already end in `%`).
- Removes `StatsRepository.sparklines(page)` and the `Sparkline` data class if nothing but the Grid used them (grep `sparkline` in `core`, `feature`, `app` first). `:core:charts`' `Sparkline` composable stays if a test uses it.

- [ ] Write failing tests: `StatsRepositoryTest` "a percent column's cell shows digits only and keeps its % in text", "a non-percent column's display equals its text", "a percent column's header ends in %", "an NGS percentage column (already in points) is unchanged".
- [ ] Run `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew :core:data:test`: FAIL. Implement; delete the sparkline tests with the code. Run: PASS.
- [ ] Update HANDOFF; commit `feat: digits-only Grid cells; drop the Grid's trend query`.

## Task 3: Numbers and player cell

**Files:** Modify `Theme.kt` (`NumberStyle` gets `fontFeatureSettings = "tnum"`), `StatTable.kt`, `GridScreen.kt` (`PlayerTable`, `FrozenWidth`, `ColumnWidth`), `GridScreenTest.kt`.

**Interfaces:**
- `StatTable` gains `zebra: Boolean = true`, `rowDivider: Boolean = false`, `sortedColumnIndex: Int? = null` (tints that column and underlines its header via new slot-free parameters `sortedTint: Color = Color.Transparent`); defaults keep Compare's tables unchanged.
- `PlayerTable` passes `zebra = false`, `rowDivider = true`, the sorted index and `MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)`; frozen column `148.sp`, stat columns `72.sp`; row height `48.sp` or `40.sp` from a new `density: RowDensity` parameter; cells draw `CellUi.display`; header keeps the arrow and gains a 2 dp primary underline on the sorted column.
- Player cell: rank gutter `20.dp`; line 1 star + name; line 2 `row.detail`; the injury letter is an outlined pill at the cell's right edge; no sparkline.
- `rowDescription` keeps `CellUi.text` (units) and loses the sparkline sentence.

- [ ] Write failing tests: `GridScreenTest` "a percent cell shows digits and its row description keeps the %" (Review Focus 5), "the sorted column's header carries the underline tag", "rows are 40 sp in compact density", "the player cell shows no trend line", "an injury letter shows as a pill", "Compare's table is unchanged" (in `:feature:compare` tests: the existing suite passes).
- [ ] Run `:feature:players:test :feature:compare:test :core:table:test`: FAIL. Implement. Run: PASS.
- [ ] Update HANDOFF; commit `feat: quieter Grid numbers and a leaner player cell`.

## Task 4: View model: density and the change count

**Files:** Modify `GridViewModel.kt`, `GridViewModelTest.kt`; wire `GridDisplayRepository` in `GridRoute` (`presets`-style optional parameter `display: GridDisplayRepository? = null`), `GridironApplication.kt`, `GridironNavHost.kt`.

**Interfaces:**
- `GridEvent.DensitySelected(val density: RowDensity)`; `GridUiState.Ready.density: RowDensity = COMFORTABLE`; removes `sparklines` from state, the sparkline flow, and the post-page sparkline load.
- `GridUiState.Ready.viewChanges: Int` (a computed property): advanced filters + (teams non-empty) + (roster chosen) + (snap floor set) + (per game on) + (heat off) + (density compact). The Filters chip shows `Filters (n)` when `n > 0`.

- [ ] Write failing tests: `GridViewModelTest` "density flows into state and DensitySelected writes it", "a failed density write leaves the state on its old value and posts a message", "the sparkline query is never made", "viewChanges counts each non-default control once", "with no display repository the density stays comfortable".
- [ ] Run `:feature:players:test`: FAIL. Implement. Run: PASS; run `:app:testDebugUnitTest` for the wiring.
- [ ] Update HANDOFF; commit `feat: Grid density and change count in the view model`.

## Task 5: "View & filters" sheet

**Files:** Modify `FilterSheets.kt` (`FilterSheet` → instant section above the existing draft/apply section), `GridScreen.kt` (open/close, remove the moved chips from the old rows only in Task 6), `GridScreenTest.kt`.

**Interfaces:**
- `FilterSheet` gains the parameters it needs for the instant section: `state: GridUiState.Ready` and `onEvent: (GridEvent) -> Unit`; rows tagged `chip:presets` (opens presets, shows the count), `chip:teams` (opens `TeamSheet`), `chip:roster` (only when rosters exist), `chip:snaps` (hidden for K and D/ST), a per-game switch, a heat switch, a row-height segmented control (`density:comfortable`, `density:compact`), and `chip:export` (existing CSV flow, moved as is).
- The advanced filter block keeps `onDraftChanged`, `onApply`, `onDismiss` behavior unchanged.

- [ ] Write failing tests: `GridScreenTest` "the sheet lists Presets, Teams, Snaps, Per game, Heat, Row height and Export", "Roster shows only when a roster exists", "Snap floor is hidden on the K chip", "tapping Compact sends DensitySelected", "Per game and Heat switches send their events", "Export from the sheet shares the CSV", "Apply and the draft count still work" (the existing filter-sheet tests pass unchanged).
- [ ] Run `:feature:players:test`: FAIL. Implement. Run: PASS.
- [ ] Update HANDOFF; commit `feat: View & filters sheet`.

## Task 6: Two-row bar that slides away

**Files:** Create `GridChrome.kt`, `ChromeScroll.kt`; modify `GridScreen.kt` (`GridContent` uses them; remove `TitleBar`, the search field, the three `ChipRow`s, `Summary`, `RosterChip`, `SnapChip` and their inline export code), `GridScreenTest.kt`.

**Interfaces:**
- `class ChromeScrollState` (remember-able) with `val offsetPx: State<Float>` (0 shown, `-heightPx` hidden), `fun show()`, and `val connection: NestedScrollConnection` that hides after 24 dp of downward travel, shows on any upward scroll or at the top, and never consumes scroll (`onPreScroll` returns `Offset.Zero`).
- `@Composable fun GridChrome(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit, onOpenWeeks: () -> Unit, onOpenFilters: () -> Unit, onEditProfiles: () -> Unit, menu: List<Pair<String, (Int) -> Unit>>, scroll: ChromeScrollState, modifier: Modifier)`: row 1 title, profile chip, weeks, season, search icon (filled while a name search is active), `☰` (`testTag("menu")`); row 2 pack dropdown (`chip:pack`), All/QB/RB/WR/TE segments plus a "More ▾" segment for FLEX, K and D/ST (`chip:position:<name>`), and the `chip:filters` chip with a dot for an active name search; then the 28 dp summary (`testTag("summary")`, text as today plus "Data through week N").
- The search field (`testTag("search")`) opens over row 2 from the icon; closing does not clear the query; its ✕ clears.
- A new request (`state.request` changes) calls `scroll.show()`; the hidden bar is `Modifier.semantics { invisibleToUser() }`.

- [ ] Write failing tests: `GridScreenTest` "the bar shows the pack chip, position segments and Filters (n)", "More opens FLEX, K and D/ST and picking K shows the Kicking pack" (and "the pack dropdown lists only that chip's packs"), "search opens from the icon, keeps the query when closed and shows the dot", "scrolling down hides the bar and scrolling up shows it", "reaching the top shows it", "a new sort shows a hidden bar" (Review Focus 2), "the empty, Loading and Failed states keep their layouts" (Review Focus 3), "at 200% font the bar's controls are still reachable" (Review Focus 1), "a hidden bar is not in the accessibility tree" (Review Focus 5); `ChromeScrollStateTest` (plain unit): "hides after 24 dp down", "any up scroll shows", "never consumes".
- [ ] Run `:feature:players:test`: FAIL. Implement. Run: PASS.
- [ ] Update HANDOFF; commit `feat: two-row Grid bar that slides away`.

## Task 7: Screenshots, docs and full gates

**Files:** Roborazzi images under `feature/players`, `CLAUDE.md` (Known Gaps: Grid layout line), `docs/ARCHITECTURE.md` (Grid paragraph), `docs/superpowers/HANDOFF.md`.

- [ ] Run `./gradlew :feature:players:recordRoborazziDebug`, read the new images (bar shown, bar hidden, compact rows, percent columns) and commit them.
- [ ] Run `GRIDIRON_STATS_DB=etl/build/stats.db ./gradlew test --continue` (only the known timing test may fail) and `./gradlew :app:assembleRelease`.
- [ ] CLAUDE.md and ARCHITECTURE: describe the two-row bar, the View & filters sheet, `gridDensity` and the removed trend line. HANDOFF: Grid layout built; phone checks (rows visible with the bar shown and hidden, scroll feel and the 24 dp threshold, compact rows, a percent column, the sheet's controls); next is season rollups; list deferred minors.
- [ ] Commit `docs: Grid layout in the docs`. The final review, the fix pass, deleting the spec and plan and opening the PR follow.

## Self-Review

- **Spec coverage:** bar and slide-away (Task 6), sheet (Task 5), density setting (Tasks 1 and 4), player cell and numbers (Task 3), digits-only cells and the trend removal (Task 2), tests (each task), screenshots and gates (Task 7).
- **Spec refinement:** the spec says the header reads "CATCH %"; the plan keeps each metric's abbreviation and appends " %" only when it doesn't already end in `%` (most already do), so no header is renamed needlessly.
- **Placeholders:** none. **Names:** `RowDensity`, `GridDisplayRepository`, `CellUi.display`, `GridEvent.DensitySelected`, `viewChanges`, `ChromeScrollState`, `GridChrome` are defined where produced and used later.
