package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.BreakoutRepository
import dev.gridiron.core.data.BreakoutRow
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.model.Position
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

public sealed interface BreakoutsState {
    public data object Loading : BreakoutsState

    public data class Unavailable(val message: String) : BreakoutsState

    public data class Loaded(val season: Int, val week: Int, val rows: List<BreakoutRow>) : BreakoutsState
}

/** Reads the Rising roles table for the season shown. */
public class BreakoutsViewModel(private val repository: BreakoutRepository) : ViewModel() {
    private val _state = MutableStateFlow<BreakoutsState>(BreakoutsState.Loading)
    public val state: StateFlow<BreakoutsState> = _state.asStateFlow()

    // A load superseded by a newer one (a refresh mid-load) must not overwrite it.
    private var latest = 0

    public fun load(season: Int) {
        val request = ++latest
        _state.value = BreakoutsState.Loading
        viewModelScope.launch {
            val next = try {
                val result = repository.find(season)
                if (result.message != null && result.rows.isEmpty()) BreakoutsState.Unavailable(result.message!!)
                else BreakoutsState.Loaded(result.season, result.week, result.rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BreakoutsState.Unavailable("Couldn't load Rising roles: ${e.message}.")
            }
            if (request == latest) _state.value = next
        }
    }

    public companion object {
        public fun factory(repository: BreakoutRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { BreakoutsViewModel(repository) } }
    }
}

/** ☰ → Rising roles: RBs, WRs and TEs whose usage and expected points are climbing, with the teammates who are out. */
@Composable
public fun BreakoutsRoute(
    season: Int,
    repository: BreakoutRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the list loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** Everyone on a league team, for the owner tags; null when no league is synced. */
    league: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
) {
    val vm: BreakoutsViewModel = viewModel(factory = BreakoutsViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val taken by league.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, version) { vm.load(season) }
    BreakoutsScreen(
        state,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season }?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(),
        onPlayer,
        onBack,
    )
}

private val POSITION_CHIPS = listOf(null, "RB", "WR", "TE")

@Composable
public fun BreakoutsScreen(
    state: BreakoutsState,
    league: LeagueRostered?,
    mine: Set<String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Rising roles", onBack)
            when (state) {
                BreakoutsState.Loading -> LoadingRows()
                is BreakoutsState.Unavailable -> EmptyState(state.message)
                is BreakoutsState.Loaded -> {
                    Text(
                        "Entering week ${state.week}: usage and expected points over the last four games against the eight before, " +
                            "plus teammates who are out. A growing role tends to keep growing; it isn't a points forecast.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (code in POSITION_CHIPS) {
                            FilterChip(selected = position == code, onClick = { position = code }, label = { Text(code ?: "All") })
                        }
                        if (league != null) {
                            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free agents") }, modifier = Modifier.testTag("chip:free"))
                        }
                    }
                    val rows = state.rows.filter { position == null || it.position == position }
                        .map { it to ownerOf(it.playerId, league, mine) }
                        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
                    if (rows.isEmpty()) Text("None right now.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { (row, _) -> row.playerId }) { (row, owner) -> BreakoutListRow(row, owner, onPlayer) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakoutListRow(row: BreakoutRow, owner: Owner?, onPlayer: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(row.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("rise:${row.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${Position.label(row.position)} · ${row.team ?: "free agent"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(row.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            owner?.let {
                Text(
                    when (it) {
                        Owner.FreeAgent -> "Free agent"
                        Owner.Yours -> "Yours"
                        is Owner.Other -> "On ${it.team}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (it == Owner.FreeAgent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(String.format(Locale.US, "%.0f", row.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
