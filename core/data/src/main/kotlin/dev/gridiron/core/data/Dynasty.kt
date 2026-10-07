package dev.gridiron.core.data

import dev.gridiron.core.data.live.FantasyLeague
import dev.gridiron.core.data.live.HttpGet
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** The league shape FantasyCalc values for: [teams], [qbs] (2 for Superflex) and points per catch. */
public data class DynastyFormat(val teams: Int, val qbs: Int, val ppr: Double)

/** [league]'s teams (12 without one), Superflex when it starts an `OP`, and PPR, half or standard by a WR catch in [profile]. */
public fun dynastyFormat(league: FantasyLeague?, profile: ScoringProfile): DynastyFormat = DynastyFormat(
    teams = league?.teams?.size?.takeIf { it > 0 } ?: DEFAULT_TEAMS,
    qbs = if ((league?.lineupSlots?.get("OP") ?: 0) > 0) 2 else 1,
    ppr = profile.receptionWeight(Position.WR).let {
        when {
            it >= 0.75 -> 1.0
            it >= 0.25 -> 0.5
            else -> 0.0
        }
    },
)

private const val DEFAULT_TEAMS = 12

/**
 * One player's FantasyCalc values: [value] (dynasty) and [redraftValue] (this season only), both from real trades.
 * [rank] and [positionRank] count players only, by dynasty value; [espnId] is null when FantasyCalc has none, and
 * [playerId] when `player_xref` doesn't know him.
 */
public data class DynastyValue(
    val espnId: String?,
    val playerId: String?,
    val name: String,
    val position: String,
    val team: String?,
    val age: Double?,
    val value: Int,
    val redraftValue: Int,
    val rank: Int,
    val positionRank: Int,
    val trend30: Int,
)

/** [values] best first; [error] says why the newest fetch failed (the last good list is kept). */
public data class DynastyResult(val values: List<DynastyValue>, val error: String?)

internal class FantasyCalcFormatException : Exception("FantasyCalc changed its format")

internal object FantasyCalcParser {
    fun url(format: DynastyFormat): String {
        val ppr = if (format.ppr == 0.5) "0.5" else format.ppr.toInt().toString()
        return "https://api.fantasycalc.com/values/current?isDynasty=true&numQbs=${format.qbs}&numTeams=${format.teams}&ppr=$ppr"
    }

    private val POSITIONS = setOf("QB", "RB", "WR", "TE")

    /** Players only (rookie picks dropped), best dynasty value first, ranked again among themselves. */
    fun parse(text: String): List<DynastyValue> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (_: SerializationException) {
            null
        } ?: throw FantasyCalcFormatException()
        val players = root.mapNotNull { (it as? JsonObject)?.let(::entry) }
        if (players.isEmpty() && root.isNotEmpty()) throw FantasyCalcFormatException()
        val sorted = players.sortedByDescending { it.value }
        val byPosition = mutableMapOf<String, Int>()
        return sorted.mapIndexed { i, p -> p.copy(rank = i + 1, positionRank = byPosition.merge(p.position, 1, Int::plus)!!) }
    }

    private fun entry(e: JsonObject): DynastyValue? {
        val p = e["player"] as? JsonObject ?: return null
        val position = p.text("position")?.takeIf { it in POSITIONS } ?: return null
        return DynastyValue(
            espnId = p.text("espnId"),
            playerId = null,
            name = p.text("name") ?: return null,
            position = position,
            team = p.text("maybeTeam"),
            age = (p["maybeAge"] as? JsonPrimitive)?.doubleOrNull,
            value = e.int("value") ?: return null,
            redraftValue = e.int("redraftValue") ?: 0,
            rank = 0,
            positionRank = 0,
            trend30 = e.int("trend30Day") ?: 0,
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * FantasyCalc's dynasty and redraft values (`api.fantasycalc.com`, keyless), one list per [DynastyFormat], held in
 * memory for six hours; a failed fetch keeps the last good list and says why.
 */
public class DynastyRepository(
    private val http: HttpGet,
    private val players: PlayerDirectory,
    private val clock: () -> Instant = Instant::now,
) {
    private val lock = Mutex()
    private val cache = mutableMapOf<DynastyFormat, Pair<Instant, List<DynastyValue>>>()

    public suspend fun load(format: DynastyFormat): DynastyResult = lock.withLock {
        val now = clock()
        val cached = cache[format]
        if (cached != null && Duration.between(cached.first, now) < MAX_AGE) return@withLock DynastyResult(cached.second, null)
        val parsed = try {
            FantasyCalcParser.parse(http.get(FantasyCalcParser.url(format)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = when (e) {
                is FantasyCalcFormatException -> e.message!!
                is IOException -> "couldn't reach FantasyCalc"
                else -> e.message ?: e::class.simpleName.orEmpty()
            }
            return@withLock DynastyResult(cached?.second.orEmpty(), why)
        }
        val ids = players.playerIds(parsed.mapNotNull { it.espnId })
        val values = parsed.map { v -> v.copy(playerId = v.espnId?.let(ids::get)) }
        cache[format] = now to values
        DynastyResult(values, null)
    }

    private companion object {
        val MAX_AGE: Duration = Duration.ofHours(6)
    }
}
