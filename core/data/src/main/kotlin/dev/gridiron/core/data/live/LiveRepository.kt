package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** How a live refresh went, feed by feed; null means that feed updated. */
public data class LiveResult(val newsError: String?, val injuriesError: String?) {
    public val ok: Boolean get() = newsError == null && injuriesError == null

    /** One line for a toast. */
    public val message: String
        get() = when {
            ok -> "Injuries and news updated."
            newsError != null && injuriesError != null -> "Couldn't update injuries or news: $injuriesError."
            injuriesError != null -> "News updated; injuries didn't: $injuriesError."
            else -> "Injuries updated; news didn't: $newsError."
        }
}

/**
 * ESPN injuries and news, kept in [db] and linked to app players through
 * [players]. Each feed updates on its own: if one fails, the other still
 * lands, and the failed one keeps its last data and "fetched at" time.
 */
public class LiveRepository(
    private val db: LiveDb,
    private val http: HttpGet,
    private val players: PlayerDirectory,
    private val clock: () -> Instant = Instant::now,
) {
    private val fetching = Mutex()
    private val _changes = MutableStateFlow(0L)

    /** Bumped after every write; screens reload when it changes. */
    public val changes: StateFlow<Long> = _changes.asStateFlow()

    /** The Grid's injury badge (Q, D, O, IR, …) by player id. Empty if live.db can't be read. */
    public val badges: Flow<Map<String, String>> = _changes.map {
        try {
            db.read { c -> c.badges() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Fetches both feeds now. */
    public suspend fun refresh(): LiveResult = fetching.withLock { fetchAll() }

    /** Fetches both feeds unless both arrived within [maxAge]; null when nothing was fetched. */
    public suspend fun refreshIfStale(maxAge: Duration = STALE_AFTER): LiveResult? = fetching.withLock {
        val asOf = fetchedAt()
        if (asOf != null && Duration.between(asOf, clock()) < maxAge) null else fetchAll()
    }

    /** When both feeds had last arrived: the older of the two times, or null if either never has. */
    public suspend fun fetchedAt(): Instant? = db.read { c ->
        val times = listOf(NEWS_AT, INJURIES_AT).map { c.meta(it)?.toLongOrNull() }
        if (times.any { it == null }) null else Instant.ofEpochMilli(times.filterNotNull().min())
    }

    public suspend fun news(): List<NewsItem> = db.read { it.news(null) }

    public suspend fun playerNews(playerId: String): List<NewsItem> = db.read { it.news(playerId) }

    public suspend fun status(playerId: String): LiveStatus? = db.read { it.status(playerId) }

    public suspend fun notes(playerId: String): List<InjuryNote> = db.read { it.notes(playerId) }

    public suspend fun injuries(): List<LiveInjury> = db.read { it.injuries() }

    private suspend fun fetchAll(): LiveResult {
        val news = fetch(EspnParser.NEWS_URL, EspnParser::news)
        val injuries = fetch(EspnParser.INJURIES_URL, EspnParser::injuries)
        val now = clock()
        val articles = news.getOrNull()
        val report = injuries.getOrNull()
        val wanted = db.read { it.unlinkedEspnIds() } +
            articles.orEmpty().flatMap { a -> a.athletes.map { it.espnId } } +
            report.orEmpty().map { it.espnId }
        val ids = players.playerIds(wanted)
        db.write { c ->
            if (articles != null) {
                c.saveNews(articles, ids)
                c.setMeta(NEWS_AT, now.toEpochMilli().toString())
            }
            if (report != null) {
                c.saveInjuries(report, ids, now)
                c.setMeta(INJURIES_AT, now.toEpochMilli().toString())
            }
            c.relink(ids)
            c.prune(now)
        }
        _changes.value += 1
        return LiveResult(news.exceptionOrNull()?.let(::describe), injuries.exceptionOrNull()?.let(::describe))
    }

    private suspend fun <T> fetch(url: String, parse: (String) -> T): Result<T> =
        try {
            Result.success(parse(http.get(url)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun describe(e: Throwable): String = when (e) {
        is LiveFormatException -> e.message ?: "ESPN changed its format"
        is IOException -> "couldn't reach ESPN"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }

    public companion object {
        /** Screens that show live data refetch it when it is older than this. */
        public val STALE_AFTER: Duration = Duration.ofMinutes(15)

        private const val NEWS_AT = "news_fetched_at"
        private const val INJURIES_AT = "injuries_fetched_at"
    }
}
