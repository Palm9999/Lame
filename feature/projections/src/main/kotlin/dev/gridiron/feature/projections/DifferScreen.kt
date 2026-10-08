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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.projections.ListedProjection
import dev.gridiron.core.projections.projectedScore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** One player both the app and ESPN project for the week, scored the same way; [gap] is the app's points less ESPN's. */
public data class DifferRow(val playerId: String, val name: String, val position: String, val team: String?, val app: Double, val espn: Double) {
    val gap: Double get() = app - espn
}

private val DIFFER_POSITIONS = listOf("QB", "RB", "WR", "TE", "K", "DST")

/** Positions the forecast never blends with ESPN: their final projection is already the model alone. */
private val MODEL_ONLY = setOf("K", "DST")

/** The [limit] biggest gaps one way ([above]: the app higher), at [position] or every position ESPN projects. */
public fun differRows(app: List<ListedProjection>, espn: List<ListedProjection>, profile: ScoringProfile, above: Boolean, position: String?, limit: Int = 15): List<DifferRow> {
    val theirs = espn.associateBy { it.playerId }
    return app.mapNotNull { a ->
        val pos = a.position?.takeIf { it in DIFFER_POSITIONS && (position == null || it == position) } ?: return@mapNotNull null
        val e = theirs[a.playerId] ?: return@mapNotNull null
        val p = Position.fromCode(pos)
        DifferRow(a.playerId, a.name, pos, a.team, projectedScore(a.components, profile, p), projectedScore(e.components, profile, p))
    }.filter { if (above) it.gap > GAP_FLOOR else it.gap < -GAP_FLOOR }
        .sortedWith(compareBy<DifferRow> { if (above) -it.gap else it.gap }.thenBy { it.playerId })
        .take(limit)
}

private const val GAP_FLOOR = 0.05

public sealed interface DifferState {
    public data object Loading : DifferState

    public data class Unavailable(val message: String) : DifferState

    /** The app's final projections, the model's alone (empty in a build before it was stored) and ESPN's for the upcoming [week]. */
    public data class Loaded(
        val week: Int,
        val app: List<ListedProjection>,
        val espn: List<ListedProjection>,
        val model: List<ListedProjection> = emptyList(),
    ) : DifferState
}

public class DifferViewModel(private val repository: ProjectionsRepository) : ViewModel() {
    private val _state = MutableStateFlow<DifferState>(DifferState.Loading)
    public val state: StateFlow<DifferState> = _state.asStateFlow()

    public fun load(season: Int) {
        viewModelScope.launch {
            _state.value = try {
                val status = repository.status()
                val week = status.upcoming[season]
                when {
                    status.status != "ok" -> DifferState.Unavailable("No projections yet. Refresh stats to build them.")
                    week == null -> DifferState.Unavailable("No upcoming games in $season.")
                    else -> DifferState.Loaded(week, repository.weekAll(season, week), repository.espnWeek(season, week), repository.modelWeek(season, week))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DifferState.Unavailable("Couldn't read projections: ${e.message}.")
            }
        }
    }
}

/** More → Where we differ: the upcoming week's players the app projects well above or below ESPN. */
@Composable
public fun DifferRoute(
    season: Int,
    repository: ProjectionsRepository,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    dataVersion: Flow<Long> = flowOf(0L),
    league: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
) {
    val vm: DifferViewModel = viewModel(factory = viewModelFactory { initializer { DifferViewModel(repository) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val taken by league.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, version) { vm.load(season) }
    val p = profile ?: return
    DifferScreen(
        state, p,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season }?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(),
        onPlayer, onBack,
    )
}

@Composable
public fun DifferScreen(
    state: DifferState,
    profile: ScoringProfile,
    league: LeagueRostered?,
    mine: Set<String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var above by rememberSaveable { mutableStateOf(true) }
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var modelAlone by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Where we differ", onBack)
            when (state) {
                DifferState.Loading -> LoadingRows()
                is DifferState.Unavailable -> EmptyState(state.message)
                is DifferState.Loaded -> {
                    if (state.espn.isEmpty()) {
                        Text("No ESPN projections for week ${state.week} in this build. Refresh stats.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        return@Column
                    }
                    val alone = modelAlone && state.model.isNotEmpty()
                    Text(
                        if (alone) {
                            "Week ${state.week}, your scoring. The model alone, before ESPN, props and the Questionable discount."
                        } else {
                            "Week ${state.week}, your scoring. The app's number already leans 50–65% on ESPN's, so a gap is where its own model disagrees. K and D/ST are the model alone."
                        },
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = above, onClick = { above = true }, label = { Text("Above ESPN") }, modifier = Modifier.testTag("differ:above"))
                        FilterChip(selected = !above, onClick = { above = false }, label = { Text("Below ESPN") }, modifier = Modifier.testTag("differ:below"))
                        if (state.model.isNotEmpty()) {
                            FilterChip(selected = modelAlone, onClick = { modelAlone = !modelAlone }, label = { Text("Model alone") }, modifier = Modifier.testTag("differ:model"))
                        }
                        if (league != null) {
                            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free agents") }, modifier = Modifier.testTag("chip:free"))
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in listOf(null) + DIFFER_POSITIONS) {
                            FilterChip(selected = position == p, onClick = { position = p }, label = { Text(p?.let(Position::label) ?: "All") }, modifier = Modifier.testTag("pos:${p ?: "all"}"))
                        }
                    }
                    val rows = differRows(if (alone) state.model + state.app.filter { it.position in MODEL_ONLY } else state.app, state.espn, profile, above, position, limit = if (freeOnly) Int.MAX_VALUE else 15)
                        .map { it to ownerOf(it.playerId, league, mine) }
                        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
                        .take(15)
                    if (rows.isEmpty()) Text("None this week.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { (r, _) -> r.playerId }) { (r, owner) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPlayer(r.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(Position.label(r.position), r.team).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text("${if (alone) "Model" else "App"} ${one(r.app)} · ESPN ${one(r.espn)}", style = MaterialTheme.typography.labelSmall)
                                    owner?.let {
                                        Text(
                                            when (it) {
                                                Owner.FreeAgent -> "Free agent"
                                                Owner.Yours -> "Yours"
                                                is Owner.Other -> "On ${it.team}"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(gainText(r.gap), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
