# Saved Grid presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native) or superpowers:subagent-driven-development. Steps use checkbox syntax. Short plan per `CLAUDE.md`: tasks, interfaces and test names; the spec carries the design.

**Goal:** Save the Grid view under a name and restore it in one tap, following the season open now.

**Architecture:** `GridPreset` (string-typed, in `:core:datastore`) is stored in the prefs JSON beside rosters. `GridPresetRepository` in `:core:data` saves, edits and resolves presets into a `GridRequest`. `GridViewModel` and a Presets sheet in `:feature:players` drive it.

**Tech Stack:** Kotlin, kotlinx.serialization, DataStore, Compose, Roborazzi.

**Spec:** `docs/superpowers/specs/2026-09-29-grid-presets-design.md`

## Global Constraints

- `FORMAT_VERSION` stays 3; `gridPresets` is an optional field with an empty default.
- Name: trimmed, 1..40 characters, unique ignoring case. At most 30 presets. `LastN.n` in `1..WeekRange.MAX_WEEK`.
- Stored names are enum names (`StatPack`, `StatColumn`, `Direction`, `PositionFilter`); filter values are in stored units.
- A preset never stores the season, scoring profile, roster or name search.
- Bad stored entries are dropped one by one, never the whole file.
- The limit and the name check run inside one `prefs.update`.

## Review Focus

- Applying `LastN(8)` in week 3 gives weeks 1–3, not an invalid range (Task 2).
- A preset saved when a pack or column exists but later renamed or removed shows greyed, not a crash (Task 2, 4).
- Two quick saves at 29 presets end at 30, not 31 (Task 2).
- A file written before this feature reads as no presets (Task 1).
- Applying a preset while the filter editor is open discards the draft, not leaves it stale (Task 3).

## File Structure

- Modify `core/datastore/.../UserPrefs.kt`, `UserPrefsJson.kt`: types, DTOs, read/write.
- Create `core/data/.../GridPresetRepository.kt`: save/edit/resolve.
- Modify `feature/players/.../GridViewModel.kt`, `GridScreen.kt`; create `PresetsSheet.kt`.
- Modify `app/.../GridironApplication.kt`, `GridironNavHost.kt`: wire the repository.
- Docs: `docs/ARCHITECTURE.md`, `CLAUDE.md`, `docs/superpowers/HANDOFF.md`.

---

### Task 1: Stored shape

**Files:** Modify `UserPrefs.kt`, `UserPrefsJson.kt`; test `core/datastore/src/test/.../UserPrefsStoreTest.kt`.

**Interfaces:**
- Produces: `GridPreset(id, name, packId: String, sort: String, direction: String, position: String, perGame: Boolean, teams: Set<String>, minSnapShare: Double?, filters: List<PresetFilter>, weeks: PresetWeeks)`; `PresetFilter(column: String, kind: PresetFilterKind, a: Double, b: Double? = null)`; `enum PresetFilterKind { AT_LEAST, AT_MOST, GREATER_THAN, LESS_THAN, BETWEEN }`; `sealed interface PresetWeeks { WholeSeason; LastN(n) }`; `UserPrefs.gridPresets: List<GridPreset> = emptyList()`; `const val MAX_PRESETS = 30`.
- `init` validation per the spec; DTOs mirror the domain with plain strings.

- [ ] Tests first: `gridPresets round trip`, `file without gridPresets reads as none`, `bad entries are dropped and the rest kept` (blank name, `LastN(0)`, BETWEEN without `b`), `duplicate names keep the first`, `entries past the 30th are dropped`.
- [ ] Run `./gradlew :core:datastore:test`: fail.
- [ ] Implement types, DTOs, `toDomain` filtering (`orNull`, `distinctBy` lowercase name, `take(MAX_PRESETS)`), `toDto`.
- [ ] Run again: pass. Commit.

### Task 2: Repository

**Files:** Create `GridPresetRepository.kt`; test `core/data/src/test/.../GridPresetRepositoryTest.kt` (model on `RosterRepositoryTest`).

**Interfaces:**
- Consumes: `PrefsSource.update`, Task 1 types, `GridRequest`, `SeasonInfo.defaultWeeks`.
- Produces:
  - `GridPresetRepository(prefs: PrefsSource, newId: () -> String = { UUID… })`
  - `val presets: Flow<ImmutableList<GridPreset>>`
  - `suspend fun save(name: String, request: GridRequest, weeks: PresetWeeks): GridPreset`, throwing `PresetNameTaken(existingId)` or `PresetLimitReached`
  - `suspend fun overwrite(id, request, weeks)` (keeps id and name); `rename(id, name)` (same name rules; `PresetNameTaken` if used by another); `delete(id)`; `restore(preset)` (for undo, re-inserts at the end if there is room)
  - `fun resolve(preset: GridPreset, base: GridRequest): Resolved` where `Resolved = Ready(request) | Unavailable(reason)`
  - `fun weeksRule(request: GridRequest): PresetWeeks`

- [ ] Tests: `save stores the view and trims the name`, `save with a used name (any case) throws PresetNameTaken`, `overwrite keeps id and name`, `rename to a used name throws`, `delete removes`, `restore re-adds`, `31st save throws PresetLimitReached`, `two saves at 29 end at 30`, `resolve WholeSeason uses defaultWeeks`, `resolve LastN clamps to week 1`, `resolve LastN follows the played weeks of a short season`, `resolve clears name search and keeps scoring, roster and season`, `resolve unknown pack is Unavailable`, `resolve unknown filter column is Unavailable`, `resolve sort outside pack is Unavailable`, `resolve position the pack does not offer is Unavailable`, `weeksRule` cases (whole season, last N ending at the last played week, other range falls back to WholeSeason).
- [ ] Run `./gradlew :core:data:test --tests "*GridPresetRepositoryTest"`: fail; implement; pass. Commit.

### Task 3: ViewModel

**Files:** Modify `GridViewModel.kt`; test `GridViewModelTest.kt`.

**Interfaces:**
- Consumes: `GridPresetRepository` (new optional constructor param and `factory` arg, default an empty fake so existing tests compile).
- Produces: `GridState.presets: ImmutableList<PresetRow(preset, summary, unavailable: String?)>`, `presetSheet: PresetSheet?` (`List | Save | Rename(id) | ConfirmReplace(name, id)`); events `PresetsOpened`, `PresetsClosed`, `PresetApplied(id)`, `PresetSaveRequested`, `PresetSaved(name, weeks)`, `PresetReplaceConfirmed`, `PresetRenamed(id, name)`, `PresetDeleted(id)`, `PresetDeleteUndone`.

- [ ] Tests: `save then apply restores the same request on another season`, `duplicate name asks to replace, yes overwrites`, `unavailable preset does not apply`, `applying a preset discards the open filter draft`, `delete then undo restores it`, `save is refused with a message at 30`, `summary reads "WR · FTN Receiving · last 4 wks · 2 filters"`.
- [ ] Implement in the existing `MutableStateFlow` style; failed writes go to `message`. Run `./gradlew :feature:players:test`. Commit.

### Task 4: Sheet, wiring, docs

**Files:** Create `PresetsSheet.kt`; modify `GridScreen.kt` (chip `chip:presets` in the filter row), `GridScreenTest.kt`, `GridironApplication.kt`, `GridironNavHost.kt`, `ARCHITECTURE.md`, `CLAUDE.md`, `HANDOFF.md`.

- [ ] Sheet: list rows with summary; tap applies; unavailable rows greyed with reason; long-press menu Rename/Delete (Undo snackbar); "Save current view…" dialog (name, Whole season / Last N stepper defaulting to `weeksRule`); replace prompt; empty state text; save disabled with a note at 30. Test tags `presets:row:<id>`, `presets:save`.
- [ ] Wire `GridPresetRepository(prefs)` in `GridironApplication` and pass it through `GridironNavHost` to `GridScreen`.
- [ ] Tests in `GridScreenTest`: `presets chip opens the sheet`, `empty sheet shows the hint`, `unavailable row is not clickable`. Roborazzi: `presetsSheet` (three presets, one unavailable) and `presetsSheetEmpty`; record with `./gradlew :feature:players:recordRoborazziDebug`.
- [ ] Docs: ARCHITECTURE (datastore shape, repository, sheet); remove the "Saved Grid presets not yet implemented" gap from CLAUDE.md (keep the roster note); HANDOFF: presets built, next is season rollups, add the phone check "save a preset, restart, apply it on another season".
- [ ] Full check: `./gradlew test` (with `GRIDIRON_STATS_DB=etl/build/stats.db`; the known timing failure in `RealDatabaseContractTest` is not ours) and `./gradlew :app:assembleRelease`. Commit, delete the spec and plan in a final commit per the working rules, push, open the PR.

## Self-review

- Spec coverage: model and validation (1), repository, resolve, weeksRule, limits (2), UI behaviour and draft discard (3, 4), errors (2, 3), tests, docs (4). Out-of-scope items are not planned.
- Types match across tasks: `PresetWeeks`, `PresetNameTaken`, `PresetLimitReached`, `Resolved`.
