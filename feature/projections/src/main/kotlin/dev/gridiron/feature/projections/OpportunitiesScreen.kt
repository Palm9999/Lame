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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.OpportunitiesRepository
import dev.gridiron.core.data.OpportunityRow
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/** ☰ → Opportunities: healthy players moving up a depth chart because a top-two starter is hurt. */
@Composable
public fun OpportunitiesRoute(
    season: Int,
    repository: OpportunitiesRepository,
    scoring: ScoringRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the list loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** Everyone on a league team, for the owner tags; null when no league is synced. */
    league: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
    /** Bumped when ESPN's injury list refreshes. */
    injuriesChanged: Flow<Long> = flowOf(0L),
) {
    val vm: OpportunitiesViewModel = viewModel(factory = OpportunitiesViewModel.factory(repository))
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by scoring.active.collectAsStateWithLifecycle<ScoringProfile?>(initialValue = null)
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val injuries by injuriesChanged.collectAsStateWithLifecycle(initialValue = 0L)
    val taken by league.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(season, profile, version, injuries) { profile?.let { vm.load(season, it) } }
    OpportunitiesScreen(
        state,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season }?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(),
        onPlayer,
        onBack,
    )
}

@Composable
public fun OpportunitiesScreen(
    state: OpportunitiesState,
    league: LeagueRostered?,
    mine: Set<String>,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
) {
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("Opportunities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            when (state) {
                OpportunitiesState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is OpportunitiesState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is OpportunitiesState.Loaded -> {
                    Text(
                        "Week ${state.week}: a top-two RB, WR or TE, or a QB1, is Doubtful, Out or IR (or newly Questionable). " +
                            "Projection against the last four games.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (league != null) {
                        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !freeOnly, onClick = { freeOnly = false }, label = { Text("All") })
                            FilterChip(
                                selected = freeOnly,
                                onClick = { freeOnly = true },
                                label = { Text("Free agents") },
                                modifier = Modifier.testTag("chip:free"),
                            )
                        }
                    }
                    val rows = state.rows.map { it to ownerOf(it.beneficiary.player.playerId, league, mine) }
                        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
                    if (rows.isEmpty()) {
                        Text("None right now.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { (row, _) -> row.beneficiary.player.playerId }) { (row, owner) ->
                            OpportunityListRow(row, owner, onPlayer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpportunityListRow(row: OpportunityRow, owner: Owner?, onPlayer: (String) -> Unit) {
    val b = row.beneficiary
    val player = b.player
    Row(
        Modifier.fillMaxWidth().clickable { onPlayer(player.playerId) }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("opp:${player.playerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(player.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${Position.label(player.position)} · ${player.team} · moves up to ${player.position}${b.toRank} (was ${player.position}${b.fromRank})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                b.injured.joinToString("; ") { "${player.position}${it.rank} ${it.name} is ${statusWord(it.abbr)}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                listOfNotNull(
                    row.projected?.let { "Projected ${points(it)}" },
                    row.recent?.let { "last four ${points(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        row.uptick?.let { Text("${if (it >= 0) "+" else ""}${points(it)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}

private fun points(value: Double): String = String.format(Locale.US, "%.1f", value)
