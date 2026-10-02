package dev.gridiron.core.data.live

import dev.gridiron.core.projections.Lineups
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One player on a league team. [playerId] is the app's id, null when the ESPN id isn't in `player_xref`. */
public data class LeaguePlayer(val espnId: String, val name: String, val slot: String, val playerId: String? = null)

/** One team in the league, with its record and roster. [rank] is by wins, then points for. */
public data class LeagueTeam(
    val id: Int,
    val name: String,
    val owner: String?,
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val pointsFor: Double,
    val pointsAgainst: Double,
    val rank: Int,
    val players: List<LeaguePlayer>,
    /** ESPN's owner ids (SWIDs), for finding the user's own team. */
    val ownerIds: List<String> = emptyList(),
)

/** A league as last read from ESPN. */
public data class FantasyLeague(
    val leagueId: String,
    val name: String,
    val season: Int,
    val week: Int,
    val teams: List<LeagueTeam>,
    val fetchedAtMillis: Long = 0,
    /** Starting slots by label (`RB` to 2, `FLEX` to 1), bench and IR left out; empty when the snapshot has none. */
    val lineupSlots: Map<String, Int> = emptyMap(),
)

/**
 * The user's own team in a [season]'s league with the starting [slots] to fill: the league's own, or the usual nine
 * when the snapshot has none ([slotsAreDefault]).
 */
public data class MyTeam(
    val teamName: String,
    val season: Int,
    val players: List<LeaguePlayer>,
    val slots: Map<String, Int>,
    val slotsAreDefault: Boolean,
)

/** The user's opponent for a week as a roster on the user's slots; [team] is null when there is none, and [message] says why. */
public data class OpponentResult(val team: MyTeam?, val message: String?)

/** Team [teamId] as the user's own; null when there is no such team. */
public fun FantasyLeague.myTeam(teamId: Int?): MyTeam? {
    val team = teams.firstOrNull { it.id == teamId } ?: return null
    val own = lineupSlots.isNotEmpty()
    return MyTeam(team.name, season, team.players, if (own) lineupSlots else Lineups.DEFAULT_SLOTS, !own)
}

/** Everyone on any team of a [season]'s league who is matched to an app player id, as of [fetchedAtMillis]. */
public data class LeagueRostered(val playerIds: Set<String>, val season: Int, val fetchedAtMillis: Long)

/** The league's rostered players; an ESPN player the app can't match is left out (he has no stats here anyway). */
public fun FantasyLeague.rostered(): LeagueRostered =
    LeagueRostered(teams.flatMap { t -> t.players.mapNotNull { it.playerId } }.toSet(), season, fetchedAtMillis)

/**
 * One player in a matchup lineup. [espnPoints] is ESPN's own score for the week (the league's scoring); [appPoints] is
 * the app's, under the active profile, null when the player isn't matched or has no stats that week.
 */
public data class MatchupPlayer(
    val espnId: String,
    val name: String,
    val slot: String,
    val espnPoints: Double?,
    val playerId: String? = null,
    val appPoints: Double? = null,
)

/** One team's side of a matchup. [appTotal] sums the starters' [MatchupPlayer.appPoints], null when none has one. */
public data class MatchupSide(
    val teamId: Int,
    val espnTotal: Double,
    val lineup: List<MatchupPlayer>,
    val appTotal: Double? = null,
)

/** One head-to-head of [week]; [away] is null for a bye. */
public data class LeagueMatchup(val week: Int, val home: MatchupSide, val away: MatchupSide?)

/**
 * ESPN's fantasy API (unofficial). Like [EspnParser] it walks the JSON tree and
 * skips what it can't read; a response that isn't a league, or whose teams all
 * fail, is a [LiveFormatException].
 */
internal object EspnFantasyParser {
    fun url(leagueId: String, season: Int): String =
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season/segments/0/leagues/$leagueId" +
            "?view=mTeam&view=mRoster&view=mStandings&view=mSettings"

    fun matchupsUrl(leagueId: String, season: Int, week: Int): String =
        "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/$season/segments/0/leagues/$leagueId" +
            "?view=mMatchup&view=mMatchupScore&scoringPeriodId=$week"

    /** The order a lineup reads in: starters by position, then the bench and injured reserve. */
    private val SLOT_ORDER = listOf("QB", "RB", "WR", "TE", "RB/WR", "WR/TE", "RB/WR/TE", "FLEX", "OP", "D/ST", "K", "BE", "IR")

    private val SLOTS = mapOf(
        0 to "QB", 2 to "RB", 4 to "WR", 6 to "TE", 16 to "D/ST", 17 to "K", 23 to "FLEX",
        20 to "BE", 21 to "IR", 7 to "OP", 3 to "RB/WR", 5 to "WR/TE", 25 to "RB/WR/TE",
    )

    /** ESPN's pro team ids, as nflverse writes the teams; a D/ST's player id is -16000 minus its team's. */
    private val PRO_TEAMS = mapOf(
        1 to "ATL", 2 to "BUF", 3 to "CHI", 4 to "CIN", 5 to "CLE", 6 to "DAL", 7 to "DEN", 8 to "DET",
        9 to "GB", 10 to "TEN", 11 to "IND", 12 to "KC", 13 to "LV", 14 to "LA", 15 to "MIA", 16 to "MIN",
        17 to "NE", 18 to "NO", 19 to "NYG", 20 to "NYJ", 21 to "PHI", 22 to "ARI", 23 to "PIT", 24 to "LAC",
        25 to "SF", 26 to "SEA", 27 to "TB", 28 to "WAS", 29 to "CAR", 30 to "JAX", 33 to "BAL", 34 to "HOU",
    )

    /** The app's id for a D/ST, from its ESPN player id. */
    fun dstPlayerId(espnId: String): String? {
        val n = espnId.toIntOrNull() ?: return null
        return if (n < -16000) PRO_TEAMS[-16000 - n]?.let { "DST_$it" } else null
    }

    fun parse(text: String, leagueId: String, fetchedAtMillis: Long): FantasyLeague {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't league JSON")
        val teams = root.array("teams") ?: throw LiveFormatException("ESPN changed its league format (no teams list)")
        val members = root.array("members").orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { m -> m.string("id")?.let { it to (m.string("displayName") ?: "") } }.toMap()
        val parsed = teams.mapNotNull { (it as? JsonObject)?.let { t -> team(t, members) } }
        if (parsed.isEmpty() && teams.isNotEmpty()) throw LiveFormatException("ESPN changed its league format (no team could be read)")
        val ranked = parsed.sortedWith(compareByDescending<LeagueTeam> { (it.wins * 2 + it.ties).toDouble() }.thenByDescending { it.pointsFor })
            .mapIndexed { i, t -> t.copy(rank = i + 1) }
        return FantasyLeague(
            leagueId = leagueId,
            name = root.obj("settings")?.string("name") ?: "League $leagueId",
            season = root.int("seasonId") ?: throw LiveFormatException("ESPN changed its league format (no season)"),
            week = root.int("scoringPeriodId") ?: 1,
            teams = ranked,
            fetchedAtMillis = fetchedAtMillis,
            lineupSlots = lineupSlots(root.obj("settings")?.obj("rosterSettings")?.obj("lineupSlotCounts")),
        )
    }

    /** ESPN's `lineupSlotCounts` (slot id to count) as starters by label; the bench, IR and unknown ids are dropped. */
    private fun lineupSlots(counts: JsonObject?): Map<String, Int> =
        counts?.entries.orEmpty().mapNotNull { (id, n) ->
            val label = id.toIntOrNull()?.let { SLOTS[it] }?.takeIf { it != "BE" && it != "IR" } ?: return@mapNotNull null
            val count = (n as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            label to count
        }.toMap()

    /**
     * [week]'s matchups from a `mMatchup` response: the `schedule` items of that matchup period. A matchup whose home
     * side can't be read is skipped; a missing away side is a bye. No schedule, or none readable, is a format error.
     */
    fun matchups(text: String, week: Int): List<LeagueMatchup> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't league JSON")
        val schedule = root.array("schedule")?.mapNotNull { it as? JsonObject }
            ?: throw LiveFormatException("ESPN changed its matchup format (no schedule)")
        val ofWeek = schedule.filter { it.int("matchupPeriodId") == week }
        val parsed = ofWeek.mapNotNull { m ->
            val home = m.obj("home")?.let(::side) ?: return@mapNotNull null
            LeagueMatchup(week, home, m.obj("away")?.let(::side))
        }
        if (parsed.isEmpty() && ofWeek.isNotEmpty()) throw LiveFormatException("ESPN changed its matchup format (no matchup could be read)")
        return parsed
    }

    private fun side(s: JsonObject): MatchupSide? {
        val teamId = s.int("teamId") ?: return null
        val entries = s.obj("rosterForCurrentScoringPeriod")?.array("entries").orEmpty().mapNotNull { (it as? JsonObject)?.let(::matchupPlayer) }
        return MatchupSide(
            teamId = teamId,
            espnTotal = s.double("totalPoints") ?: s.double("totalPointsLive") ?: 0.0,
            lineup = entries.sortedBy { SLOT_ORDER.indexOf(it.slot).let { i -> if (i < 0) SLOT_ORDER.size else i } },
        )
    }

    private fun matchupPlayer(e: JsonObject): MatchupPlayer? {
        val espnId = e.string("playerId") ?: return null
        val entry = e.obj("playerPoolEntry")
        val name = entry?.obj("player")?.string("fullName") ?: dstPlayerId(espnId)?.let { "${it.removePrefix("DST_")} D/ST" } ?: return null
        return MatchupPlayer(espnId, name, SLOTS[e.int("lineupSlotId")] ?: "BE", entry?.double("appliedStatTotal"))
    }

    private fun team(t: JsonObject, members: Map<String, String>): LeagueTeam? {
        val id = t.int("id") ?: return null
        val name = t.string("name")
            ?: listOfNotNull(t.string("location"), t.string("nickname")).joinToString(" ").takeIf { it.isNotBlank() }
            ?: "Team $id"
        val overall = t.obj("record")?.obj("overall")
        val ownerIds = (t.array("owners").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } + listOfNotNull(t.string("primaryOwner"))).distinct()
        val players = t.obj("roster")?.array("entries").orEmpty().mapNotNull { (it as? JsonObject)?.let(::player) }
        return LeagueTeam(
            id = id,
            name = name,
            owner = ownerIds.firstNotNullOfOrNull { members[it] }?.takeIf { it.isNotBlank() },
            wins = overall?.int("wins") ?: 0,
            losses = overall?.int("losses") ?: 0,
            ties = overall?.int("ties") ?: 0,
            pointsFor = overall?.double("pointsFor") ?: 0.0,
            pointsAgainst = overall?.double("pointsAgainst") ?: 0.0,
            rank = 0,
            players = players,
            ownerIds = ownerIds,
        )
    }

    private fun player(e: JsonObject): LeaguePlayer? {
        val espnId = e.string("playerId") ?: return null
        val info = e.obj("playerPoolEntry")?.obj("player")
        val name = info?.string("fullName") ?: dstPlayerId(espnId)?.let { "${it.removePrefix("DST_")} D/ST" } ?: return null
        return LeaguePlayer(espnId, name, SLOTS[e.int("lineupSlotId")] ?: "BE")
    }
}

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** The snapshot as a JSON document, so the last sync reads offline. */
internal fun FantasyLeague.toJson(): String = buildJsonObject {
    put("leagueId", leagueId)
    put("name", name)
    put("season", season)
    put("week", week)
    put("fetchedAtMillis", fetchedAtMillis)
    put("lineupSlots", buildJsonObject { lineupSlots.forEach { (slot, n) -> put(slot, n) } })
    put(
        "teams",
        buildJsonArray {
            for (t in teams) {
                add(
                    buildJsonObject {
                        put("id", t.id)
                        t.owner?.let { put("owner", it) }
                        put("name", t.name)
                        put("wins", t.wins)
                        put("losses", t.losses)
                        put("ties", t.ties)
                        put("pointsFor", t.pointsFor)
                        put("pointsAgainst", t.pointsAgainst)
                        put("rank", t.rank)
                        put("ownerIds", buildJsonArray { t.ownerIds.forEach { add(JsonPrimitive(it)) } })
                        put(
                            "players",
                            buildJsonArray {
                                for (p in t.players) {
                                    add(
                                        buildJsonObject {
                                            put("espnId", p.espnId)
                                            put("name", p.name)
                                            put("slot", p.slot)
                                            p.playerId?.let { put("playerId", it) }
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        },
    )
}.toString()

/** Reads [toJson]'s document back; null if it isn't one. */
internal fun fantasyLeagueFromJson(text: String): FantasyLeague? = try {
    val o = Json.parseToJsonElement(text) as JsonObject
    FantasyLeague(
        leagueId = o.string("leagueId")!!,
        name = o.string("name")!!,
        season = o.int("season")!!,
        week = o.int("week")!!,
        fetchedAtMillis = (o["fetchedAtMillis"] as? JsonPrimitive)?.longOrNull ?: 0,
        lineupSlots = o.obj("lineupSlots")?.entries.orEmpty().mapNotNull { (slot, n) -> (n as? JsonPrimitive)?.intOrNull?.let { slot to it } }.toMap(),
        teams = o.array("teams")!!.map { e ->
            val t = e as JsonObject
            LeagueTeam(
                id = t.int("id")!!,
                name = t.string("name")!!,
                owner = t.string("owner"),
                wins = t.int("wins")!!,
                losses = t.int("losses")!!,
                ties = t.int("ties")!!,
                pointsFor = t.double("pointsFor")!!,
                pointsAgainst = t.double("pointsAgainst")!!,
                rank = t.int("rank")!!,
                ownerIds = t.array("ownerIds").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                players = t.array("players").orEmpty().map { pe ->
                    val p = pe as JsonObject
                    LeaguePlayer(p.string("espnId")!!, p.string("name")!!, p.string("slot")!!, p.string("playerId"))
                },
            )
        },
    )
} catch (_: Exception) {
    null
}
