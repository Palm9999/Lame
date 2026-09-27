package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import dev.gridiron.core.forecast.PropsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** What Settings shows about props (spec §5). */
public data class PropsStatus(
    /** The Odds API's `x-requests-remaining` after the last call; null before any. */
    val creditsLeft: Int?,
    /** When props last arrived for a game; null before any. */
    val fetchedAt: Instant?,
    /** Why the last refresh stopped short; null when it didn't. */
    val error: String?,
)

/**
 * 00:00 UTC on the first Wednesday after [t]. An NFL week runs from Thursday
 * night to Monday night, and Monday night ends on Tuesday in UTC.
 */
internal fun nextWednesday(t: Instant): Instant =
    t.atZone(ZoneOffset.UTC).toLocalDate()
        .with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY))
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()

/**
 * The upcoming week's games: those not yet kicked off at [now] that start
 * before the Wednesday after the first of them. Games with a team the app
 * doesn't know are left out.
 */
internal fun upcomingWeek(events: List<OddsEvent>, now: Instant): List<StoredEvent> {
    val ahead = events.filter { it.commence > now }
    val first = ahead.minOfOrNull { it.commence } ?: return emptyList()
    val cutoff = nextWednesday(first)
    return ahead.filter { it.commence < cutoff }.mapNotNull { e ->
        val home = NFL_TEAMS[e.home] ?: return@mapNotNull null
        val away = NFL_TEAMS[e.away] ?: return@mapNotNull null
        StoredEvent(e.id, e.commence, home, away)
    }
}

/**
 * The upcoming week's player props from The Odds API, kept in [db] beside
 * ESPN's news and injuries. Fetching is rationed (spec §5): the free events
 * list, then each of the week's games that hasn't kicked off and wasn't
 * fetched in the last 24 hours, and never a call that could overdraw the
 * credits `x-requests-remaining` reported.
 *
 * Nothing here throws but to cancel. A refresh that stops short returns why,
 * and Settings shows it. Whatever was fetched before stays. The key goes
 * only into Odds API URLs; it is replaced in every message.
 */
public class PropsRepository(
    private val db: LiveDb,
    private val http: HttpClient,
    private val clock: () -> Instant = Instant::now,
) {
    private val fetching = Mutex()
    private val changes = MutableStateFlow(0L)

    /** Credits left, when props last arrived, and the last refresh's error. Empty if live.db can't be read. */
    public val status: Flow<PropsStatus> = changes.map {
        readOr(PropsStatus(null, null, null)) { c ->
            PropsStatus(
                c.meta(CREDITS)?.toIntOrNull(),
                c.meta(FETCHED_AT)?.toLongOrNull()?.let(Instant::ofEpochMilli),
                c.meta(ERROR)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Fetches what's due with [key]. Returns why it stopped short, or null when it didn't. */
    public suspend fun refresh(key: String): String? = fetching.withLock {
        val error = try {
            fetch(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            describe(e)
        }?.replace(key, "…")
        try {
            db.write { it.setMeta(ERROR, error.orEmpty()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // live.db can't be written: the error still goes back for the toast.
        }
        changes.value += 1
        error
    }

    /** Props for every stored game that has any, or null when none does. */
    public suspend fun snapshot(): PropsSnapshot? =
        readOr(null) { c -> c.propsSnapshot().takeIf { it.events.isNotEmpty() } }

    private suspend fun fetch(key: String): String? {
        val now = clock()
        val listing = call(OddsApi.eventsUrl(key))
        problem(listing)?.let { return it }
        val week = upcomingWeek(OddsApi.events(listing.body), now)
        db.write { c ->
            c.pruneProps(now.minus(PRUNE_AFTER))
            c.saveEvents(week)
        }
        val fetched = db.read { it.propFetchTimes() }
        for (event in week.sortedBy { it.commence }) {
            val last = fetched[event.id]
            if (last != null && Duration.between(last, now) < REFETCH_AFTER) continue
            val left = db.read { it.meta(CREDITS)?.toIntOrNull() }
            if (left != null && left < OddsApi.ODDS_CALL_COST) return "out of Odds API credits ($left left)"
            val response = call(OddsApi.oddsUrl(key, event.id))
            if (response.code == HTTP_NOT_FOUND) continue // taken off the board
            problem(response)?.let { return it }
            val quotes = OddsApi.quotes(response.body)
            db.write { c ->
                c.saveLines(event.id, quotes, now)
                c.setMeta(FETCHED_AT, now.toEpochMilli().toString())
            }
        }
        return null
    }

    /** One GET, recording the credits it reports. */
    private suspend fun call(url: String): HttpResponse {
        val response = http.get(url)
        response.header("x-requests-remaining")?.trim()?.toDoubleOrNull()?.let { left ->
            db.write { it.setMeta(CREDITS, left.toInt().toString()) }
        }
        return response
    }

    private fun problem(r: HttpResponse): String? {
        if (r.code == HTTP_OK) return null
        val detail = OddsApi.errorMessage(r.body)?.let { " ($it)" }.orEmpty()
        return when (r.code) {
            HTTP_UNAUTHORIZED -> "the Odds API refused the key$detail"
            HTTP_TOO_MANY -> "the Odds API is limiting requests$detail"
            else -> "the Odds API answered HTTP ${r.code}$detail"
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is LiveFormatException -> e.message ?: "the Odds API changed its format"
        is IOException -> "couldn't reach the Odds API"
        else -> "couldn't save props (${e::class.simpleName})"
    }

    private suspend fun <T> readOr(fallback: T, block: (SQLiteConnection) -> T): T =
        try {
            db.read(block)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    private companion object {
        val REFETCH_AFTER: Duration = Duration.ofHours(24)

        /** Spec §5, "pruned after the game": half a day after kickoff, when every game is over. */
        val PRUNE_AFTER: Duration = Duration.ofHours(12)

        const val CREDITS = "props_credits_left"
        const val FETCHED_AT = "props_fetched_at"
        const val ERROR = "props_error"
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
    }
}
