package dev.gridiron.feature.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridDisplayRepository
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.PresetLimitReached
import dev.gridiron.core.data.PresetNameTaken
import dev.gridiron.core.data.Resolved
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.data.describeSlot
import dev.gridiron.core.datastore.GridPreset
import dev.gridiron.core.datastore.MAX_PRESETS
import dev.gridiron.core.datastore.PresetWeeks
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.datastore.RowDensity
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    data class DensitySelected(val density: RowDensity) : GridEvent
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
    /** Shows only roster [id]'s players; null shows everyone; [GridViewModel.FREE_AGENTS_ID] shows everyone on no league team. */
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
        /** The open filter sheet's match count; null when the sheet is closed. */
        val draftCount: DraftCount? = null,
        /** ESPN injury letters (Q, D, O, IR, …) by player id; empty when there is no live data. */
        val badges: ImmutableMap<String, String> = persistentMapOf(),
        val rosters: ImmutableList<Roster> = persistentListOf(),
        /** The roster the Grid is narrowed to, or null for everyone. */
        val rosterId: String? = null,
        /** Everyone on any roster, so the table can mark them. */
        val rostered: ImmutableSet<String> = persistentSetOf(),
        /** The free-agents choice, or null when it isn't offered (no league synced, or not for this season). */
        val freeAgents: FreeAgentsOption? = null,
        /** False when no presets repository is wired, so the screen hides the chip. */
        val presetsEnabled: Boolean = false,
        val presets: ImmutableList<PresetRow> = persistentListOf(),
        /** The open presets sheet, or null when closed. */
        val presetSheet: PresetSheet? = null,
        /** A preset just deleted, offered for undo until the sheet closes. */
        val deletedPreset: GridPreset? = null,
        /** Why the last save, rename or delete failed, shown in the sheet (the Grid's snackbar sits under its scrim). */
        val presetError: String? = null,
        /** The saved row height. */
        val density: RowDensity = RowDensity.COMFORTABLE,
    ) : GridUiState {
        val presetsFull: Boolean get() = presets.size >= MAX_PRESETS

        /**
         * How many controls in the View & filters sheet differ from their defaults: each advanced filter, and one
         * apiece for teams, a roster, a snap floor, per game, heat off and compact rows.
         */
        val viewChanges: Int
            get() = request.filters.size +
                listOf(
                    request.teams.isNotEmpty(),
                    rosterId != null,
                    request.minSnapShare != null,
                    request.perGame,
                    !heat,
                    density == RowDensity.COMPACT,
                ).count { it }

        val refreshing: Boolean get() = page?.request != request && error == null
    }
}

/** The roster chip's "Free agents" row; [asOfMillis] is when the league was last read from ESPN. */
data class FreeAgentsOption(val asOfMillis: Long)

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
    /** Row height; null keeps it comfortable and ignores changes. */
    private val display: GridDisplayRepository? = null,
    /** Who is on a league team (the last ESPN sync); null turns the free-agents choice off. */
    leagueRostered: Flow<LeagueRostered?> = flowOf(null),
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
    private val draft = MutableStateFlow<List<Filter>?>(null)
    /** Count results tagged with the draft they were computed for, so a stale count never resurfaces after the sheet closes or a newer draft supersedes it. */
    private val draftCount = MutableStateFlow<Pair<List<Filter>, DraftCount>?>(null)
    private val rosterId = MutableStateFlow<String?>(null)
    /** The chosen roster's players, kept so a request created later starts narrowed too. */
    private val onlyPlayers = MutableStateFlow<Set<String>?>(null)
    /** The league's rostered players while free agents is chosen, else empty; kept like [onlyPlayers]. */
    private val excludePlayers = MutableStateFlow<Set<String>>(emptySet())
    private val league = leagueRostered.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val rosterList = rosters.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val presetList = (presets?.presets ?: flowOf(persistentListOf<GridPreset>()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())
    private val densityFlow = display?.density ?: flowOf(RowDensity.COMFORTABLE)
    private val presetSheet = MutableStateFlow<PresetSheet?>(null)
    private val deletedPreset = MutableStateFlow<GridPreset?>(null)
    private val presetError = MutableStateFlow<String?>(null)

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
            combine(scoring.profiles, trayUi, message, editingSlot, combine(draft, draftCount, badges, ::Lines), ::Extras),
        ) { base, extras ->
            if (base is GridUiState.Ready) {
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
                    draftCount = draftCount,
                    badges = extras.lines.badges.toImmutableMap(),
                )
            } else {
                base
            }
        }.combine(combine(rosterList, rosterId, league, ::Triple)) { base, (list, id, synced) ->
            if (base is GridUiState.Ready) {
                base.copy(
                    rosters = list.toImmutableList(),
                    rosterId = id,
                    rostered = list.flatMap { it.playerIds }.toImmutableSet(),
                    freeAgents = synced?.takeIf { it.season == base.request.season.season }?.let { FreeAgentsOption(it.fetchedAtMillis) },
                )
            } else {
                base
            }
        }.combine(combine(presetList, presetSheet, deletedPreset, presetError, ::PresetLines)) { base, (list, sheet, deleted, error) ->
            if (base is GridUiState.Ready && presets != null) {
                base.copy(
                    presetsEnabled = true,
                    presets = list.map { preset ->
                        val reason = (presets.resolve(preset, base.request) as? Resolved.Unavailable)?.reason
                        PresetRow(preset, presetSummary(preset), reason)
                    }.toImmutableList(),
                    presetSheet = sheet,
                    deletedPreset = deleted,
                    presetError = error,
                )
            } else {
                base
            }
        }.combine(densityFlow) { base, density ->
            if (base is GridUiState.Ready) base.copy(density = density) else base
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GridUiState.Loading)

    private data class PresetLines(
        val list: ImmutableList<GridPreset>,
        val sheet: PresetSheet?,
        val deleted: GridPreset?,
        val error: String?,
    )

    private data class Extras(
        val profiles: ImmutableList<ScoringProfile>,
        val tray: ImmutableList<TraySlotUi>,
        val message: String?,
        val editingSlot: CompareSlot?,
        val lines: Lines,
    )

    /** The draft-count and badge sources, combined once so each carries its own staleness tag. */
    private data class Lines(
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
                        excludePlayers = excludePlayers.value,
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
            val season = request.map { it?.season?.season }.distinctUntilChanged()
            combine(rosterList, rosterId, league, season) { list, id, rostered, seasonNow ->
                val free = id == FREE_AGENTS_ID
                val roster = id?.takeUnless { free }?.let { wanted -> list.firstOrNull { it.id == wanted } }
                val offered = rostered?.takeIf { seasonNow == null || it.season == seasonNow }
                // A deleted roster, or free agents once the league or its season is gone, falls back to everyone.
                if (id != null && (if (free) offered == null else roster == null)) rosterId.value = null
                roster?.playerIds?.toSet() to (if (free) offered?.playerIds.orEmpty() else emptySet())
            }.collect { (only, except) ->
                onlyPlayers.value = only
                excludePlayers.value = except
                request.update { it?.copy(onlyPlayers = only, excludePlayers = except) }
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
            is GridEvent.DensitySelected -> {
                val repo = display ?: return
                viewModelScope.launch {
                    try {
                        repo.setDensity(event.density)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message.value = "Couldn't save row height: ${e.message ?: e::class.simpleName}"
                    }
                }
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

    /** Runs a preset write; any failure becomes [presetError] in the sheet instead of a crash. */
    private fun launchPreset(
        onNameTaken: (PresetNameTaken) -> Unit = { presetError.value = "Another preset is already called that." },
        block: suspend () -> Unit,
    ) {
        presetError.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PresetNameTaken) {
                onNameTaken(e)
            } catch (e: PresetLimitReached) {
                presetError.value = "You can keep $MAX_PRESETS presets. Delete one first."
            } catch (e: IllegalArgumentException) {
                presetError.value = e.message
            } catch (e: Exception) {
                presetError.value = "Couldn't save presets: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    private fun onPresetEvent(event: GridEvent) {
        val repo = presets ?: return
        val current = request.value
        when (event) {
            GridEvent.PresetsOpened -> {
                presetError.value = null
                presetSheet.value = PresetSheet.Listing
            }
            GridEvent.PresetsClosed -> {
                presetSheet.value = null
                deletedPreset.value = null
                presetError.value = null
            }
            GridEvent.PresetDialogDismissed -> {
                presetError.value = null
                presetSheet.value = PresetSheet.Listing
            }
            GridEvent.PresetSaveRequested -> if (current != null) {
                presetError.value = null
                presetSheet.value = PresetSheet.Saving(repo.weeksRule(current))
            }
            is GridEvent.PresetSaved -> if (current != null) {
                launchPreset(onNameTaken = { presetSheet.value = PresetSheet.ConfirmReplace(event.name.trim(), it.existingId, event.weeks) }) {
                    repo.save(event.name, current, event.weeks)
                    presetSheet.value = PresetSheet.Listing
                }
            }
            GridEvent.PresetReplaceConfirmed -> {
                val ask = presetSheet.value as? PresetSheet.ConfirmReplace
                if (ask != null && current != null) {
                    launchPreset {
                        repo.overwrite(ask.id, current, ask.weeks)
                        presetSheet.value = PresetSheet.Listing
                    }
                }
            }
            is GridEvent.PresetRenameRequested -> presetList.value.firstOrNull { it.id == event.id }?.let {
                presetError.value = null
                presetSheet.value = PresetSheet.Renaming(it.id, it.name)
            }
            is GridEvent.PresetRenamed -> launchPreset {
                repo.rename(event.id, event.name)
                presetSheet.value = PresetSheet.Listing
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
                            deletedPreset.value = null
                            presetError.value = null
                            request.value = resolved.request
                        }
                        is Resolved.Unavailable -> presetError.value = "${preset.name}: ${resolved.reason}"
                    }
                }
            }
            is GridEvent.PresetDeleted -> presetList.value.firstOrNull { it.id == event.id }?.let { preset ->
                launchPreset {
                    repo.delete(preset.id)
                    deletedPreset.value = preset
                }
            }
            GridEvent.PresetDeleteUndone -> deletedPreset.value?.let { preset ->
                deletedPreset.value = null
                launchPreset { repo.restore(preset) }
            }
            else -> Unit
        }
    }

    companion object {
        /** The roster chip's choice for everyone on no team in the synced league; no real roster has this id. */
        const val FREE_AGENTS_ID = "free-agents"

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
            is GridEvent.DensitySelected -> r
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
            display: GridDisplayRepository? = null,
            leagueRostered: Flow<LeagueRostered?> = flowOf(null),
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    GridViewModel(repository, scoring, tray, badges = badges, rosters = rosters, presets = presets, display = display, leagueRostered = leagueRostered)
                }
            }
    }
}
