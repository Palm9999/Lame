package dev.gridiron.core.ingest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

/** One player's ESPN projection for one week, in our metric ids. A stat ESPN leaves out is zero. */
internal data class EspnProjection(val espnId: String, val season: Int, val week: Int, val stats: Map<String, Double>)

/** ESPN's response no longer has the shape [readEspnProjections] reads. */
internal class EspnFormatException(message: String) : Exception(message)

/**
 * ESPN's stat codes for the stats we project, checked against nflverse's actual games in 2025: every one matches
 * nflverse exactly in at least 93% of player-weeks (fumbles lost) and most in 99-100%.
 */
private val ESPN_STATS: Map<String, String> = mapOf(
    "1" to "completions", "3" to "passing_yards", "4" to "passing_tds", "19" to "passing_2pt", "20" to "interceptions",
    "64" to "sacks_taken", "211" to "passing_first_downs",
    "23" to "carries", "24" to "rushing_yards", "25" to "rushing_tds", "26" to "rushing_2pt", "212" to "rushing_first_downs",
    "58" to "targets", "53" to "receptions", "42" to "receiving_yards", "43" to "receiving_tds", "44" to "receiving_2pt",
    "213" to "receiving_first_downs", "72" to "fumbles_lost",
    // Kickers and D/STs (checked against nflverse 2025 the same way); Where we differ reads them, the blend doesn't.
    "74" to "fg_made_50", "77" to "fg_made_40_49", "80" to "fg_made_0_39", "85" to "fg_missed", "86" to "xp_made", "88" to "xp_missed",
    "99" to "dst_sacks", "95" to "dst_interceptions", "96" to "dst_fumble_recoveries", "98" to "dst_safeties",
    "120" to "points_allowed", "127" to "yards_allowed",
)

/** ESPN splits a D/ST's touchdowns four ways (interception, fumble, kickoff and punt returns); ours is one count. */
private val ESPN_DST_TDS = listOf("93", "94", "101", "102")

/** ESPN's pass attempts leave sacks out; ours count them. */
private const val ESPN_ATTEMPTS = "0"
private const val ESPN_SACKS = "64"

private val json = Json { ignoreUnknownKeys = true }

/**
 * [season]'s weekly projections (statSourceId 1, statSplitTypeId 1) from ESPN's `kona_player_info` view.
 * Throws [EspnFormatException] when the response isn't the shape it reads.
 */
internal fun readEspnProjections(input: InputStream, season: Int): List<EspnProjection> {
    val root = try {
        json.parseToJsonElement(input.bufferedReader().readText())
    } catch (e: SerializationException) {
        throw EspnFormatException("not JSON (${e.message})")
    }
    val players = (root as? JsonObject)?.get("players") as? JsonArray ?: throw EspnFormatException("no players list")
    return players.flatMap { entry ->
        val player = entry.field("player")
        val id = player.field("id").jsonPrimitive.content
        val stats = (player.jsonObject["stats"] as? JsonArray).orEmpty()
        stats.mapNotNull { s ->
            val o = s.jsonObject
            if (o.int("statSourceId") != 1 || o.int("statSplitTypeId") != 1 || o.int("seasonId") != season) return@mapNotNull null
            val week = o.int("scoringPeriodId") ?: return@mapNotNull null
            val raw = (o["stats"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 }
            val ours = ESPN_STATS.entries.associate { (code, metric) -> metric to (raw[code] ?: 0.0) } +
                ("attempts" to (raw[ESPN_ATTEMPTS] ?: 0.0) + (raw[ESPN_SACKS] ?: 0.0)) +
                ("dst_tds" to ESPN_DST_TDS.sumOf { raw[it] ?: 0.0 })
            EspnProjection(id, season, week, ours)
        }
    }
}

private fun JsonElement.field(name: String): JsonElement =
    (this as? JsonObject)?.get(name) ?: throw EspnFormatException("a player has no \"$name\"")

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
