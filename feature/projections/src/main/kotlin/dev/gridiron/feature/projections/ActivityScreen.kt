package dev.gridiron.feature.projections

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
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
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.ActivityItem
import dev.gridiron.core.data.live.ActivityKind
import dev.gridiron.core.data.live.ActivityResult
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

public sealed interface ActivityState {
    public data object Loading : ActivityState

    public data class Loaded(val result: ActivityResult) : ActivityState
}

private fun teamName(teams: Map<Int, String>, id: Int) = teams[id] ?: "Team $id"

private fun names(moves: List<dev.gridiron.core.data.live.ActivityMove>) = moves.joinToString { it.name ?: "a player" }

/** "Mine added Waiver Back for $12 and dropped Cut Guy" or "Rivals traded Old Star to Mine for Young Gun". */
public fun activityText(item: ActivityItem, teams: Map<Int, String>): String {
    val team = teamName(teams, item.teamId)
    return when (item.kind) {
        ActivityKind.ADD -> {
            val added = item.moves.filter { it.toTeamId == item.teamId }
            val dropped = item.moves.filter { it.fromTeamId == item.teamId }
            listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { "added ${names(it)}" + (item.bid?.let { b -> " for $$b" } ?: "") },
                dropped.takeIf { it.isNotEmpty() }?.let { "dropped ${names(it)}" },
            ).joinToString(" and ", prefix = "$team ")
        }
        ActivityKind.TRADE -> {
            val sent = item.moves.filter { it.fromTeamId == item.teamId }
            val partner = sent.firstOrNull()?.toTeamId ?: item.moves.first { it.toTeamId == item.teamId }.fromTeamId
            val got = item.moves.filter { it.toTeamId == item.teamId }
            "$team traded ${names(sent).ifEmpty { "nobody" }} to ${teamName(teams, partner)} for ${names(got).ifEmpty { "nobody" }}"
        }
    }
}

/** Each trading team's rest-of-season points in less points out ([ros] by player id; unknown players count 0). */
public fun tradeNet(item: ActivityItem, ros: Map<String, Double>): Map<Int, Double> {
    val net = HashMap<Int, Double>()
    for (m in item.moves) {
        val pts = m.playerId?.let(ros::get) ?: 0.0
        if (m.toTeamId != 0) net.merge(m.toTeamId, pts, Double::plus)
        if (m.fromTeamId != 0) net.merge(m.fromTeamId, -pts, Double::plus)
    }
    return net
}

public class ActivityViewModel(
    private val activity: suspend (Int) -> ActivityResult,
    private val ros: suspend (Int, ScoringProfile) -> Map<String, Double>,
    /** Swaps each trade's grade for the one kept from its first sighting (`TradeGradeStore.keep`). */
    private val keep: suspend (profileId: String, grades: Map<String, Map<Int, Double>>) -> Map<String, Map<Int, Double>> = { _, g -> g },
) : ViewModel() {
    private val _grades = MutableStateFlow<Map<String, Map<Int, Double>>>(emptyMap())
    public val grades: StateFlow<Map<String, Map<Int, Double>>> = _grades.asStateFlow()
    private val _state = MutableStateFlow<ActivityState>(ActivityState.Loading)
    public val state: StateFlow<ActivityState> = _state.asStateFlow()
    private val _points = MutableStateFlow<Map<String, Double>>(emptyMap())
    public val points: StateFlow<Map<String, Double>> = _points.asStateFlow()

    public fun load(season: Int, profile: ScoringProfile) {
        viewModelScope.launch {
            val loaded = activity(season)
            _state.value = ActivityState.Loaded(loaded)
            _points.value = try {
                ros(season, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            // Only with projections to grade by: a grade of zeros would be kept for good.
            if (_points.value.isNotEmpty()) {
                val trades = loaded.items.filter { it.kind == ActivityKind.TRADE }.associate { it.id to tradeNet(it, _points.value) }
                _grades.value = try {
                    keep(profile.id, trades)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    trades
                }
            }
        }
    }
}

/** More → League activity: the league's adds, drops and trades this season, newest first. */
@Composable
public fun ActivityRoute(
    season: Int,
    activity: suspend (Int) -> ActivityResult,
    ros: suspend (Int, ScoringProfile) -> Map<String, Double>,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    keep: suspend (String, Map<String, Map<Int, Double>>) -> Map<String, Map<Int, Double>> = { _, g -> g },
) {
    val vm: ActivityViewModel = viewModel(factory = viewModelFactory { initializer { ActivityViewModel(activity, ros, keep) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val grades by vm.grades.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    LaunchedEffect(season, profile) { profile?.let { vm.load(season, it) } }
    ActivityScreen(state, points, onPlayer, onBack, grades)
}

private enum class ActivityFilter(val label: String, val tag: String) {
    ALL("All", "all"),
    ADDS("Adds", "adds"),
    TRADES("Trades", "trades"),
    MINE("Mine", "mine"),
}

@Composable
public fun ActivityScreen(
    state: ActivityState,
    ros: Map<String, Double>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Each trade's grade from when the app first saw it, by trade id; a trade without one is graded on today's numbers. */
    grades: Map<String, Map<Int, Double>> = emptyMap(),
) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("League activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                ActivityState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is ActivityState.Loaded -> {
                    val r = state.result
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in ActivityFilter.entries) {
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, modifier = Modifier.testTag("filter:${f.tag}"))
                        }
                    }
                    if (r.items.isEmpty()) {
                        Text("No activity: ${r.error ?: "no moves yet"}.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        return@Column
                    }
                    val shown = r.items.filter { i ->
                        when (filter) {
                            ActivityFilter.ALL -> true
                            ActivityFilter.ADDS -> i.kind == ActivityKind.ADD
                            ActivityFilter.TRADES -> i.kind == ActivityKind.TRADE
                            ActivityFilter.MINE -> r.myTeamId != null && i.moves.any { it.fromTeamId == r.myTeamId || it.toTeamId == r.myTeamId }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        for ((week, items) in shown.groupBy { it.week }) {
                            item(key = "w:$week") {
                                Text("Week $week", Modifier.padding(start = 16.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(items, key = { it.id }) { i -> ActivityRow(i, r, ros, onPlayer, grades[i.id]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, result: ActivityResult, ros: Map<String, Double>, onPlayer: (String) -> Unit, kept: Map<Int, Double>?) {
    val mine = result.myTeamId != null && item.moves.any { it.fromTeamId == result.myTeamId || it.toTeamId == result.myTeamId }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(activityText(item, result.teams), style = MaterialTheme.typography.bodyMedium, fontWeight = if (mine) FontWeight.Bold else FontWeight.SemiBold)
        if (item.kind == ActivityKind.TRADE) {
            Text(
                (kept ?: tradeNet(item, ros)).entries.sortedByDescending { it.value }
                    .joinToString(" · ", postfix = if (kept != null) " rest of season, when first seen" else " rest of season") { (team, v) ->
                    "${teamName(result.teams, team)} ${gainText(v)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (m in item.moves.filter { it.toTeamId != 0 && it.playerId != null }) {
            val id = m.playerId!!
            Text(
                "${m.name ?: id}" + (ros[id]?.let { " · ${String.format(Locale.US, "%.1f", it)} rest of season" } ?: ""),
                Modifier.clickable { onPlayer(id) }.padding(vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
