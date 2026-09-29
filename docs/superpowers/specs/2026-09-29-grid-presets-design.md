# Saved Grid presets

## Purpose

Rebuilding a favourite Grid view (pack, sort, position, filters) by hand each visit is slow. A preset saves the view under a name and restores it in one tap, following the season you have open, so "Buy-low WRs, last 4 weeks" stays current all year.

## Decisions (from the user)

- A preset captures the **full view**: pack, sort column, direction, position chip, per-game, teams, snap-share floor, advanced filters, and a **weeks rule**.
- It does not capture the season, scoring profile, roster or name search.
- The weeks rule is `WholeSeason` or `LastN(n)`; applying a preset uses the season and played weeks open now.
- UI: a **Presets** chip in the Grid opens a sheet (apply, save current, rename, delete).
- Storage: the `:core:datastore` prefs JSON, like rosters. `user.db` is not built.

## Model

`GridPreset` lives in `:core:datastore` (which sees only `:core:model`), so every field is a plain string or number, as the other stored shapes are:

```
GridPreset(id, name, packId, sort, direction, position, perGame, teams,
           minSnapShare, filters: List<PresetFilter>, weeks: PresetWeeks)
PresetFilter(column, kind, a, b?)        // kind: AT_LEAST, AT_MOST, GREATER_THAN, LESS_THAN, BETWEEN
PresetWeeks = WholeSeason | LastN(n)     // n in 1..WeekRange.MAX_WEEK
```

Stored names are the enum names (`StatPack`, `StatColumn`, `Direction`, `PositionFilter`). Filter values are stored in stored units, as `GridRequest.filters` holds them.

`UserPrefs` gains `gridPresets: List<GridPreset> = emptyList()`. `UserPrefsDto` gains `gridPresets` with an empty default, so `FORMAT_VERSION` stays 3 (no migration; older files read as no presets, and `ignoreUnknownKeys` lets an older build read a newer file).

Validation (`init`): non-blank id; name trimmed, 1..40 characters; `LastN.n` in range; a `BETWEEN` filter has `b`. `UserPrefs.toString` needs no change.

Reading: an entry that fails validation is dropped, not the file (as rosters do). Names are unique ignoring case; on read the first wins. At most 30 presets; extras past the 30th are dropped on read and refused on save.

## Repository (`:core:data`)

`GridPresetRepository(prefs: PrefsSource, newId)`, modelled on `RosterRepository`:

- `presets: Flow<ImmutableList<GridPreset>>`
- `save(name, request: GridRequest, weeks: PresetWeeks)`: returns the new preset; a name already in use (ignoring case) fails with a typed error the ViewModel turns into an overwrite prompt; `overwrite(id, request, weeks)` replaces the view but keeps id and name
- `rename(id, name)`, `delete(id)`
- `resolve(preset, base: GridRequest): Resolved`, pure, where `Resolved` is `Ready(GridRequest)` or `Unavailable(reason)`

`resolve` builds a request from `base` (which carries the season, scoring profile and roster the user has open): it replaces pack, sort, direction, positions, per-game, teams, snap floor and filters, clears the name search, and sets `weeks`: `WholeSeason` gives `season.defaultWeeks`; `LastN(n)` gives `WeekRange(max(1, last - n + 1), last)` where `last = season.defaultWeeks.last`. It is `Unavailable` when the pack, sort column or any filter column is not a known enum name, when the sort column is not in the pack, or when the position chip does not offer the pack. Teams that no longer exist are kept: they match nothing, which the user sees.

`weeksRule(request)` derives the default rule for the save dialog: `WholeSeason` when `request.weeks == season.defaultWeeks`, else `LastN(request.weeks.size)` when `request.weeks.last == season.defaultWeeks.last`, else `WholeSeason`.

## UI (`:feature:players`)

- A **Presets** chip in the Grid's filter row opens a bottom sheet listing presets by name with a one-line summary ("WR · FTN Receiving · last 4 wks · 2 filters"). Empty state: "Save the view you have open to come back to it."
- Tap applies (`Ready`) and closes the sheet; an `Unavailable` row is greyed with its reason and cannot be applied, but can be renamed or deleted.
- **Save current view…** opens a dialog: a name field and a weeks choice (Whole season / Last N weeks with a number stepper), defaulting to `weeksRule`. A duplicate name asks "Replace it?" and overwrites on yes. At 30 presets the action is disabled with a note.
- Long-press a row for Rename and Delete; delete needs no confirm and shows an Undo snackbar.
- `GridViewModel` gets the presets flow, `savePreset`, `applyPreset`, `renamePreset`, `deletePreset` and the sheet and dialog state, kept in the same `MutableStateFlow` style as its neighbours. Applying a preset while a draft filter editor is open discards the draft.

## Errors

- Unreadable prefs keep today's behaviour (reset with a notice).
- A failed write surfaces through the existing `message` flow.
- Two quick saves cannot exceed 30, because the limit and the name check run inside the single `prefs.update`.

## Tests

- `:core:datastore`: JSON round trip; an older file without the key reads as none; a bad entry (blank name, `LastN(0)`, `BETWEEN` without `b`) is dropped and the rest kept; duplicate names keep the first; the 31st entry is dropped.
- `:core:data` `GridPresetRepositoryTest`: save, duplicate name, overwrite keeps id and name, rename, delete, limit of 30; `resolve` for `WholeSeason` and `LastN` (including `n` larger than the played weeks, and a season with a short `lastWeek`); `Unavailable` for an unknown pack, unknown column, sort outside the pack; the name search is cleared and roster and scoring are kept; `weeksRule` cases.
- `GridViewModelTest`: save then apply restores the same request on another season; overwrite prompt; unavailable row does not apply; delete then undo.
- Roborazzi: the sheet with three presets and one unavailable, and the empty sheet.

## Docs (same PR)

Update `docs/ARCHITECTURE.md` (datastore shape, repository, Grid sheet), remove the "Saved Grid presets not yet implemented" gap from `CLAUDE.md`, and update `docs/superpowers/HANDOFF.md` (next: season rollups; add an open check on the phone: save a preset, restart, apply it).

## Out of scope

Reordering presets, sharing or exporting them, presets for Compare or Projections, capturing the scoring profile or roster, and a `user.db`.
