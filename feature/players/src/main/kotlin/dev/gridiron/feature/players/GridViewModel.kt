package dev.gridiron.feature.players

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.statquery.StatColumn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
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
    ) : GridUiState {
        val refreshing: Boolean get() = page?.request != request && error == null
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GridViewModel(
    private val repository: StatsRepository,
    /** Coalesces bursts (typing, dragging the week slider) into one query. */
    debounceMillis: Long = 150,
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

    val state: StateFlow<GridUiState> =
        combine(catalogLoad, request, heat, lastPage, pageError) { load, r, h, page, err ->
            when (load) {
                CatalogLoad.Loading -> GridUiState.Loading
                is CatalogLoad.Failed -> GridUiState.Failed(load.message)
                is CatalogLoad.Loaded ->
                    if (r == null) GridUiState.Loading
                    else GridUiState.Ready(load.catalog, r, h, page, err?.takeIf { it.first == r }?.second)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GridUiState.Loading)

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
            request.value = GridRequest(c.latest, c.latest.defaultWeeks, StatPack.OPPORTUNITY)
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
    }

    fun onEvent(event: GridEvent) {
        if (event == GridEvent.HeatToggled) {
            heat.update { !it }
            return
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
        }

        fun factory(repository: StatsRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { GridViewModel(repository) }
        }
    }
}
