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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gridiron.core.data.TdRegressionBoard
import dev.gridiron.core.data.TdRegressionRepository
import dev.gridiron.core.data.TdRegressionRow
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.data.live.MyTeam
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.designsystem.playerClick
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** "8 TDs on 4.6 expected (+3.4)". */
internal fun tdLine(row: TdRegressionRow): String =
    String.format(Locale.US, "%.0f TDs on %.1f expected (%s%.1f)", row.tds, row.expected, if (row.gap >= 0) "+" else "−", kotlin.math.abs(row.gap))

/** More → TD regression: who scores touchdowns well above or below what his usage predicts. */
@Composable
public fun TdRegressionRoute(
    season: Int,
    repository: TdRegressionRepository,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    /** Bumped when a refresh swaps in new stats: the board loads again. */
    dataVersion: Flow<Long> = flowOf(0L),
    /** Everyone on a league team, for the owner tags; null when no league is synced. */
    league: Flow<LeagueRostered?> = flowOf(null),
    myTeam: Flow<MyTeam?> = flowOf(null),
) {
    val version by dataVersion.collectAsStateWithLifecycle(initialValue = 0L)
    val taken by league.collectAsStateWithLifecycle(initialValue = null)
    val team by myTeam.collectAsStateWithLifecycle(initialValue = null)
    val board by produceState<Result<TdRegressionBoard>?>(null, season, version) {
        value = try {
            Result.success(repository.board(season))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    TdRegressionScreen(
        board, onPlayer, onBack,
        taken?.takeIf { it.season == season },
        team?.takeIf { it.season == season }?.players?.mapNotNull { it.playerId }?.toSet().orEmpty(),
    )
}

private const val SHOWN = 25

@Composable
public fun TdRegressionScreen(
    board: Result<TdRegressionBoard>?,
    onPlayer: (String) -> Unit,
    onBack: () -> Unit,
    league: LeagueRostered? = null,
    mine: Set<String> = emptySet(),
) {
    var cold by rememberSaveable { mutableStateOf(false) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var position by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("TD regression", onBack)
            val loaded = board?.getOrNull()
            when {
                board == null -> LoadingRows()
                loaded == null -> EmptyState("Couldn't read touchdowns. Refresh stats and try again.")
                loaded.throughWeek == 0 -> EmptyState("No expected touchdowns for this season yet.")
                else -> {
                    Text(
                        "Through week ${loaded.throughWeek}: touchdowns against those expected from where and how often each player " +
                            "got the ball (ffopportunity). Big gaps tend to shrink: running hot is a sell-high, running cold a buy-low.",
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !cold, onClick = { cold = false }, label = { Text("Running hot") }, modifier = Modifier.testTag("td:hot"))
                        FilterChip(selected = cold, onClick = { cold = true }, label = { Text("Running cold") }, modifier = Modifier.testTag("td:cold"))
                        for (code in listOf(null, "QB", "RB", "WR", "TE")) {
                            FilterChip(selected = position == code, onClick = { position = code }, label = { Text(code ?: "All") })
                        }
                        if (league != null) {
                            FilterChip(selected = freeOnly, onClick = { freeOnly = !freeOnly }, label = { Text("Free agents") }, modifier = Modifier.testTag("chip:free"))
                        }
                    }
                    val rows = loaded.rows.filter { (position == null || it.position == position) && (if (cold) it.gap < 0 else it.gap > 0) }
                        .let { if (cold) it.asReversed() else it }
                        .map { it to ownerOf(it.playerId, league, mine) }
                        .filter { (_, owner) -> !freeOnly || owner == Owner.FreeAgent }
                        .take(SHOWN)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { (row, _) -> row.playerId }) { (row, owner) ->
                            Column(Modifier.fillMaxWidth().playerClick(row.playerId, onPlayer).padding(horizontal = 16.dp, vertical = 8.dp).testTag("td:${row.playerId}")) {
                                Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(
                                        row.position, row.team, tdLine(row),
                                        when (owner) {
                                            Owner.FreeAgent -> "Free agent"
                                            Owner.Yours -> "Yours"
                                            is Owner.Other -> "On ${owner.team}"
                                            null -> null
                                        },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
