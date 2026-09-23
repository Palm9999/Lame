package dev.gridiron.feature.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.Sparkline
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TraySlotUi
import dev.gridiron.core.data.describeSlot
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
}

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
    ) : GridUiState {
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
) : ViewModel() {

    private sealed interface CatalogLoad {
        data object Loading : CatalogLoad
        data class Loaded(val catalog: Catalog) : CatalogLoad
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
    private val draftCount = MutableStateFlow<DraftCount?>(null)

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
            combine(scoring.profiles, trayUi, message, editingSlot, combine(sparklines, draftCount, ::Pair), ::Extras),
        ) { base, extras ->
            if (base is GridUiState.Ready) {
                val lines = extras.lines.first?.takeIf { (page, _) -> page == base.page }?.second.orEmpty()
                base.copy(
                    profiles = extras.profiles,
                    tray = extras.tray,
                    message = extras.message,
                    editingSlot = extras.editingSlot,
                    sparklines = lines.toImmutableMap(),
                    draftCount = extras.lines.second,
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
        val lines: Pair<Pair<GridPage, Map<String, Sparkline>>?, DraftCount?>,
    )

    init {
        viewModelScope.launch {
            val c = try {
                repository.catalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogLoad.value = CatalogLoad.Failed("Couldn't open the stats database: ${e.message}")
                return@launch
            }
            catalogLoad.value = CatalogLoad.Loaded(c)
            request.value = GridRequest(c.latest, c.latest.defaultWeeks, StatPack.OPPORTUNITY, scoring = scoring.active.first())
        }
        viewModelScope.launch {
            request.filterNotNull()
                .debounce(debounceMillis)
                .mapLatest { r ->
                    try {
                        lastPage.value = repository.grid(r, checkNotNull(catalog))
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
                    draftCount.value = try {
                        DraftCount.Matches(repository.count(r.copy(filters = filters)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        DraftCount.Unavailable
                    }
                }
                .collect()
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
                draftCount.value = DraftCount.Counting
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
            else -> Unit
        }
        val c = catalog ?: return
        request.update { current -> current?.let { reduce(it, event, c) } }
    }

    companion object {
        /** Pure state transition, so it can be tested without coroutines. */
        internal fun reduce(r: GridRequest, event: GridEvent, catalog: Catalog): GridRequest = when (event) {
            is GridEvent.SeasonSelected -> {
                val season = catalog.season(event.season)
                r.copy(season = season, weeks = season.defaultWeeks)
            }
            is GridEvent.WeeksChanged -> r.copy(weeks = event.weeks)
            // A new pack brings its own lead stat as the sort.
            is GridEvent.PackSelected -> r.copy(
                pack = event.pack,
                sort = event.pack.defaultSort,
                direction = GridRequest.defaultDirection(event.pack.defaultSort),
            )
            is GridEvent.PositionsSelected -> r.copy(positions = event.positions)
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
        }

        fun factory(repository: StatsRepository, scoring: ScoringRepository, tray: CompareTrayRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GridViewModel(repository, scoring, tray) }
            }
    }
}
