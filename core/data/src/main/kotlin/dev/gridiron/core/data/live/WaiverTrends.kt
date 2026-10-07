package dev.gridiron.core.data.live

import androidx.sqlite.SQLiteConnection
import dev.gridiron.core.data.PlayerDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** One player's share of ESPN's public leagues: [percentOwned] rostering him, ESPN's own [percentChange] beside it. */
public data class EspnRostership(
    val espnId: String,
    val name: String,
    val position: String?,
    val team: String?,
    val percentOwned: Double,
    val percentChange: Double,
)

/**
 * One player's roster trend. [playerId] is the app's id, null when `player_xref` doesn't know him; [weekChange] is his
 * roster % now less a week ago, from the phone's own daily snapshots, null until a week of them exists.
 */
public data class WaiverTrend(
    val espnId: String,
    val playerId: String?,
    val name: String,
    val position: String?,
    val team: String?,
    val percentOwned: Double,
    val espnChange: Double,
    val weekChange: Double?,
)

/** [trends] as last fetched; [weekly] when a snapshot from a week ago exists; [error] says why the newest fetch failed. */
public data class WaiverTrendsResult(val trends: List<WaiverTrend>, val weekly: Boolean, val error: String?)

/** ESPN's player list on its PPR default league, the 1,000 most rostered: everyone with any roster share. */
internal object EspnTrendsParser {
    fun url(season: Int): String =
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season/segments/0/leaguedefaults/3?view=kona_player_info"

    /** ESPN's filter: most rostered first, and only one stat line each (the full list is about 20 MB without it). */
    fun headers(season: Int): Map<String, String> = mapOf(
        "X-Fantasy-Filter" to
            """{"players":{"limit":$LIMIT,"sortPercOwned":{"sortPriority":1,"sortAsc":false},""" +
            """"filterStatsForTopScoringPeriodIds":{"value":1,"additionalValue":["00$season"]}}}""",
    )

    private const val LIMIT = 1000

    private val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 16 to "DST")

    fun parse(text: String): List<EspnRostership> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't its player list")
        val players = root.array("players") ?: throw LiveFormatException("ESPN changed its player list format (no players)")
        val parsed = players.mapNotNull { (it as? JsonObject)?.obj("player")?.let(::player) }
        if (parsed.isEmpty() && players.isNotEmpty()) throw LiveFormatException("ESPN changed its player list format (no roster percentages)")
        return parsed
    }

    private fun player(p: JsonObject): EspnRostership? {
        val id = p.number("id")?.toLong() ?: return null
        val ownership = p.obj("ownership") ?: return null
        return EspnRostership(
            espnId = id.toString(),
            name = p.string("fullName") ?: return null,
            position = p.int("defaultPositionId")?.let(POSITIONS::get),
            team = p.int("proTeamId")?.let(EspnFantasyParser::proTeam),
            percentOwned = ownership.number("percentOwned") ?: return null,
            percentChange = ownership.number("percentChange") ?: 0.0,
        )
    }
}

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Keeps [now]'s roster percentages as [day]'s (the day's last fetch wins) and drops snapshots older than thirty days. */
internal fun SQLiteConnection.saveRosterPct(day: Long, players: List<EspnRostership>) {
    prepare("INSERT OR REPLACE INTO roster_pct (espn_id, day, pct) VALUES (?, ?, ?)").use { st ->
        for (p in players) {
            st.reset()
            st.bindText(1, p.espnId)
            st.bindLong(2, day)
            st.bindDouble(3, p.percentOwned)
            st.step()
        }
    }
    prepare("DELETE FROM roster_pct WHERE day < ?").use {
        it.bindLong(1, day - SNAPSHOT_DAYS)
        it.step()
    }
}

/** The newest snapshot seven to ten days before [day], by ESPN id; empty when there is none. */
internal fun SQLiteConnection.weekAgoRosterPct(day: Long): Map<String, Double> {
    val base = prepare("SELECT MAX(day) FROM roster_pct WHERE day BETWEEN ? AND ?").use {
        it.bindLong(1, day - WEEK_MAX_DAYS)
        it.bindLong(2, day - WEEK_DAYS)
        if (it.step() && !it.isNull(0)) it.getLong(0) else null
    } ?: return emptyMap()
    return prepare("SELECT espn_id, pct FROM roster_pct WHERE day = ?").use { st ->
        st.bindLong(1, base)
        buildMap { while (st.step()) put(st.getText(0), st.getDouble(1)) }
    }
}

internal fun SQLiteConnection.hasRosterPct(day: Long): Boolean =
    prepare("SELECT 1 FROM roster_pct WHERE day = ? LIMIT 1").use {
        it.bindLong(1, day)
        it.step()
    }

private const val SNAPSHOT_DAYS = 30L
private const val WEEK_DAYS = 7L
private const val WEEK_MAX_DAYS = 10L

/** Each change against a week ago: a player missing then was below ESPN's list, rostered nowhere, so he counts from 0. */
internal fun trends(players: List<EspnRostership>, weekAgo: Map<String, Double>, playerIds: Map<String, String>): List<WaiverTrend> =
    players.map { p ->
        WaiverTrend(
            espnId = p.espnId,
            playerId = playerIds[p.espnId] ?: EspnFantasyParser.dstPlayerId(p.espnId),
            name = p.name,
            position = p.position,
            team = p.team,
            percentOwned = p.percentOwned,
            espnChange = p.percentChange,
            weekChange = if (weekAgo.isEmpty()) null else p.percentOwned - (weekAgo[p.espnId] ?: 0.0),
        )
    }

/**
 * Who ESPN's public leagues are adding and dropping. One keyless request (about 600 KB compressed) per open, at most
 * every [LiveRepository.STALE_AFTER]; each fetch also saves the day's roster percentages in [db], so after a week the
 * phone has its own weekly change. The last good list stays in memory when a fetch fails.
 */
public class WaiverTrendsRepository(
    private val db: LiveDb,
    private val http: HeaderHttpGet,
    private val players: PlayerDirectory,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private var last: Triple<Int, Instant, WaiverTrendsResult>? = null

    public suspend fun load(season: Int): WaiverTrendsResult = lock.withLock {
        val now = clock()
        last?.let { (s, at, result) -> if (s == season && Duration.between(at, now) < LiveRepository.STALE_AFTER) return@withLock result }
        val fetched = try {
            EspnTrendsParser.parse(http.get(EspnTrendsParser.url(season), EspnTrendsParser.headers(season)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val previous = last?.takeIf { it.first == season }?.third
            return@withLock WaiverTrendsResult(previous?.trends.orEmpty(), previous?.weekly ?: false, describe(e))
        }
        val day = now.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
        // The snapshot is a nicety: a full disk still shows ESPN's own change.
        val weekAgo = try {
            db.write { it.saveRosterPct(day, fetched) }
            db.read { it.weekAgoRosterPct(day) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val ids = players.playerIds(fetched.filter { it.position != "DST" }.map { it.espnId })
        val result = WaiverTrendsResult(trends(fetched, weekAgo, ids), weekAgo.isNotEmpty(), null)
        last = Triple(season, now, result)
        result
    }

    /**
     * The background job's daily snapshot: fetches (and so saves today's roster percentages) only when today has none,
     * so the weekly change doesn't wait on the screen being opened. A failure throws; the next run tries again.
     */
    public suspend fun snapshotDaily(season: Int) {
        val day = clock().atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
        if (db.read { it.hasRosterPct(day) }) return
        load(season).error?.let { throw IOException(it) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is LiveFormatException -> e.message ?: "ESPN changed its format"
        is IOException -> "couldn't reach ESPN"
        else -> e.message ?: e::class.simpleName.orEmpty()
    }
}
