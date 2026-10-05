package dev.gridiron.core.data.live

import dev.gridiron.core.model.Roster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** A news story about [players] on the user's rosters. */
public data class NewsAlert(val item: NewsItem, val players: List<String>) {
    public val title: String get() = players.joinToString(", ")
    public val text: String get() = item.headline
}

public object NewsAlerts {
    /**
     * Stories published after [since] that tag a player in [rostered], newest first, at most [limit] (a burst of
     * stories, the first run after a long gap, shouldn't flood the shade).
     */
    public fun fresh(news: List<NewsItem>, rostered: Set<String>, since: Instant, limit: Int = MAX_PER_CHECK): List<NewsAlert> =
        news.filter { it.published.isAfter(since) }
            .mapNotNull { item ->
                val names = item.players.filter { it.playerId in rostered }.map { it.name }.distinct()
                if (names.isEmpty()) null else NewsAlert(item, names)
            }
            .sortedByDescending { it.item.published }
            .take(limit)

    public const val MAX_PER_CHECK: Int = 5
}

/**
 * ESPN stories about rostered players since the last check. It reads the news [live] already holds (the injury check
 * just refreshed it), keeps the newest story time seen in [stateFile], and only stores on its first run.
 */
public class NewsAlertChecker(
    private val live: LiveRepository,
    private val rosters: Flow<List<Roster>>,
    private val stateFile: File,
) {
    public suspend fun check(): List<NewsAlert> {
        val news = live.news()
        val newest = news.maxOfOrNull { it.published } ?: return emptyList()
        val since = withContext(Dispatchers.IO) { stateFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let(Instant::ofEpochMilli) }
        val latest = maxOf(newest, since ?: newest)
        withContext(Dispatchers.IO) { runCatching { stateFile.writeText(latest.toEpochMilli().toString()) } }
        if (since == null) return emptyList()
        return NewsAlerts.fresh(news, rosters.first().flatMapTo(HashSet()) { it.playerIds }, since)
    }

    /** Forgets the last story seen: alerts turned back on start from then, not from before. */
    public suspend fun forget() {
        withContext(Dispatchers.IO) { stateFile.delete() }
    }
}
