package dev.gridiron.core.data.live

import dev.gridiron.core.projections.Lineups
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
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
    /** FAAB dollars spent this season; null when ESPN didn't say. */
    val faabSpent: Int? = null,
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
    /** The NFL weeks of the league's playoffs, from its schedule settings; empty when the snapshot has none. */
    val playoffWeeks: List<Int> = emptyList(),
    /** Each team's FAAB budget for the season; null when the league doesn't bid (or the snapshot predates it). */
    val faabBudget: Int? = null,
    /** How many teams make the playoffs; null when the snapshot has no schedule settings. */
    val playoffTeams: Int? = null,
    /** NFL weeks per regular-season matchup (ESPN's `matchupPeriodLength`, almost always one). */
    val periodWeeks: Int = 1,
)

/** The league, the user's team id (null when none is chosen) and the regular-season games still to play. */
public data class PlayoffPicture(val league: FantasyLeague, val myTeamId: Int?, val remaining: List<ScheduledGame>)

/** The weeks reviewed, newest first; [message] says why there are none. */
public data class LineupReviewResult(val weeks: List<WeekReview>, val message: String?)

/** [picture], or null with [message] saying why. */
public data class PlayoffPictureResult(val picture: PlayoffPicture?, val message: String?)

/** One head-to-head on ESPN's schedule: matchup period [period]; [awayId] null for a bye; [decided] once ESPN names a winner. */
public data class ScheduledGame(val period: Int, val homeId: Int, val awayId: Int?, val decided: Boolean)

/** NFL weeks 15-17: most leagues' playoffs, used when the league's own aren't known. */
public val DEFAULT_PLAYOFF_WEEKS: List<Int> = listOf(15, 16, 17)

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
    /** The league's playoff weeks, or [DEFAULT_PLAYOFF_WEEKS]. */
    val playoffWeeks: List<Int> = DEFAULT_PLAYOFF_WEEKS,
    /** FAAB dollars this team has left; null when the league doesn't bid or ESPN didn't say what it spent. */
    val faabLeft: Int? = null,
    /** The league's FAAB budget; null when it doesn't bid. */
    val faabBudget: Int? = null,
)

/** Every team but [teamId], each as a [MyTeam] on the league's slots, for trades; empty when [teamId] isn't in it. */
public fun FantasyLeague.otherTeams(teamId: Int?): List<MyTeam> {
    if (teams.none { it.id == teamId }) return emptyList()
    return teams.filter { it.id != teamId }.mapNotNull { myTeam(it.id) }
}

/** The user's opponent for a week as a roster on the user's slots; [team] is null when there is none, and [message] says why. */
public data class OpponentResult(val team: MyTeam?, val message: String?)

/** Team [teamId] as the user's own; null when there is no such team. */
public fun FantasyLeague.myTeam(teamId: Int?): MyTeam? {
    val team = teams.firstOrNull { it.id == teamId } ?: return null
    val own = lineupSlots.isNotEmpty()
    return MyTeam(
        team.name, season, team.players, if (own) lineupSlots else Lineups.DEFAULT_SLOTS, !own,
        playoffWeeks = playoffWeeks.ifEmpty { DEFAULT_PLAYOFF_WEEKS },
        faabLeft = faabBudget?.let { budget -> team.faabSpent?.let { (budget - it).coerceAtLeast(0) } },
        faabBudget = faabBudget,
    )
}

/**
 * Everyone on any team of a [season]'s league who is matched to an app player id, as of [fetchedAtMillis]; [owners]
 * names each one's team.
 */
public data class LeagueRostered(val playerIds: Set<String>, val season: Int, val fetchedAtMillis: Long, val owners: Map<String, String> = emptyMap())

/** The league's rostered players; an ESPN player the app can't match is left out (he has no stats here anyway). */
public fun FantasyLeague.rostered(): LeagueRostered =
    LeagueRostered(
        playerIds = teams.flatMap { t -> t.players.mapNotNull { it.playerId } }.toSet(),
        season = season,
        fetchedAtMillis = fetchedAtMillis,
        owners = teams.flatMap { t -> t.players.mapNotNull { p -> p.playerId?.let { it to t.name } } }.toMap(),
    )

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
    /** ESPN's position for him (`defaultPositionId`) as a `player.position` code; null when unknown. */
    val position: String? = null,
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
            playoffWeeks = playoffWeeks(root.obj("settings")?.obj("scheduleSettings")),
            playoffTeams = root.obj("settings")?.obj("scheduleSettings")?.int("playoffTeamCount")?.takeIf { it > 0 },
            periodWeeks = root.obj("settings")?.obj("scheduleSettings")?.int("matchupPeriodLength")?.takeIf { it > 0 } ?: 1,
            faabBudget = root.obj("settings")?.obj("acquisitionSettings")
                ?.takeIf { (it["isUsingAcquisitionBudget"] as? JsonPrimitive)?.booleanOrNull == true }
                ?.int("acquisitionBudget")?.takeIf { it > 0 },
        )
    }

    /**
     * The playoffs' NFL weeks from `scheduleSettings` (checked against ESPN's `leaguedefaults/3?view=mSettings`): the
     * regular season is `matchupPeriodCount` matchups of `matchupPeriodLength` weeks, then one round per halving of
     * `playoffTeamCount`, each `playoffMatchupPeriodLengthByRound` weeks long (or `playoffMatchupPeriodLength`, at least
     * one). Empty when the settings are missing or would run past week 18.
     */
    private fun playoffWeeks(s: JsonObject?): List<Int> {
        s ?: return emptyList()
        val matchups = s.int("matchupPeriodCount")?.takeIf { it > 0 } ?: return emptyList()
        val length = s.int("matchupPeriodLength")?.takeIf { it > 0 } ?: 1
        val teams = s.int("playoffTeamCount")?.takeIf { it > 1 } ?: return emptyList()
        var rounds = 0
        while ((1 shl rounds) < teams) rounds++
        val byRound = s.obj("playoffMatchupPeriodLengthByRound")
        val fallback = s.int("playoffMatchupPeriodLength")?.takeIf { it > 0 } ?: 1
        val weeks = (1..rounds).sumOf { r -> byRound?.int(r.toString())?.takeIf { it > 0 } ?: fallback }
        val first = matchups * length + 1
        val last = first + weeks - 1
        return if (last <= 18) (first..last).toList() else emptyList()
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

    /**
     * Every head-to-head on a `mMatchup` response's `schedule`, whatever its period: a game is [ScheduledGame.decided]
     * once its `winner` is HOME, AWAY or TIE (ESPN says UNDECIDED until then). No schedule is a format error.
     */
    fun schedule(text: String): List<ScheduledGame> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw LiveFormatException("ESPN sent something that isn't league JSON")
        val items = root.array("schedule")?.mapNotNull { it as? JsonObject }
            ?: throw LiveFormatException("ESPN changed its matchup format (no schedule)")
        return items.mapNotNull { m ->
            val period = m.int("matchupPeriodId") ?: return@mapNotNull null
            val home = m.obj("home")?.int("teamId") ?: return@mapNotNull null
            ScheduledGame(period, home, m.obj("away")?.int("teamId"), m.string("winner") in DECIDED)
        }
    }

    private val DECIDED = setOf("HOME", "AWAY", "TIE")

    /** ESPN's `defaultPositionId`s (from memory, unverified against a live league). */
    private val POSITIONS = mapOf(1 to "QB", 2 to "RB", 3 to "WR", 4 to "TE", 5 to "K", 16 to "DST")

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
        val position = if (dstPlayerId(espnId) != null) "DST" else entry?.obj("player")?.int("defaultPositionId")?.let { POSITIONS[it] }
        return MatchupPlayer(espnId, name, SLOTS[e.int("lineupSlotId")] ?: "BE", entry?.double("appliedStatTotal"), position = position)
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
            faabSpent = t.obj("transactionCounter")?.int("acquisitionBudgetSpent"),
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
    put("playoffWeeks", buildJsonArray { playoffWeeks.forEach { add(JsonPrimitive(it)) } })
    faabBudget?.let { put("faabBudget", it) }
    playoffTeams?.let { put("playoffTeams", it) }
    put("periodWeeks", periodWeeks)
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
                        t.faabSpent?.let { put("faabSpent", it) }
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
        playoffWeeks = o.array("playoffWeeks").orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull },
        faabBudget = o.int("faabBudget"),
        playoffTeams = o.int("playoffTeams"),
        periodWeeks = o.int("periodWeeks") ?: 1,
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
                faabSpent = t.int("faabSpent"),
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
