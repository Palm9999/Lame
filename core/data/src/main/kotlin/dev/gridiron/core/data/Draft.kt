package dev.gridiron.core.data

import dev.gridiron.core.data.live.HttpGet
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import dev.gridiron.core.statquery.ValueMode
import dev.gridiron.core.statquery.normalizeSearch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import kotlin.random.Random

/**
 * One player on the draft board: Fantasy Football Calculator's average draft position ([adp], picks), his bye, and,
 * when he is matched to the app's players, his id and last season's points per game under the active profile.
 */
public data class BoardPlayer(
    val key: String,
    val name: String,
    val position: String,
    val team: String?,
    val adp: Double,
    val bye: Int?,
    val playerId: String? = null,
    val lastPerGame: Double? = null,
)

/** The board, or why there is none; [matched] counts the players tied to the app's own. */
public data class DraftBoardResult(val players: List<BoardPlayer>, val matched: Int, val message: String?)

/**
 * The draft board: Fantasy Football Calculator's ADP for [teams] teams in the scoring closest to the active profile
 * (PPR, half or standard), each player matched to the app's players by name and position (a D/ST by team) for last
 * season's points per game. The app's own projections don't exist before the season's first game, so this is the
 * board's whole basis.
 */
public class DraftRepository(private val executor: QueryExecutor, private val http: HttpGet) {
    public suspend fun board(year: Int, teams: Int, profile: ScoringProfile): DraftBoardResult {
        val raw = try {
            AdpParser.parse(http.get(AdpParser.url(AdpParser.format(profile), teams, year)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdpFormatException) {
            return DraftBoardResult(emptyList(), 0, e.message)
        } catch (e: IOException) {
            return DraftBoardResult(emptyList(), 0, "couldn't reach Fantasy Football Calculator (${e.message})")
        }
        if (raw.isEmpty()) return DraftBoardResult(emptyList(), 0, "no ADP for $year yet")
        val ids = try {
            match(raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap() // no stats yet: the board still has ADP
        }
        val perGame = try {
            lastSeason(ids.values.toSet(), year - 1, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val players = raw.map { p -> ids[p.key]?.let { id -> p.copy(playerId = id, lastPerGame = perGame[id]) } ?: p }
        return DraftBoardResult(players, ids.size, null)
    }

    /**
     * App ids by board key: a D/ST by its team, anyone else by name (suffixes like Jr. or III dropped on both sides, as
     * sources disagree on them) and position, the team breaking a tie.
     */
    private suspend fun match(players: List<BoardPlayer>): Map<String, String> {
        val positions = players.map { it.position }.filter { it != "DST" }.distinct()
        val rows = if (positions.isEmpty()) {
            emptyList()
        } else {
            executor.query(
                SqlQuery(
                    "SELECT player_id, search_name, position, team FROM player WHERE position IN (${positions.joinToString(",") { "?" }})",
                    positions.map { Bind.Text(it) },
                ),
            ) { listOf(it.text(0), it.text(1), it.textOrNull(2), it.textOrNull(3)) }
        }
        val byName = rows.groupBy { baseName(it[1]!!) }
        return players.mapNotNull { p ->
            if (p.position == "DST") return@mapNotNull p.team?.let { p.key to "DST_${nflTeam(it)}" }
            val same = byName[baseName(normalizeSearch(p.name))].orEmpty().filter { it[2] == p.position }
            val pick = same.singleOrNull() ?: same.firstOrNull { it[3] == p.team?.let(::nflTeam) } ?: return@mapNotNull null
            p.key to pick[0]!!
        }.toMap()
    }

    private suspend fun lastSeason(ids: Set<String>, season: Int, profile: ScoringProfile): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val q = StatQueryBuilder.grid(
            StatQuerySpec(
                season = season,
                weeks = WeekRange(1, 18),
                columns = listOf(StatColumn.FANTASY_POINTS),
                playerIds = ids,
                includeUnqualified = true,
                mode = ValueMode.PER_GAME,
                limit = StatQuerySpec.MAX_LIMIT,
                scoring = profile,
            ),
        )
        return executor.query(q.query) { it.text(0) to it.doubleOrNull(q.layout.valueIndex(StatColumn.FANTASY_POINTS)) }
            .mapNotNull { (id, v) -> v?.let { id to it } }.toMap()
    }

    private companion object {
        /** FFC's team codes where nflverse's differ. */
        val TEAMS = mapOf("LAR" to "LA", "JAC" to "JAX", "WSH" to "WAS")

        fun nflTeam(code: String) = TEAMS[code] ?: code

        private val SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv", "v")

        /** A search name without a trailing suffix: "james cook iii" and "james cook" agree. */
        fun baseName(search: String): String = search.split(' ').filter { it.isNotEmpty() }.let { parts ->
            if (parts.size > 2 && parts.last() in SUFFIXES) parts.dropLast(1) else parts
        }.joinToString(" ")
    }
}

/** FFC sent something the board can't read. */
public class AdpFormatException(message: String) : Exception(message)

public object AdpParser {
    /** PPR, half or standard, by what a WR's catch is worth in [profile]. */
    public fun format(profile: ScoringProfile): String = profile.receptionWeight(Position.WR).let {
        when {
            it >= 0.75 -> "ppr"
            it >= 0.25 -> "half-ppr"
            else -> "standard"
        }
    }

    public fun url(format: String, teams: Int, year: Int): String =
        "https://fantasyfootballcalculator.com/api/v1/adp/$format?teams=$teams&year=$year"

    private val POSITIONS = mapOf("QB" to "QB", "RB" to "RB", "WR" to "WR", "TE" to "TE", "PK" to "K", "DEF" to "DST")

    /** The `players` array, best ADP first; an unknown position is skipped. */
    public fun parse(text: String): List<BoardPlayer> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw AdpFormatException("Fantasy Football Calculator sent something that isn't ADP")
        val players = root["players"] as? JsonArray ?: throw AdpFormatException("Fantasy Football Calculator changed its ADP format (no players)")
        return players.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val pos = POSITIONS[(o["position"] as? JsonPrimitive)?.content] ?: return@mapNotNull null
            val adp = (o["adp"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            BoardPlayer(
                key = (o["player_id"] as? JsonPrimitive)?.content ?: "$name|$pos",
                name = name,
                position = pos,
                team = (o["team"] as? JsonPrimitive)?.content,
                adp = adp,
                bye = (o["bye"] as? JsonPrimitive)?.intOrNull,
            )
        }.sortedBy { it.adp }
    }
}

/**
 * Who to take next: the best-ADP players still on the board, a position the user's starting [slots] still need
 * moved up [NEED_BONUS] picks, no kicker or D/ST before the last two rounds, and none at a position already at its
 * [CAPS] limit. [round] counts from one; [rounds] is the draft's length.
 */
public object DraftAdvice {
    public fun suggestions(
        available: List<BoardPlayer>,
        mine: List<BoardPlayer>,
        slots: Map<String, Int>,
        round: Int,
        rounds: Int,
        limit: Int = 5,
    ): List<BoardPlayer> {
        val have = mine.groupingBy { it.position }.eachCount()
        val starters = mapOf(
            "QB" to (slots["QB"] ?: 1), "RB" to (slots["RB"] ?: 2), "WR" to (slots["WR"] ?: 2), "TE" to (slots["TE"] ?: 1),
            "K" to (slots["K"] ?: 1), "DST" to (slots["D/ST"] ?: 1),
        )
        val late = round >= rounds - 1
        return available.filter { p ->
            (have[p.position] ?: 0) < (CAPS[p.position] ?: Int.MAX_VALUE) &&
                (p.position !in setOf("K", "DST") || (late && (have[p.position] ?: 0) == 0))
        }.sortedBy { p -> p.adp - if ((have[p.position] ?: 0) < (starters[p.position] ?: 0)) NEED_BONUS else 0.0 }
            .take(limit)
    }

    /** How far a position the lineup still needs moves up the board: about two-thirds of a round. Judgment. */
    public const val NEED_BONUS: Double = 8.0

    /** Most a roster wants at each position: beyond these the picks are wasted depth. Judgment. */
    public val CAPS: Map<String, Int> = mapOf("QB" to 2, "RB" to 7, "WR" to 7, "TE" to 2, "K" to 1, "DST" to 1)
}

/**
 * A snake mock draft against bots: each bot takes one of [DraftAdvice]'s top three for its own roster, weighted
 * [BOT_WEIGHTS], so bots follow ADP and need without drafting the same way every time. Picks are board keys in
 * draft order; teams count from 0.
 */
public object MockDraft {
    /** The team making overall pick [n] (from 0): 0..teams-1 in odd rounds, back again in even ones. */
    public fun team(n: Int, teams: Int): Int = (n % teams).let { if ((n / teams) % 2 == 0) it else teams - 1 - it }

    /** "3.07": round and pick within it for overall pick [n] (from 0). */
    public fun label(n: Int, teams: Int): String = "${n / teams + 1}.${(n % teams + 1).toString().padStart(2, '0')}"

    /** [picks] with the bots' picks added until it's [slot]'s turn, the draft's [rounds] are done or the board runs out. */
    public fun run(
        board: List<BoardPlayer>,
        picks: List<String>,
        slot: Int,
        teams: Int,
        rounds: Int,
        slots: Map<String, Int>,
        random: Random,
    ): List<String> {
        val byKey = board.associateBy { it.key }
        val out = picks.toMutableList()
        while (out.size < teams * rounds && team(out.size, teams) != slot) {
            val t = team(out.size, teams)
            val taken = out.toSet()
            val roster = out.indices.filter { team(it, teams) == t }.mapNotNull { byKey[out[it]] }
            val options = DraftAdvice.suggestions(board.filter { it.key !in taken }, roster, slots, out.size / teams + 1, rounds, BOT_WEIGHTS.size)
            if (options.isEmpty()) break
            val w = BOT_WEIGHTS.take(options.size)
            var r = random.nextDouble() * w.sum()
            out += options[w.indices.firstOrNull { r -= w[it]; r < 0 } ?: options.lastIndex].key
        }
        return out
    }

    /** How often a bot takes its first, second and third choice. Judgment. */
    public val BOT_WEIGHTS: List<Double> = listOf(0.6, 0.25, 0.15)
}
