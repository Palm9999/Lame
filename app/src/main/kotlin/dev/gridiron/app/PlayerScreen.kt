package dev.gridiron.app

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.PlayerHeader
import dev.gridiron.core.data.live.InjuryNote
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.LiveStatus
import dev.gridiron.core.data.live.NewsItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Everything the Player page shows. The projections follow-up adds its card here. */
data class PlayerPage(
    val header: PlayerHeader?,
    val status: LiveStatus?,
    val notes: List<InjuryNote>,
    val news: List<NewsItem>,
    val asOf: Instant?,
)

private val NO_CHANGES: StateFlow<Long> = MutableStateFlow(0L)

@Composable
fun PlayerRoute(playerId: String, players: PlayerDirectory?, live: LiveRepository?, onBack: () -> Unit) {
    val version by (live?.changes ?: NO_CHANGES).collectAsState()
    var page by remember(playerId) { mutableStateOf<PlayerPage?>(null) }
    LaunchedEffect(Unit) { live?.refreshIfStale() }
    LaunchedEffect(playerId, version) {
        page = PlayerPage(
            header = players?.header(playerId),
            status = live?.status(playerId),
            notes = live?.notes(playerId).orEmpty(),
            news = live?.playerNews(playerId).orEmpty(),
            asOf = live?.fetchedAt(),
        )
    }
    val uri = LocalUriHandler.current
    PlayerScreen(playerId, page, liveAvailable = live != null, onBack = onBack, onOpen = { uri.openSafely(it) })
}

@Composable
fun PlayerScreen(playerId: String, page: PlayerPage?, liveAvailable: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
            }
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            LazyColumn {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            page.header?.name ?: playerId,
                            Modifier.testTag("playerName"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val detail = listOfNotNull(page.header?.position, page.header?.team)
                        if (detail.isNotEmpty()) {
                            Text(detail.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { SectionTitle("Status") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val s = page.status
                        when {
                            !liveAvailable -> Text("Live injuries and news aren't available.", style = MaterialTheme.typography.bodySmall)
                            s == null -> Text("No injury designation.", style = MaterialTheme.typography.bodyMedium)
                            else -> {
                                Text(s.status, color = injuryColor(s.abbr), fontWeight = FontWeight.Bold)
                                s.shortComment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                s.longComment?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (page.notes.isNotEmpty()) {
                    item { SectionTitle("Injury notes") }
                    items(page.notes) { note ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "${formatWhen(note.notedAt)} · ${note.status}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(note.comment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (liveAvailable) {
                    item { SectionTitle("News") }
                    if (page.news.isEmpty()) {
                        item { Text("No recent news.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(page.news, key = { it.id }) { n ->
                            Column(Modifier.fillMaxWidth().clickable { onOpen(n.url) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(n.headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(formatWhen(n.published), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                page.asOf?.let { asOf ->
                    item {
                        Text(
                            "ESPN · as of ${formatWhen(asOf)}",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

/** Red for Out, IR and Doubtful; the accent for Questionable. */
@Composable
internal fun injuryColor(abbr: String): Color = when (abbr) {
    "O", "IR", "D" -> MaterialTheme.colorScheme.error
    "Q" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurface
}
