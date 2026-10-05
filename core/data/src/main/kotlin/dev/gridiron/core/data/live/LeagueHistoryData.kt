package dev.gridiron.core.data.live

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One team's season: its ESPN owner ([ownerId], null when ESPN names none), record, [finalRank] (null until set) and [playoffSeed]. */
public data class HistoryTeam(
    val id: Int,
    val name: String,
    val ownerId: String?,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val pointsFor: Double,
    val pointsAgainst: Double,
    val finalRank: Int?,
    val playoffSeed: Int?,
)

/** A regular-season ([playoff] false) or winners-bracket game; [winner] is ESPN's `HOME`, `AWAY`, `TIE` or `UNDECIDED`; [awayId] null for a bye. */
public data class HistoryGame(
    val week: Int,
    val homeId: Int,
    val awayId: Int?,
    val homePoints: Double,
    val awayPoints: Double,
    val winner: String,
    val playoff: Boolean,
) {
    val decided: Boolean get() = awayId != null && winner in DECIDED

    private companion object {
        val DECIDED = setOf("HOME", "AWAY", "TIE")
    }
}

/** One season of the league; [members] maps owner ids to ESPN display names. */
public data class HistorySeason(
    val season: Int,
    val teams: List<HistoryTeam>,
    val members: Map<String, String>,
    val games: List<HistoryGame>,
    val playoffTeams: Int?,
)

/**
 * A season of a league from ESPN's `mTeam`, `mStandings`, `mSettings` and `mMatchupScore` views. The shapes are from
 * memory, unverified against a live league: `teams[].primaryOwner`, `rankCalculatedFinal`, `playoffSeed`,
 * `record.overall`; `schedule[]` with `matchupPeriodId`, `winner`, `playoffTierType`, `home`/`away` `teamId` and
 * `totalPoints`; `status.previousSeasons`. Consolation games (any tier but `NONE` and `WINNERS_BRACKET`) are dropped.
 */
internal object EspnHistoryParser {
    private const val VIEWS = "view=mTeam&view=mStandings&view=mSettings&view=mMatchupScore"
    private const val FIRST_CURRENT_API = 2018

    /** ESPN serves seasons before 2018 from `leagueHistory`, answered as a one-element array. */
    fun url(leagueId: String, season: Int): String = if (season >= FIRST_CURRENT_API) {
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season/segments/0/leagues/$leagueId?$VIEWS"
    } else {
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/leagueHistory/$leagueId?seasonId=$season&$VIEWS"
    }

    fun statusUrl(leagueId: String, season: Int): String =
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season/segments/0/leagues/$leagueId?view=mStatus"

    fun parse(text: String, season: Int): HistorySeason {
        val root = root(text) ?: throw LiveFormatException("ESPN sent something that isn't a league season")
        val teams = root.array("teams")?.mapNotNull { (it as? JsonObject)?.let(::team) }
            ?: throw LiveFormatException("ESPN changed its league format (no teams in $season)")
        val members = root.array("members").orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { m -> m.string("id")?.let { id -> m.string("displayName")?.let { id to it } } }.toMap()
        return HistorySeason(
            season = root.int("seasonId") ?: season,
            teams = teams,
            members = members,
            games = root.array("schedule").orEmpty().mapNotNull { (it as? JsonObject)?.let(::game) },
            playoffTeams = root.obj("settings")?.obj("scheduleSettings")?.int("playoffTeamCount")?.takeIf { it > 0 },
        )
    }

    /** The league's earlier seasons, oldest first; empty when the answer has none or can't be read. */
    fun previousSeasons(text: String): List<Int> =
        root(text)?.obj("status")?.array("previousSeasons").orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.sorted()

    private fun root(text: String): JsonObject? = try {
        when (val e = Json.parseToJsonElement(text)) {
            is JsonObject -> e
            is JsonArray -> e.firstOrNull() as? JsonObject
            else -> null
        }
    } catch (_: SerializationException) {
        null
    }

    private fun team(t: JsonObject): HistoryTeam? {
        val overall = t.obj("record")?.obj("overall")
        return HistoryTeam(
            id = t.int("id") ?: return null,
            name = t.string("name") ?: listOfNotNull(t.string("location"), t.string("nickname")).joinToString(" ").ifBlank { null } ?: return null,
            ownerId = t.string("primaryOwner") ?: (t["owners"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content },
            wins = overall?.int("wins") ?: 0,
            losses = overall?.int("losses") ?: 0,
            ties = overall?.int("ties") ?: 0,
            pointsFor = overall?.double("pointsFor") ?: 0.0,
            pointsAgainst = overall?.double("pointsAgainst") ?: 0.0,
            finalRank = t.int("rankCalculatedFinal")?.takeIf { it > 0 },
            playoffSeed = t.int("playoffSeed")?.takeIf { it > 0 },
        )
    }

    private fun game(g: JsonObject): HistoryGame? {
        val tier = g.string("playoffTierType") ?: "NONE"
        if (tier != "NONE" && tier != WINNERS) return null
        val home = g.obj("home") ?: return null
        val away = g.obj("away")
        return HistoryGame(
            week = g.int("matchupPeriodId") ?: return null,
            homeId = home.int("teamId") ?: return null,
            awayId = away?.int("teamId"),
            homePoints = home.double("totalPoints") ?: 0.0,
            awayPoints = away?.double("totalPoints") ?: 0.0,
            winner = g.string("winner") ?: "UNDECIDED",
            playoff = tier == WINNERS,
        )
    }

    const val WINNERS = "WINNERS_BRACKET"
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** The season in ESPN's own shape, so [historySeasonFromJson] reads it with the same parser. */
internal fun HistorySeason.toJson(): String = buildJsonObject {
    put("seasonId", season)
    put("settings", buildJsonObject { put("scheduleSettings", buildJsonObject { playoffTeams?.let { put("playoffTeamCount", it) } }) })
    put("members", buildJsonArray { members.forEach { (id, name) -> add(buildJsonObject { put("id", id); put("displayName", name) }) } })
    put(
        "teams",
        buildJsonArray {
            for (t in teams) {
                add(
                    buildJsonObject {
                        put("id", t.id)
                        put("name", t.name)
                        t.ownerId?.let { put("primaryOwner", it) }
                        put("rankCalculatedFinal", t.finalRank ?: 0)
                        put("playoffSeed", t.playoffSeed ?: 0)
                        put(
                            "record",
                            buildJsonObject {
                                put(
                                    "overall",
                                    buildJsonObject {
                                        put("wins", t.wins)
                                        put("losses", t.losses)
                                        put("ties", t.ties)
                                        put("pointsFor", t.pointsFor)
                                        put("pointsAgainst", t.pointsAgainst)
                                    },
                                )
                            },
                        )
                    },
                )
            }
        },
    )
    put(
        "schedule",
        buildJsonArray {
            for (g in games) {
                add(
                    buildJsonObject {
                        put("matchupPeriodId", g.week)
                        put("winner", g.winner)
                        put("playoffTierType", if (g.playoff) EspnHistoryParser.WINNERS else "NONE")
                        put("home", buildJsonObject { put("teamId", g.homeId); put("totalPoints", g.homePoints) })
                        g.awayId?.let { a -> put("away", buildJsonObject { put("teamId", a); put("totalPoints", g.awayPoints) }) }
                    },
                )
            }
        },
    )
}.toString()

/** Reads [toJson]'s document back; null if it isn't one. */
internal fun historySeasonFromJson(text: String): HistorySeason? = try {
    run {
        val season = (Json.parseToJsonElement(text) as? JsonObject)?.int("seasonId") ?: return@run null
        EspnHistoryParser.parse(text, season)
    }
} catch (_: LiveFormatException) {
    null
} catch (_: SerializationException) {
    null
}
