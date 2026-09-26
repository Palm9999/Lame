package dev.gridiron.app

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.data.live.NewsItem
import java.time.Instant

@Composable
fun NewsRoute(live: LiveRepository, onBack: () -> Unit, onPlayer: (String) -> Unit) {
    val version by live.changes.collectAsState()
    var items by remember { mutableStateOf<List<NewsItem>?>(null) }
    var asOf by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { error = live.refreshIfStale()?.newsError }
    LaunchedEffect(version) {
        items = live.news()
        asOf = live.fetchedAt()
    }
    val uri = LocalUriHandler.current
    NewsScreen(items, asOf, error, onBack, onOpen = { uri.openSafely(it) }, onPlayer = onPlayer)
}

/** ESPN headlines, newest first. [error] is set when the last fetch failed and older news is showing. */
@Composable
fun NewsScreen(
    items: List<NewsItem>?,
    asOf: Instant?,
    error: String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onPlayer: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Text("News", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            LiveCaption(asOf, error)
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> Message(if (error != null) "Couldn't reach ESPN. Try again later." else "No news yet.")
                else -> LazyColumn {
                    items(items, key = { it.id }) { item ->
                        NewsRow(item, onOpen, onPlayer)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, onOpen: (String) -> Unit, onPlayer: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().clickable { onOpen(item.url) }.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(item.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            item.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(formatWhen(item.published), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Only players the app knows get a chip; the article shows either way.
        val linked = item.players.mapNotNull { p -> p.playerId?.let { id -> id to p.name } }
        if (linked.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                linked.forEach { (id, name) ->
                    AssistChip(onClick = { onPlayer(id) }, label = { Text(name) }, modifier = Modifier.testTag("chip:$id"))
                }
            }
        }
    }
}

/** "ESPN · as of Sep 25, 7:01 PM", or why the data is older than it should be. */
@Composable
internal fun LiveCaption(asOf: Instant?, error: String?) {
    val text = when {
        error != null && asOf != null -> "Not updated: $error. Showing data as of ${formatWhen(asOf)}."
        error != null -> "Not updated: $error."
        asOf != null -> "ESPN · as of ${formatWhen(asOf)}"
        else -> "ESPN"
    }
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Opens [url] in the browser; with no browser installed, does nothing rather than crash. */
internal fun UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the headline stays on screen.
    } catch (_: IllegalArgumentException) {
        // A malformed link from the feed.
    }
}
