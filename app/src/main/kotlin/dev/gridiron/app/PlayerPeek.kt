package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.live.NewsItem
import dev.gridiron.core.designsystem.LoadingRows
import dev.gridiron.core.designsystem.RangeBar
import dev.gridiron.core.designsystem.RemoteCircleImage
import dev.gridiron.core.designsystem.SpreadChart
import dev.gridiron.core.designsystem.StatusBadge
import dev.gridiron.core.designsystem.TeamChip
import dev.gridiron.core.designsystem.espnImageUrl
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.WeekRange
import dev.gridiron.feature.projections.ProjectionCard
import dev.gridiron.feature.projections.loadProjectionCard
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the quick look shows: the Player page's top, this week and his latest news. */
data class PlayerPeek(
    val name: String,
    val position: String?,
    val team: String?,
    val status: String?,
    val imageUrl: String?,
    val card: ProjectionCard?,
    val news: List<NewsItem>,
)

/** Reads [PlayerPeek] for [playerId] from what the Player page reads; any part that fails is left out. */
suspend fun loadPeek(playerId: String, deps: Deps): PlayerPeek {
    suspend fun <T> soft(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    val header = soft { deps.players?.header(playerId) }
    val status = soft { deps.live?.status(playerId) }
    val profile = soft { deps.scoring.active.first() }
    val card = if (profile != null) {
        soft { loadProjectionCard(deps.projections, playerId, header?.team, profile, header?.position?.let(Position::fromCode), status?.abbr) }
    } else {
        null
    }
    return PlayerPeek(
        name = header?.name ?: playerId,
        position = header?.position,
        team = header?.team,
        status = status?.abbr?.takeIf { it != "A" },
        imageUrl = espnImageUrl(playerId, soft { deps.players?.espnId(playerId) }),
        card = card,
        news = soft { deps.live?.playerNews(playerId) }.orEmpty().take(2),
    )
}

/** A long-pressed player at a glance, over whatever screen is open: his page and Compare one tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerPeekSheet(
    playerId: String,
    deps: Deps,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    /** The screen's own Add to Compare (the Grid's, over its weeks); null adds his whole season. */
    onCompare: (() -> Unit)? = null,
) {
    var peek by remember(playerId) { mutableStateOf<PlayerPeek?>(null) }
    var note by remember(playerId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playerId) { peek = loadPeek(playerId, deps) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("peek")) {
        val p = peek
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The buttons need only the id, so they show while the rest loads.
            if (p == null) {
                LoadingRows(rows = 3)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RemoteCircleImage(p.imageUrl, 56.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.position?.let(Position::label)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            p.team?.let { TeamChip(it) }
                            p.status?.let { StatusBadge(it) }
                        }
                    }
                }
                val card = p.card
                if (card != null && !card.bye && !card.notThisWeek && !card.out) {
                    Text(
                        "Week ${card.week}${card.matchup?.let { " $it" }.orEmpty()}: ${one(card.points)} pts · ${one(card.floor)}–${one(card.ceiling)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    card.spread?.let { SpreadChart(it.shares, it.low, it.high, card.floor, card.points, card.ceiling) }
                        ?: RangeBar(card.floor, card.points, card.ceiling, card.ceiling * 1.15)
                } else if (card != null) {
                    Text(if (card.bye) "Bye this week" else if (card.out) "Out this week" else "Not projected this week", style = MaterialTheme.typography.titleMedium)
                }
                card?.rosPoints?.let { Text("Rest of season ${one(it)} pts", style = MaterialTheme.typography.bodyMedium) }
                for (n in p.news) {
                    Text(n.headline, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(playerId) }, modifier = Modifier.testTag("peek:open")) { Text("Open page") }
                OutlinedButton(
                    onClick = {
                        if (onCompare != null) {
                            onCompare()
                            onDismiss()
                        } else {
                            scope.launch {
                                val season = currentSeason()
                                note = when (deps.tray.add(CompareSlot(playerId, season, WeekRange(1, WeekRange.lastRegularSeasonWeek(season))))) {
                                    CompareTrayRepository.AddResult.ADDED -> "Added to Compare"
                                    CompareTrayRepository.AddResult.ALREADY_THERE -> "Already in Compare"
                                    CompareTrayRepository.AddResult.FULL -> "Compare is full"
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("peek:compare"),
                ) { Text("Add to Compare") }
            }
        }
    }
}

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
