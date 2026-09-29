package dev.gridiron.feature.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.PresetLimitReached
import dev.gridiron.core.data.PresetNameTaken
import dev.gridiron.core.data.Resolved
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.Sparkline
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.data.describeSlot
import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.MAX_PRESETS
import dev.gridiron.core.datastore.PresetWeeks
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the user can do on the Grid. The screen only ever emits these. */
sealed interface GridEvent {
    data class SeasonSelected(val season: Int) : GridEvent
    data class WeeksChanged(val weeks: WeekRange) : GridEvent
    data class PackSelected(val pack: StatPack) : GridEvent
    data class PositionsSelected(val positions: PositionFilter) : GridEvent
    data class SortBy(val column: StatColumn) : GridEvent
    data class NameChanged(val name: String) : GridEvent
    data object PerGameToggled : GridEvent
    data object HeatToggled : GridEvent
    data class ProfileSelected(val id: String) : GridEvent
    data class AddToCompare(val playerId: String, val name: String) : GridEvent
    data class RemoveFromTray(val slot: CompareSlot) : GridEvent
    /** Opens the season/weeks sheet for a tray chip. */
    data class EditTraySlot(val slot: CompareSlot) : GridEvent
    data class ReplaceTraySlot(val old: CompareSlot, val new: CompareSlot) : GridEvent
    data object TraySlotEditClosed : GridEvent
    data object MessageShown : GridEvent
    data class TeamsSelected(val teams: Set<String>) : GridEvent
    data class MinSnapShareSelected(val share: Double?) : GridEvent
    /** Commits the filter sheet's complete rows. */
    data class FiltersApplied(val filters: List<Filter>) : GridEvent
    /** The sheet's complete rows changed; counts them without touching the Grid. */
    data class FilterDraftChanged(val filters: List<Filter>) : GridEvent
    data object FilterSheetClosed : GridEvent
    /** Shows only roster [id]'s players; null shows everyone. */
    data class RosterSelected(val id: String?) : GridEvent

    /** Opens the presets sheet. */
    data object PresetsOpened : GridEvent
    data object PresetsClosed : GridEvent
    /** Opens the save dialog, its weeks rule defaulted from the view. */
    data object PresetSaveRequested : GridEvent
    data class PresetSaved(val name: String, val weeks: PresetWeeks) : GridEvent
    /** Yes to "replace it?" after saving under a used name. */
    data object PresetReplaceConfirmed : GridEvent
    data class PresetRenameRequested(val id: String) : GridEvent
    data class PresetRenamed(val id: String, val name: String) : GridEvent
    /** Closes the save, rename or replace dialog and returns to the list. */
    data object PresetDialogDismissed : GridEvent
    data class PresetApplied(val id: String) : GridEvent
    data class PresetDeleted(val id: String) : GridEvent
    data object PresetDeleteUndone : GridEvent
    /** The undo snackbar timed out. */
    data object PresetDeleteDismissed : GridEvent
}

/** What the presets sheet shows: the list, or a dialog over it. */
sealed interface PresetSheet {
    data object Listing : PresetSheet
    data class Saving(val weeks: PresetWeeks) : PresetSheet
    data class Renaming(val id: String, val name: String) : PresetSheet
    data class ConfirmReplace(val name: String, val id: String, val weeks: PresetWeeks) : PresetSheet
}

/** A saved preset as the sheet lists it; [unavailable] is why it can't be applied right now, or null. */
data class PresetRow(val preset: GridPreset, val summary: String, val unavailable: String?)

sealed interface GridUiState {
    data object Loading : GridUiState

    data class Failed(val message: String) : GridUiState

    /**
     * @property page The last completed page. It can lag [request] while a new
     *   query runs, so the old table stays up instead of flashing empty.
     */
    data class Ready(
        val catalog: Catalog,
        val request: GridRequest,
        val heat: Boolean,
        val page: GridPage?,
        val error: String?,
        val profiles: ImmutableList<ScoringProfile> = ScoringPresets.all.toImmutableList(),
        val tray: ImmutableList<TraySlotUi> = persistentListOf(),
        /** A one-off message for the snackbar; the screen sends [GridEvent.MessageShown] after showing it. */
        val message: String? = null,
        /** The tray slot the season/weeks sheet is editing, or null when the sheet is closed. */
        val editingSlot: CompareSlot? = null,
        /** Sparklines for [page]'s rows, by player id; empty until they load or if they fail. */
        val sparklines: ImmutableMap<String, Sparkline> = persistentMapOf(),
        /** The open filter sheet's match count; null when the sheet is closed. */
        val draftCount: DraftCount? = null,
        /** ESPN injury letters (Q, D, O, IR, …) by player id; empty when there is no live data. */
        val badges: ImmutableMap<String, String> = persistentMapOf(),
        val rosters: ImmutableList<Roster> = persistentListOf(),
        /** The roster the Grid is narrowed to, or null for everyone. */
        val rosterId: String? = null,
        /** Everyone on any roster, so the table can mark them. */
        val rostered: ImmutableSet<String> = persistentSetOf(),
        /** False when no presets repository is wired, so the screen hides the chip. */
        val presetsEnabled: Boolean = false,
        val presets: ImmutableList<PresetRow> = persistentListOf(),
        /** The open presets sheet, or null when closed. */
        val presetSheet: PresetSheet? = null,
        /** A preset just deleted, offered for undo until the snackbar goes. */
        val deletedPreset: GridPreset? = null,
    ) : GridUiState {
        val presetsFull: Boolean get() = presets.size >= MAX_PRESETS

        val refreshing: Boolean get() = page?.request != request && error == null
    }
}

/** The filter sheet's live "N players match". */
sealed interface DraftCount {
    data object Counting : DraftCount
    data class Matches(val count: Int) : DraftCount
    data object Unavailable : DraftCount
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GridViewModel(
    private val repository: StatsRepository,
    private val scoring: ScoringRepository,
    private val tray: CompareTrayRepository,
    /** Coalesces bursts (typing, dragging the week slider) into one query. */
    debounceMillis: Long = 150,
    /** Coalesces filter-sheet typing into one count. */
    countDebounceMillis: Long = 250,
    /** Live injury letters by player id, from ESPN; re-emits after every live refresh. */
    badges: Flow<Map<String, String>> = flowOf(emptyMap()),
    /** The user's fantasy teams, from [dev.gridiron.core.data.RosterRepository]. */
    rosters: Flow<List<Roster>> = flowOf(emptyList()),
    /** Saved views; null turns the presets off. */
    private val presets: GridPresetRepository? = null,
) : ViewModel() {

    private sealed interface CatalogLoad {
        data object Loading : CatalogLoad
        data class Loaded(val catalog: Catalog, val version: Long) : CatalogLoad
        data class Failed(val message: String) : CatalogLoad
    }

    private val catalogLoad = MutableStateFlow<CatalogLoad>(CatalogLoad.Loading)
    private val request = MutableStateFlow<GridRequest?>(null)
    private val heat = MutableStateFlow(true)
    private val lastPage = MutableStateFlow<GridPage?>(null)
    /** A failed query, remembered with its request so a later success clears it. */
    private val pageError = MutableStateFlow<Pair<GridRequest, String>?>(null)

    private val catalog: Catalog?
        get() = (catalogLoad.value as? CatalogLoad.Loaded)?.catalog

    private val message = MutableStateFlow<String?>(null)
    private val editingSlot = MutableStateFlow<CompareSlot?>(null)
    /** Sparklines tagged with the page they were computed for, so a stale set is never shown. */
    private val sparklines = MutableStateFlow<Pair<GridPage, Map<String, Sparkline>>?>(null)
    private val draft = MutableStateFlow<List<Filter>?>(null)
    /** Count results tagged with the draft they were computed for, so a stale count never resurfaces after the sheet closes or a newer draft supersedes it. */
    private val draftCount = MutableStateFlow<Pair<List<Filter>, DraftCount>?>(null)
    private val rosterId = MutableStateFlow<String?>(null)
    /** The chosen roster's players, kept so a request created later starts narrowed too. */
    private val onlyPlayers = MutableStateFlow<Set<String>?>(null)
    private val rosterList = rosters.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val presetList = (presets?.presets ?: flowOf(persistentListOf<GridPreset>()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())
    private val presetSheet = MutableStateFlow<PresetSheet?>(null)
    private val deletedPreset = MutableStateFlow<GridPreset?>(null)

    private val trayUi: Flow<ImmutableList<TraySlotUi>> =
        combine(tray.slots, catalogLoad) { slots, load -> slots to (load as? CatalogLoad.Loaded)?.catalog }
            .mapLatest { (slots, catalog) ->
                if (catalog == null) return@mapLatest persistentListOf()
                val names = try {
                    repository.players(slots.map { it.playerId })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyMap()
                }
                slots.map { TraySlotUi(it, names[it.playerId]?.name ?: it.playerId, describeSlot(it, catalog)) }.toImmutableList()
            }

    val state: StateFlow<GridUiState> =
        combine(catalogLoad, request, heat, lastPage, pageError) { load, r, h, page, err ->
            when (load) {
                CatalogLoad.Loading -> GridUiState.Loading
                is CatalogLoad.Failed -> GridUiState.Failed(load.message)
                is CatalogLoad.Loaded ->
                    if (r == null) GridUiState.Loading
                    else GridUiState.Ready(load.catalog, r, h, page, err?.takeIf { it.first == r }?.second)
            }
        }.combine(
            combine(scoring.profiles, trayUi, message, editingSlot, combine(sparklines, draft, draftCount, badges, ::Lines), ::Extras),
        ) { base, extras ->
            if (base is GridUiState.Ready) {
                val lines = extras.lines.sparklines?.takeIf { (page, _) -> page == base.page }?.second.orEmpty()
                // A count is only shown when it was computed for the draft the sheet
                // currently holds; a stale in-flight or completed count is ignored
                // rather than resurrecting after the draft moved on or the sheet closed.
                val draftCount = when {
                    extras.lines.draft == null -> null
                    extras.lines.count?.first == extras.lines.draft -> extras.lines.count.second
                    else -> DraftCount.Counting
                }
                base.copy(
                    profiles = extras.profiles,
                    tray = extras.tray,
                    message = extras.message,
                    editingSlot = extras.editingSlot,
                    sparklines = lines.toImmutableMap(),
                    draftCount = draftCount,
                    badges = extras.lines.badges.toImmutableMap(),
                )
            } else {
                base
            }
        }.combine(combine(rosterList, rosterId, ::Pair)) { base, (list, id) ->
            if (base is GridUiState.Ready) {
                base.copy(
                    rosters = list.toImmutableList(),
                    rosterId = id,
                    rostered = list.flatMap { it.playerIds }.toImmutableSet(),
                )
            } else {
                base
            }
        }.combine(combine(presetList, presetSheet, deletedPreset, ::Triple)) { base, (list, sheet, deleted) ->
            if (base is GridUiState.Ready && presets != null) {
                base.copy(
                    presetsEnabled = true,
                    presets = list.map { preset ->
                        val reason = (presets.resolve(preset, base.request) as? Resolved.Unavailable)?.reason
                        PresetRow(preset, presetSummary(preset), reason)
                    }.toImmutableList(),
                    presetSheet = sheet,
                    deletedPreset = deleted,
                )
            } else {
                base
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GridUiState.Loading)

    private data class Extras(
        val profiles: ImmutableList<ScoringProfile>,
        val tray: ImmutableList<TraySlotUi>,
        val message: String?,
        val editingSlot: CompareSlot?,
        val lines: Lines,
    )

    /** The sparkline, draft-count and badge sources, combined once so each carries its own staleness tag. */
    private data class Lines(
        val sparklines: Pair<GridPage, Map<String, Sparkline>>?,
        val draft: List<Filter>?,
        val count: Pair<List<Filter>, DraftCount>?,
        val badges: Map<String, String>,
    )

    init {
        viewModelScope.launch {
            repository.dataVersion.collectLatest { version ->
                val c = try {
                    repository.catalog()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    catalogLoad.value = CatalogLoad.Failed("Couldn't open the stats database: ${e.message}")
                    return@collectLatest
                }
                catalogLoad.value = CatalogLoad.Loaded(c, version)
                val current = request.value
                request.value = if (current != null) {
                    rebase(current, c)
                } else {
                    GridRequest(
                        c.latest,
                        c.latest.defaultWeeks,
                        StatPack.OPPORTUNITY,
                        scoring = scoring.active.first(),
                        onlyPlayers = onlyPlayers.value,
                    )
                }
            }
        }
        viewModelScope.launch {
            // Pairs each request with the catalog it runs against, so a new
            // data version re-runs the page even when the request is unchanged.
            combine(request.filterNotNull(), catalogLoad.filterIsInstance<CatalogLoad.Loaded>(), ::Pair)
                .debounce(debounceMillis)
                .mapLatest { (r, load) ->
                    try {
                        lastPage.value = repository.grid(r, load.catalog)
                        pageError.value = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        pageError.value = r to (e.message ?: e::class.simpleName.orEmpty())
                    }
                }
                .collect()
        }
        viewModelScope.launch {
            // After each page, never before it: the table must not wait on its sparklines.
            lastPage.filterNotNull()
                .mapLatest { page ->
                    val lines = try {
                        repository.sparklines(page)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                    sparklines.value = page to lines
                }
                .collect()
        }
        viewModelScope.launch {
            draft.debounce(countDebounceMillis)
                .mapLatest { filters ->
                    val r = request.value
                    if (filters == null || r == null) return@mapLatest
                    val result = try {
                        DraftCount.Matches(repository.count(r.copy(filters = filters)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DraftCount.Unavailable
                    }
                    // Tagged with the draft it was computed for: if this coroutine is slow
                    // to cancel (debounce itself delays the cancelling emission) and the
                    // draft has since moved on or the sheet closed, the state combine
                    // above ignores this write instead of showing a stale count.
                    draftCount.value = filters to result
                }
                .collect()
        }
        viewModelScope.launch {
            combine(rosterList, rosterId) { list, id ->
                val roster = id?.let { wanted -> list.firstOrNull { it.id == wanted } }
                // A deleted roster falls back to everyone.
                if (id != null && roster == null) rosterId.value = null
                roster?.playerIds?.toSet()
            }.collect { ids ->
                onlyPlayers.value = ids
                request.update { it?.copy(onlyPlayers = ids) }
            }
        }
        viewModelScope.launch {
            scoring.active.collect { profile -> request.update { it?.copy(scoring = profile) } }
        }
        viewModelScope.launch {
            scoring.resetNotice.filter { it }.collect {
                message.value = "Saved scoring profiles couldn't be read, so they were reset."
                scoring.dismissResetNotice()
            }
        }
    }

    fun onEvent(event: GridEvent) {
        when (event) {
            GridEvent.HeatToggled -> {
                heat.update { !it }
                return
            }
            is GridEvent.ProfileSelected -> {
                viewModelScope.launch { scoring.setActive(event.id) }
                return
            }
            is GridEvent.AddToCompare -> {
                val r = request.value ?: return
                viewModelScope.launch {
                    // No snackbar on a successful add: the screen's haptic and the
                    // new tray chip confirm it, and a snackbar would sit over the
                    // tray's Compare button for its whole duration.
                    when (tray.add(CompareSlot(event.playerId, r.season.season, r.weeks))) {
                        CompareTrayRepository.AddResult.ADDED -> Unit
                        CompareTrayRepository.AddResult.ALREADY_THERE -> message.value = "${event.name} is already in compare"
                        CompareTrayRepository.AddResult.FULL ->
                            message.value = "Compare holds ${CompareTrayRepository.CAPACITY} players. Remove one first."
                    }
                }
                return
            }
            is GridEvent.RemoveFromTray -> {
                viewModelScope.launch { tray.remove(event.slot) }
                return
            }
            is GridEvent.EditTraySlot -> {
                editingSlot.value = event.slot
                return
            }
            is GridEvent.ReplaceTraySlot -> {
                // The sheet follows the change straight away, so a quick second
                // change replaces the new slot rather than the old one...
                editingSlot.value = event.new
                viewModelScope.launch {
                    if (!tray.replace(event.old, event.new)) {
                        message.value = "That player and range is already in compare"
                        // ...and on a rejection it closes rather than keep editing
                        // a slot that never made it into the tray.
                        editingSlot.update { if (it == event.new) null else it }
                    }
                }
                return
            }
            GridEvent.TraySlotEditClosed -> {
                editingSlot.value = null
                return
            }
            GridEvent.MessageShown -> {
                message.value = null
                return
            }
            is GridEvent.FilterDraftChanged -> {
                draft.value = event.filters
                return
            }
            GridEvent.FilterSheetClosed -> {
                draft.value = null
                draftCount.value = null
                return
            }
            is GridEvent.FiltersApplied -> {
                draft.value = null
                draftCount.value = null
            }
            is GridEvent.RosterSelected -> {
                rosterId.value = event.id
                return
            }
            GridEvent.PresetsOpened,
            GridEvent.PresetsClosed,
            GridEvent.PresetSaveRequested,
            GridEvent.PresetReplaceConfirmed,
            GridEvent.PresetDialogDismissed,
            GridEvent.PresetDeleteUndone,
            GridEvent.PresetDeleteDismissed,
            is GridEvent.PresetSaved,
            is GridEvent.PresetRenameRequested,
            is GridEvent.PresetRenamed,
            is GridEvent.PresetApplied,
            is GridEvent.PresetDeleted -> {
                onPresetEvent(event)
                return
            }
            else -> Unit
        }
        val c = catalog ?: return
        request.update { current -> current?.let { reduce(it, event, c) } }
    }

    private fun onPresetEvent(event: GridEvent) {
        val repo = presets ?: return
        val current = request.value
        when (event) {
            GridEvent.PresetsOpened -> presetSheet.value = PresetSheet.Listing
            GridEvent.PresetsClosed -> presetSheet.value = null
            GridEvent.PresetDialogDismissed -> presetSheet.value = PresetSheet.Listing
            GridEvent.PresetSaveRequested -> if (current != null) presetSheet.value = PresetSheet.Saving(repo.weeksRule(current))
            is GridEvent.PresetSaved -> if (current != null) {
                viewModelScope.launch {
                    try {
                        repo.save(event.name, current, event.weeks)
                        presetSheet.value = PresetSheet.Listing
                    } catch (e: PresetNameTaken) {
                        presetSheet.value = PresetSheet.ConfirmReplace(event.name.trim(), e.existingId, event.weeks)
                    } catch (e: PresetLimitReached) {
                        message.value = "You can keep $MAX_PRESETS presets. Delete one first."
                    } catch (e: IllegalArgumentException) {
                        message.value = e.message
                    }
                }
            }
            GridEvent.PresetReplaceConfirmed -> {
                val ask = presetSheet.value as? PresetSheet.ConfirmReplace
                if (ask != null && current != null) {
                    viewModelScope.launch {
                        repo.overwrite(ask.id, current, ask.weeks)
                        presetSheet.value = PresetSheet.Listing
                    }
                }
            }
            is GridEvent.PresetRenameRequested -> presetList.value.firstOrNull { it.id == event.id }?.let {
                presetSheet.value = PresetSheet.Renaming(it.id, it.name)
            }
            is GridEvent.PresetRenamed -> viewModelScope.launch {
                try {
                    repo.rename(event.id, event.name)
                    presetSheet.value = PresetSheet.Listing
                } catch (e: PresetNameTaken) {
                    message.value = "Another preset is already called that."
                } catch (e: IllegalArgumentException) {
                    message.value = e.message
                }
            }
            is GridEvent.PresetApplied -> {
                val preset = presetList.value.firstOrNull { it.id == event.id }
                if (preset != null && current != null) {
                    when (val resolved = repo.resolve(preset, current)) {
                        is Resolved.Ready -> {
                            // A half-typed filter sheet would otherwise count against the old view.
                            draft.value = null
                            draftCount.value = null
                            presetSheet.value = null
                            request.value = resolved.request
                        }
                        is Resolved.Unavailable -> message.value = "${preset.name}: ${resolved.reason}"
                    }
                }
            }
            is GridEvent.PresetDeleted -> presetList.value.firstOrNull { it.id == event.id }?.let { preset ->
                deletedPreset.value = preset
                viewModelScope.launch { repo.delete(preset.id) }
            }
            GridEvent.PresetDeleteUndone -> deletedPreset.value?.let { preset ->
                deletedPreset.value = null
                viewModelScope.launch { repo.restore(preset) }
            }
            GridEvent.PresetDeleteDismissed -> deletedPreset.value = null
            else -> Unit
        }
    }

    companion object {
        /** Pure state transition, so it can be tested without coroutines. */
        internal fun reduce(r: GridRequest, event: GridEvent, catalog: Catalog): GridRequest = when (event) {
            is GridEvent.SeasonSelected -> {
                val season = catalog.season(event.season)
                r.copy(season = season, weeks = season.defaultWeeks)
            }
            is GridEvent.WeeksChanged -> r.copy(weeks = event.weeks)
            // A new pack brings its own lead stat as the sort, and a K or D/ST pack its chip.
            is GridEvent.PackSelected -> r.copy(
                pack = event.pack,
                positions = event.pack.unit ?: r.positions.takeIf { event.pack in it.packs } ?: PositionFilter.ALL,
                sort = event.pack.defaultSort,
                direction = GridRequest.defaultDirection(event.pack.defaultSort),
            )
            // A chip keeps the pack when it offers it; otherwise it brings its first.
            is GridEvent.PositionsSelected -> if (r.pack in event.positions.packs) {
                r.copy(positions = event.positions)
            } else {
                val pack = event.positions.packs.first()
                r.copy(positions = event.positions, pack = pack, sort = pack.defaultSort, direction = GridRequest.defaultDirection(pack.defaultSort))
            }
            // Tapping the sorted column flips it; tapping another sorts it best-first.
            is GridEvent.SortBy -> if (event.column == r.sort) {
                r.copy(direction = if (r.direction == Direction.DESCENDING) Direction.ASCENDING else Direction.DESCENDING)
            } else {
                r.copy(sort = event.column, direction = GridRequest.defaultDirection(event.column))
            }
            is GridEvent.NameChanged -> r.copy(name = event.name)
            GridEvent.PerGameToggled -> r.copy(perGame = !r.perGame)
            GridEvent.HeatToggled -> r
            is GridEvent.ProfileSelected -> r
            is GridEvent.AddToCompare -> r
            is GridEvent.RemoveFromTray -> r
            is GridEvent.EditTraySlot -> r
            is GridEvent.ReplaceTraySlot -> r
            GridEvent.TraySlotEditClosed -> r
            GridEvent.MessageShown -> r
            is GridEvent.TeamsSelected -> r.copy(teams = event.teams)
            is GridEvent.MinSnapShareSelected -> r.copy(minSnapShare = event.share)
            is GridEvent.FiltersApplied -> r.copy(filters = event.filters)
            is GridEvent.FilterDraftChanged -> r
            GridEvent.FilterSheetClosed -> r
            is GridEvent.RosterSelected -> r
            GridEvent.PresetsOpened,
            GridEvent.PresetsClosed,
            GridEvent.PresetSaveRequested,
            GridEvent.PresetReplaceConfirmed,
            GridEvent.PresetDialogDismissed,
            GridEvent.PresetDeleteUndone,
            GridEvent.PresetDeleteDismissed,
            is GridEvent.PresetSaved,
            is GridEvent.PresetRenameRequested,
            is GridEvent.PresetRenamed,
            is GridEvent.PresetApplied,
            is GridEvent.PresetDeleted -> r
        }

        /**
         * Carries [r] over to a reloaded [catalog]. The same season is kept if
         * it still exists, and default weeks follow its new last week. If the
         * season is gone (deselected in Settings), the latest season is used.
         */
        internal fun rebase(r: GridRequest, catalog: Catalog): GridRequest {
            val season = catalog.seasons.firstOrNull { it.season == r.season.season }
                ?: return r.copy(season = catalog.latest, weeks = catalog.latest.defaultWeeks)
            val weeks = if (r.weeks == r.season.defaultWeeks) season.defaultWeeks else r.weeks
            return r.copy(season = season, weeks = weeks)
        }

        fun factory(
            repository: StatsRepository,
            scoring: ScoringRepository,
            tray: CompareTrayRepository,
            badges: Flow<Map<String, String>> = flowOf(emptyMap()),
            rosters: Flow<List<Roster>> = flowOf(emptyList()),
            presets: GridPresetRepository? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GridViewModel(repository, scoring, tray, badges = badges, rosters = rosters, presets = presets) }
            }
    }
}
