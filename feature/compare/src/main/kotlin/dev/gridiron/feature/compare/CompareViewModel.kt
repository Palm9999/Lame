package dev.gridiron.feature.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.ComparePage
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareRequest
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.ScoringProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/** The four tabs Compare shows once there's a page to render. */
internal enum class CompareTab(val label: String) { BARS("Bars"), TABLE("Table"), RADAR("Radar"), SCATTER("Scatter") }

/** Everything the Compare screen can be showing. */
internal sealed interface CompareUiState {
    data object Loading : CompareUiState
    data object NeedsPlayers : CompareUiState
    data class Failed(val message: String) : CompareUiState

    data class Ready(
        val page: ComparePage,
        val catalog: Catalog,
        val profiles: ImmutableList<ScoringProfile>,
        val tab: CompareTab = CompareTab.BARS,
        val onlyDifferences: Boolean = false,
        val radarPair: Pair<Int, Int> = 0 to 1,
        val selectedPoint: String? = null,
        val refreshing: Boolean = false,
        val message: String? = null,
    ) : CompareUiState
}

/** Everything the user can do on Compare. The screen only ever emits these. */
internal sealed interface CompareEvent {
    data class TabSelected(val tab: CompareTab) : CompareEvent
    data object PerGameToggled : CompareEvent
    data object OnlyDifferencesToggled : CompareEvent
    data class RadarPairChanged(val a: Int, val b: Int) : CompareEvent
    data class PointSelected(val playerId: String?) : CompareEvent
    data class AddPointToCompare(val playerId: String, val name: String) : CompareEvent
    data class RemoveSlot(val slot: CompareSlot) : CompareEvent
    data class ProfileSelected(val id: String) : CompareEvent
    data object MessageShown : CompareEvent
}

/**
 * The Compare screen's state, built from one Grid query per slot (Task 11's
 * [CompareRepository]) on the database dispatcher, off the main thread.
 *
 * The query pipeline (catalog once, then a page per tray/profile/per-game
 * combination) runs through coroutines and needs the dispatcher to advance in
 * tests. The view-only fields (tab, only-differences, the radar pair, the
 * selected scatter point, the snackbar message) are plain state that
 * [onEvent] updates and republishes synchronously, so choosing a radar pair
 * or a tab never waits on a query and never gets reset by one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CompareViewModel(
    private val stats: StatsRepository,
    private val compare: CompareRepository,
    private val scoring: ScoringRepository,
    private val tray: CompareTrayRepository,
) : ViewModel() {

    private sealed interface CatalogLoad {
        data object Loading : CatalogLoad
        data class Loaded(val catalog: Catalog) : CatalogLoad
        data class Failed(val message: String) : CatalogLoad
    }

    // The query pipeline's own toggle; PerGameToggled retriggers a query, so it
    // stays a flow rather than a plain field.
    private val perGame = MutableStateFlow(false)

    // Plain fields, all touched only from the main dispatcher: every write is
    // immediately followed by recompute(), so state.value is always current
    // the moment a caller's onEvent(...) call (or a suspend step) returns.
    private var catalogLoad: CatalogLoad = CatalogLoad.Loading
    private var needsPlayers = false
    private var page: ComparePage? = null
    private var firstLoadError: String? = null
    private var computing = false
    private var profiles: ImmutableList<ScoringProfile> = persistentListOf()
    private var tab = CompareTab.BARS
    private var onlyDifferences = false
    private var radarPair = 0 to 1
    private var selectedPoint: String? = null
    private var message: String? = null

    private val _state = MutableStateFlow<CompareUiState>(CompareUiState.Loading)
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            scoring.profiles.collect {
                profiles = it
                recompute()
            }
        }
        viewModelScope.launch {
            val catalog = try {
                stats.catalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                catalogLoad = CatalogLoad.Failed("Couldn't open the stats database: ${e.message}")
                recompute()
                return@launch
            }
            catalogLoad = CatalogLoad.Loaded(catalog)
            recompute()

            combine(tray.slots, scoring.active, perGame, ::Triple)
                .mapLatest { (slots, profile, pg) ->
                    if (slots.size < 2) {
                        needsPlayers = true
                        page = null
                        firstLoadError = null
                        recompute()
                        return@mapLatest
                    }
                    needsPlayers = false
                    computing = true
                    recompute()
                    try {
                        page = compare.compare(CompareRequest(slots, profile, pg), catalog)
                        firstLoadError = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: e::class.simpleName.orEmpty()
                        if (page == null) firstLoadError = msg else message = "Couldn't refresh: $msg"
                    } finally {
                        computing = false
                        recompute()
                    }
                }
                .collect()
        }
    }

    private fun recompute() {
        _state.value = when (val load = catalogLoad) {
            CatalogLoad.Loading -> CompareUiState.Loading
            is CatalogLoad.Failed -> CompareUiState.Failed(load.message)
            is CatalogLoad.Loaded -> when {
                needsPlayers -> CompareUiState.NeedsPlayers
                firstLoadError != null -> CompareUiState.Failed(firstLoadError!!)
                page != null -> CompareUiState.Ready(
                    page = page!!,
                    catalog = load.catalog,
                    profiles = profiles,
                    tab = tab,
                    onlyDifferences = onlyDifferences,
                    radarPair = clampRadarPair(radarPair, page!!.slots.size),
                    selectedPoint = selectedPoint,
                    refreshing = computing,
                    message = message,
                )
                else -> CompareUiState.Loading
            }
        }
    }

    fun onEvent(event: CompareEvent) {
        when (event) {
            is CompareEvent.TabSelected -> {
                tab = event.tab
                recompute()
            }
            CompareEvent.PerGameToggled -> perGame.value = !perGame.value
            CompareEvent.OnlyDifferencesToggled -> {
                onlyDifferences = !onlyDifferences
                recompute()
            }
            is CompareEvent.RadarPairChanged -> {
                radarPair = event.a to event.b
                recompute()
            }
            is CompareEvent.PointSelected -> {
                selectedPoint = event.playerId
                recompute()
            }
            is CompareEvent.AddPointToCompare -> viewModelScope.launch {
                val first = tray.slots.first().firstOrNull() ?: return@launch
                val slot = CompareSlot(event.playerId, first.season, first.weeks)
                message = when (tray.add(slot)) {
                    CompareTrayRepository.AddResult.ADDED -> "${event.name} added to compare"
                    CompareTrayRepository.AddResult.ALREADY_THERE -> "${event.name} is already in compare"
                    CompareTrayRepository.AddResult.FULL -> "Compare holds ${CompareTrayRepository.CAPACITY} players. Remove one first."
                }
                recompute()
            }
            is CompareEvent.RemoveSlot -> viewModelScope.launch { tray.remove(event.slot) }
            is CompareEvent.ProfileSelected -> viewModelScope.launch { scoring.setActive(event.id) }
            CompareEvent.MessageShown -> {
                message = null
                recompute()
            }
        }
    }

    companion object {
        /** A radar pair is only valid while both indices still point at a slot. */
        private fun clampRadarPair(pair: Pair<Int, Int>, slotCount: Int): Pair<Int, Int> =
            if (pair.first in 0 until slotCount && pair.second in 0 until slotCount && pair.first != pair.second) pair else 0 to 1

        fun factory(
            stats: StatsRepository,
            compare: CompareRepository,
            scoring: ScoringRepository,
            tray: CompareTrayRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CompareViewModel(stats, compare, scoring, tray) }
        }
    }
}
