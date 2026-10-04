package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.Roster
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.Lineups
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant

class FantasyLeagueTest {
    @TempDir
    lateinit var dir: File

    private fun team(id: Int, name: String, w: Int, l: Int, pf: Double, owner: String, vararg entries: String) =
        """{"id":$id,"name":"$name","primaryOwner":"$owner","owners":["$owner"],
            "record":{"overall":{"wins":$w,"losses":$l,"ties":0,"pointsFor":$pf,"pointsAgainst":300.5}},
            "roster":{"entries":[${entries.joinToString(",")}]}}"""

    private fun entry(id: Int, name: String?, slot: Int) =
        """{"playerId":$id,"lineupSlotId":$slot,"playerPoolEntry":{"player":${if (name == null) "{}" else """{"fullName":"$name"}"""}}}"""

    private val body = """
        {"seasonId":2026,"scoringPeriodId":4,"settings":{"name":"Sunday League"},
         "members":[{"id":"{ME}","displayName":"Dr Palm"},{"id":"{YOU}","displayName":"Rival"}],
         "teams":[
          ${team(1, "Rivals", 1, 3, 400.0, "{YOU}", entry(111, "Rival QB", 0))},
          ${team(2, "Mine", 3, 1, 420.5, "{ME}", entry(222, "Star WR", 4), entry(333, "Bench Guy", 20), entry(-16012, null, 16), entry(999, "Unknown", 21))}
         ]}
    """.trimIndent()

    @Test
    fun `parses standings, ranks by wins then points, and reads rosters`() {
        val league = EspnFantasyParser.parse(body, "42", 5L)
        assertEquals("Sunday League", league.name)
        assertEquals(2026, league.season)
        assertEquals(listOf("Mine", "Rivals"), league.teams.map { it.name })
        assertEquals(listOf(1, 2), league.teams.map { it.rank })
        val mine = league.teams.first()
        assertEquals("Dr Palm", mine.owner)
        assertEquals(3, mine.wins)
        assertEquals(420.5, mine.pointsFor)
        assertEquals(listOf("WR", "BE", "D/ST", "IR"), mine.players.map { it.slot })
        assertEquals("KC D/ST", mine.players[2].name)
        assertEquals("DST_KC", EspnFantasyParser.dstPlayerId("-16012"))
        assertNull(EspnFantasyParser.dstPlayerId("222"))
    }

    @Test
    fun `parses the starter slots and drops the bench, IR and unknown ids`() {
        val withSlots = body.replace(
            "\"settings\":{\"name\":\"Sunday League\"}",
            "\"settings\":{\"name\":\"Sunday League\",\"rosterSettings\":{\"lineupSlotCounts\":" +
                "{\"0\":1,\"2\":2,\"4\":2,\"6\":1,\"23\":1,\"16\":1,\"17\":1,\"20\":6,\"21\":1,\"99\":3,\"7\":0}}}",
        )
        assertEquals(
            mapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "FLEX" to 1, "D/ST" to 1, "K" to 1),
            EspnFantasyParser.parse(withSlots, "42", 5L).lineupSlots,
        )
        assertEquals(emptyMap<String, Int>(), EspnFantasyParser.parse(body, "42", 5L).lineupSlots)
    }

    private fun withSettings(settings: String, mineExtra: String = "") = body
        .replace("\"settings\":{\"name\":\"Sunday League\"}", "\"settings\":{\"name\":\"Sunday League\",$settings}")
        .replace("\"name\":\"Mine\",", "\"name\":\"Mine\",$mineExtra")

    @Test
    fun `playoff weeks follow the schedule settings, ESPN's defaults giving 15 to 17`() {
        // ESPN's leaguedefaults/3 (2026): 14 matchups of a week, 4 playoff teams, rounds of 1 and 2 weeks.
        val defaults = "\"scheduleSettings\":{\"matchupPeriodCount\":14,\"matchupPeriodLength\":1,\"playoffTeamCount\":4," +
            "\"playoffMatchupPeriodLength\":0,\"playoffMatchupPeriodLengthByRound\":{\"1\":1,\"2\":2}}"
        assertEquals(listOf(15, 16, 17), EspnFantasyParser.parse(withSettings(defaults), "42", 5L).playoffWeeks)
        // Six teams take three one-week rounds after a 13-week season.
        val six = "\"scheduleSettings\":{\"matchupPeriodCount\":13,\"matchupPeriodLength\":1,\"playoffTeamCount\":6,\"playoffMatchupPeriodLength\":1}"
        assertEquals(listOf(14, 15, 16), EspnFantasyParser.parse(withSettings(six), "42", 5L).playoffWeeks)
        // Missing settings, or a schedule past week 18, say nothing; the team then uses 15-17.
        val league = EspnFantasyParser.parse(body, "42", 5L)
        assertEquals(emptyList<Int>(), league.playoffWeeks)
        assertEquals(listOf(15, 16, 17), league.myTeam(2)!!.playoffWeeks)
        val long = "\"scheduleSettings\":{\"matchupPeriodCount\":17,\"playoffTeamCount\":8}"
        assertEquals(emptyList<Int>(), EspnFantasyParser.parse(withSettings(long), "42", 5L).playoffWeeks)
    }

    @Test
    fun `FAAB is read when the league bids, and what is left follows the team's spending`() {
        val bids = "\"acquisitionSettings\":{\"acquisitionBudget\":100,\"isUsingAcquisitionBudget\":true}"
        val league = EspnFantasyParser.parse(withSettings(bids, "\"transactionCounter\":{\"acquisitionBudgetSpent\":37},"), "42", 5L)
        assertEquals(100, league.faabBudget)
        assertEquals(63, league.myTeam(2)!!.faabLeft)
        // Saved and read back the same.
        assertEquals(league, fantasyLeagueFromJson(league.toJson()))
        // Spending unknown: the budget, but nothing said about what is left.
        assertNull(EspnFantasyParser.parse(withSettings(bids), "42", 5L).myTeam(2)!!.faabLeft)
        // A league on waiver order (ESPN's default) has no budget.
        val order = "\"acquisitionSettings\":{\"acquisitionBudget\":100,\"isUsingAcquisitionBudget\":false}"
        assertNull(EspnFantasyParser.parse(withSettings(order), "42", 5L).faabBudget)
    }

    @Test
    fun `the saved snapshot keeps its slots, and one saved before slots existed reads back empty`() {
        val league = EspnFantasyParser.parse(body, "42", 5L).copy(lineupSlots = mapOf("RB" to 2, "OP" to 1))
        assertEquals(league, fantasyLeagueFromJson(league.toJson()))
        val old = Json.parseToJsonElement(league.toJson()).jsonObject.filterKeys { it != "lineupSlots" }
        assertEquals(emptyMap<String, Int>(), fantasyLeagueFromJson(JsonObject(old).toString())!!.lineupSlots)
    }

    @Test
    fun `a response that is not a league is a format error`() {
        assertThrows<LiveFormatException> { EspnFantasyParser.parse("[]", "42", 0) }
        assertThrows<LiveFormatException> { EspnFantasyParser.parse("""{"seasonId":2026}""", "42", 0) }
        assertThrows<LiveFormatException> { EspnFantasyParser.parse("""{"seasonId":2026,"teams":[{"x":1}]}""", "42", 0) }
    }

    @Test
    fun `the saved snapshot reads back the same`() {
        val league = EspnFantasyParser.parse(body, "42", 5L)
        assertEquals(league, fantasyLeagueFromJson(league.toJson()))
        assertNull(fantasyLeagueFromJson("not json"))
    }

    private fun mEntry(id: Int, name: String?, slot: Int, points: Double?) =
        """{"playerId":$id,"lineupSlotId":$slot,"playerPoolEntry":{${points?.let { """"appliedStatTotal":$it,""" }.orEmpty()}"player":${if (name == null) "{}" else """{"fullName":"$name"}"""}}}"""

    private fun mSide(teamId: Int, total: Double, vararg entries: String) =
        """{"teamId":$teamId,"totalPoints":$total,"rosterForCurrentScoringPeriod":{"entries":[${entries.joinToString(",")}]}}"""

    private val matchupBody = """
        {"seasonId":2026,"scoringPeriodId":4,"schedule":[
          {"id":1,"matchupPeriodId":3,"home":${mSide(1, 90.0)},"away":${mSide(2, 80.0)}},
          {"id":2,"matchupPeriodId":4,
           "home":${mSide(2, 112.4, mEntry(999, "Bench Guy", 20, 3.0), mEntry(-16012, null, 16, 8.0), mEntry(222, "Star WR", 4, 21.3), mEntry(333, "Flex Guy", 23, 11.5), mEntry(111, "Star QB", 0, 25.1), mEntry(7, null, 4, 1.0))},
           "away":${mSide(1, 98.2, mEntry(444, "Rival RB", 2, 14.0))}},
          {"id":3,"matchupPeriodId":4,"home":${mSide(3, 60.0)}}
        ]}
    """.trimIndent()

    @Test
    fun `parses two matchups with totals and lineups`() {
        val matchups = EspnFantasyParser.matchups(matchupBody, 4)
        assertEquals(2, matchups.size)
        val first = matchups[0]
        assertEquals(4, first.week)
        assertEquals(2, first.home.teamId)
        assertEquals(112.4, first.home.espnTotal)
        assertEquals(1, first.away!!.teamId)
        assertEquals(98.2, first.away.espnTotal)
        assertEquals("Rival RB", first.away.lineup.single().name)
        assertEquals(14.0, first.away.lineup.single().espnPoints)
    }

    @Test
    fun `a lineup reads starters by position, then the bench`() {
        val lineup = EspnFantasyParser.matchups(matchupBody, 4)[0].home.lineup
        assertEquals(listOf("QB", "WR", "FLEX", "D/ST", "BE"), lineup.map { it.slot })
        assertEquals(listOf(25.1, 21.3, 11.5, 8.0, 3.0), lineup.map { it.espnPoints })
    }

    @Test
    fun `a D-ST entry gets its name and an unnamed unknown player is skipped`() {
        val lineup = EspnFantasyParser.matchups(matchupBody, 4)[0].home.lineup
        assertEquals("KC D/ST", lineup.single { it.slot == "D/ST" }.name)
        assertTrue(lineup.none { it.espnId == "7" })
    }

    @Test
    fun `a missing away side is a bye`() {
        val bye = EspnFantasyParser.matchups(matchupBody, 4)[1]
        assertEquals(3, bye.home.teamId)
        assertNull(bye.away)
    }

    @Test
    fun `only the asked week's matchups are kept`() {
        assertEquals(listOf(90.0), EspnFantasyParser.matchups(matchupBody, 3).map { it.home.espnTotal })
        assertEquals(emptyList<LeagueMatchup>(), EspnFantasyParser.matchups(matchupBody, 9))
    }

    @Test
    fun `a response without a schedule is a format error`() {
        assertThrows<LiveFormatException> { EspnFantasyParser.matchups("[]", 4) }
        assertThrows<LiveFormatException> { EspnFantasyParser.matchups("""{"seasonId":2026}""", 4) }
        assertThrows<LiveFormatException> { EspnFantasyParser.matchups("""{"schedule":[{"matchupPeriodId":4,"home":{"x":1}}]}""", 4) }
    }

    @Test
    fun `the matchups url asks for the matchup views and the week`() {
        val url = EspnFantasyParser.matchupsUrl("42", 2026, 4)
        assertTrue("view=mMatchup" in url && "view=mMatchupScore" in url && url.endsWith("scoringPeriodId=4"))
        assertTrue("/seasons/2026/" in url && "/leagues/42?" in url)
    }

    private val players = PlayerDirectory(
        object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
        },
    )

    /** Adds a league and sets the shared login: what the screen's "Save and sync" does. */
    private suspend fun FantasyLeagueRepository.configure(id: String, s2: String?, swid: String?) {
        setLogin(s2, swid)
        addLeague(id)
    }

    private fun repo(prefs: FakePrefsSource, http: HeaderHttpGet) =
        FantasyLeagueRepository(prefs, http, players, dir) { Instant.parse("2026-10-01T00:00:00Z") }

    @Test
    fun `sync sends the cookies, saves the league and the user's team as a roster`() = runTest {
        val prefs = FakePrefsSource()
        var seen: Pair<String, Map<String, String>>? = null
        val repo = repo(prefs) { url, headers -> seen = url to headers; body }
        repo.configure(" 42 ", "S2VALUE", "{ME}")
        val result = repo.sync(2026)
        assertTrue(result.ok, result.message)
        val (url, sent) = checkNotNull(seen)
        assertEquals(EspnFantasyParser.url("42", 2026), url)
        assertEquals("espn_s2=S2VALUE; SWID={ME}", sent["Cookie"])
        assertEquals(listOf("Mine", "Rivals"), repo.league.value!!.teams.map { it.name })
        // Only the D/ST has an app id here: the xref is empty.
        assertEquals(listOf(Roster("espn-42", "Mine", listOf("DST_KC"))), prefs.prefs.first().rosters)
        assertEquals(2, prefs.prefs.first().espnLeague!!.teamId)
        assertEquals(3, result.unmatched)
        // A fresh repository reads the league from disk.
        val again = repo(prefs) { _, _ -> error("offline") }
        again.load()
        assertEquals(repo.league.value, again.league.value)
    }

    @Test
    fun `rostered holds every team's matched players and skips unmatched`() {
        fun p(espn: String, id: String?) = LeaguePlayer(espn, "n$espn", "BE", id)
        fun t(id: Int, vararg ps: LeaguePlayer) = LeagueTeam(id, "T$id", null, 0, 0, 0, 0.0, 0.0, id, ps.toList())
        val league = FantasyLeague("42", "L", 2026, 4, listOf(t(1, p("1", "A"), p("2", null)), t(2, p("3", "B"), p("4", "A"))), 77L)
        // "A" is on both teams (a stale sync): the later team names him.
        assertEquals(LeagueRostered(setOf("A", "B"), 2026, 77L, mapOf("A" to "T2", "B" to "T2")), league.rostered())
    }

    @Test
    fun `rostered is null before a sync, then follows the league, and a fresh repository reads the saved one`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { _, _ -> body }
        assertNull(repo.rostered.first())
        repo.configure("42", null, "{ME}")
        assertTrue(repo.sync(2026).ok)
        // Only the D/ST has an app id here: the xref is empty.
        assertEquals(
            LeagueRostered(setOf("DST_KC"), 2026, Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), mapOf("DST_KC" to "Mine")),
            repo.rostered.first(),
        )
        assertEquals(repo.rostered.first(), repo(prefs) { _, _ -> error("offline") }.rostered.first())
    }

    @Test
    fun `myTeam follows the chosen team and the saved snapshot`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { _, _ -> body }
        assertNull(repo.myTeam.first())
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        // No team chosen yet (no SWID to find it by).
        assertNull(repo.myTeam.first())

        repo.chooseTeam(2)
        val mine = checkNotNull(repo.myTeam.first())
        assertEquals("Mine", mine.teamName)
        assertEquals(2026, mine.season)
        assertEquals(4, mine.players.size)
        assertEquals(Lineups.DEFAULT_SLOTS, mine.slots)
        assertTrue(mine.slotsAreDefault)
        assertEquals(mine, repo(prefs) { _, _ -> error("offline") }.myTeam.first())

        repo.configure("43", null, null)
        assertNull(repo.myTeam.first())
    }

    @Test
    fun `other teams are every team but mine, on the league's slots`() = runTest {
        fun t(id: Int) = LeagueTeam(id, "T$id", null, 0, 0, 0, 0.0, 0.0, id, listOf(LeaguePlayer("$id", "n$id", "BE", "P$id")))
        val league = FantasyLeague("42", "L", 2026, 4, listOf(t(1), t(2), t(3)), 77L, lineupSlots = mapOf("QB" to 1, "RB" to 2))
        val others = league.otherTeams(2)
        assertEquals(listOf("T1", "T3"), others.map { it.teamName })
        assertEquals(mapOf("QB" to 1, "RB" to 2), others.first().slots)
        assertFalse(others.first().slotsAreDefault)
        assertEquals(emptyList<MyTeam>(), league.otherTeams(9))
        assertEquals(emptyList<MyTeam>(), league.otherTeams(null))

        val prefs = FakePrefsSource()
        val repo = repo(prefs) { _, _ -> body }
        assertEquals(emptyList<MyTeam>(), repo.otherTeams.first())
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        assertTrue(repo.otherTeams.first().none { it.teamName == "Mine" })
    }

    @Test
    fun `matchups sends the cookies and the week, and an unmatched player has no app points`() = runTest {
        val prefs = FakePrefsSource()
        var seen: Pair<String, Map<String, String>>? = null
        val repo = repo(prefs) { url, headers -> seen = url to headers; matchupBody }
        repo.configure("42", "S2VALUE", "{ME}")
        val result = repo.matchups(2026, 4, ScoringPresets.PPR)
        assertNull(result.error)
        val (url, sent) = checkNotNull(seen)
        assertEquals(EspnFantasyParser.matchupsUrl("42", 2026, 4), url)
        assertEquals("espn_s2=S2VALUE; SWID={ME}", sent["Cookie"])
        assertEquals(2, result.matchups.size)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), result.fetchedAtMillis)
        // No stats database is wired here and the xref is empty: only the D/ST has an app id, and no app points.
        val lineup = result.matchups[0].home.lineup
        assertEquals("DST_KC", lineup.single { it.slot == "D/ST" }.playerId)
        assertTrue(lineup.all { it.appPoints == null })
        assertNull(result.matchups[0].home.appTotal)
    }

    @Test
    fun `opponent is the other side's roster on my slots`() = runTest {
        val prefs = FakePrefsSource()
        var seen: Pair<String, Map<String, String>>? = null
        val repo = repo(prefs) { url, headers -> if ("view=mMatchup" in url) { seen = url to headers; matchupBody } else body }
        repo.configure("42", "S2VALUE", "{ME}")
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)

        val result = repo.opponent(2026, 4)
        assertNull(result.message)
        val rival = checkNotNull(result.team)
        assertEquals("Rivals", rival.teamName)
        assertEquals(listOf("Rival QB"), rival.players.map { it.name })
        assertEquals(Lineups.DEFAULT_SLOTS, rival.slots)
        val (url, sent) = checkNotNull(seen)
        assertEquals(EspnFantasyParser.matchupsUrl("42", 2026, 4), url)
        assertEquals("espn_s2=S2VALUE; SWID={ME}", sent["Cookie"])
        // The same matchup read from the other side.
        repo.chooseTeam(1)
        assertEquals("Mine", repo.opponent(2026, 4).team!!.teamName)
    }

    @Test
    fun `the schedule lists every period's games, decided once ESPN names a winner`() {
        val body = matchupBody.replace("\"id\":1,\"matchupPeriodId\":3,", "\"id\":1,\"matchupPeriodId\":3,\"winner\":\"HOME\",")
            .replace("\"id\":2,\"matchupPeriodId\":4,", "\"id\":2,\"matchupPeriodId\":4,\"winner\":\"UNDECIDED\",")
        assertEquals(
            listOf(ScheduledGame(3, 1, 2, true), ScheduledGame(4, 2, 1, false), ScheduledGame(4, 3, null, false)),
            EspnFantasyParser.schedule(body),
        )
        assertThrows<LiveFormatException> { EspnFantasyParser.schedule("{}") }
    }

    @Test
    fun `the playoff picture keeps the undecided regular-season games, and says why when there is none`() = runTest {
        val prefs = FakePrefsSource()
        val scheduled = matchupBody.replace("\"id\":1,\"matchupPeriodId\":3,", "\"id\":1,\"matchupPeriodId\":3,\"winner\":\"AWAY\",")
        val repo = repo(prefs) { url, _ -> if ("view=mMatchup" in url) scheduled else body }
        assertEquals("no league id set", repo.playoffPicture(2026, 4).message)
        repo.configure("42", null, null)
        assertEquals("sync your league first", repo.playoffPicture(2026, 4).message)
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        val picture = checkNotNull(repo.playoffPicture(2026, 4).picture)
        assertEquals(2, picture.myTeamId)
        // Period 3 is decided and period 4's other entry is a bye: one game left.
        assertEquals(listOf(ScheduledGame(4, 2, 1, false)), picture.remaining)
    }

    @Test
    fun `the lineup review reads each finished week from ESPN, newest first`() = runTest {
        val prefs = FakePrefsSource()
        val asked = mutableListOf<String>()
        val repo = repo(prefs) { url, _ -> if ("view=mMatchup" in url) { asked += url; matchupBody } else body }
        assertEquals("no league id set", repo.lineupReview(2026, 4).message)
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        val result = repo.lineupReview(2026, 4)
        assertNull(result.message)
        // The fixture only has team 2 in week 4 (and week 3 between teams 1 and 2, with no lineups).
        assertEquals(listOf(4, 3), result.weeks.map { it.week })
        // Star QB, Star WR, Flex Guy and the D/ST started; the unnamed unknown is skipped by the parser.
        assertEquals(65.9, result.weeks.first().scored, 1e-9)
        assertEquals((4 downTo 1).map { EspnFantasyParser.matchupsUrl("42", 2026, it) }, asked)
    }

    @Test
    fun `playoff odds re-sync a stale snapshot first, so a week decided since isn't lost`() = runTest {
        val prefs = FakePrefsSource()
        var now = Instant.parse("2026-10-01T00:00:00Z")
        var leagueReads = 0
        val repo = FantasyLeagueRepository(prefs, { url, _ -> if ("view=mMatchup" in url) matchupBody else body.also { leagueReads++ } }, players, dir) { now }
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        assertEquals(1, leagueReads)
        now = now.plusSeconds(10 * 60)
        assertNotNull(repo.playoffPicture(2026, 4).picture)
        assertEquals(1, leagueReads)
        now = now.plusSeconds(60 * 60)
        assertNotNull(repo.playoffPicture(2026, 4).picture)
        assertEquals(2, leagueReads)
    }

    @Test
    fun `a bye, a missing matchup and a missing sync each say so`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { url, _ -> if ("view=mMatchup" in url) matchupBody else body }
        assertEquals("no league id set", repo.opponent(2026, 4).message)
        repo.configure("42", null, null)
        assertEquals("sync your league first", repo.opponent(2026, 4).message)
        assertTrue(repo.sync(2026).ok)
        assertEquals("choose your team first", repo.opponent(2026, 4).message)

        repo.chooseTeam(2)
        assertEquals("ESPN lists no matchup for you in week 9", repo.opponent(2026, 9).message)
        val byeBody = "{\"schedule\":[{\"matchupPeriodId\":4,\"home\":${mSide(2, 0.0)}}]}"
        val bye = repo(prefs) { url, _ -> if ("view=mMatchup" in url) byeBody else body }
        assertEquals("you have a bye in week 4", bye.opponent(2026, 4).message)
        assertNull(bye.opponent(2026, 4).team)
    }

    @Test
    fun `an opponent fetch that fails says why`() = runTest {
        val prefs = FakePrefsSource()
        val ok = repo(prefs) { _, _ -> body }
        ok.configure("42", null, null)
        assertTrue(ok.sync(2026).ok)
        ok.chooseTeam(2)

        val offline = repo(prefs) { _, _ -> throw IOException("HTTP 403") }
        assertEquals("ESPN says this league is private; add your espn_s2 and SWID cookies", offline.opponent(2026, 4).message)
        val changed = repo(prefs) { _, _ -> "{\"nothing\":1}" }
        assertEquals("ESPN changed its matchup format (no schedule)", changed.opponent(2026, 4).message)
    }

    @Test
    fun `matchups say why they failed and no league is its own message`() = runTest {
        val prefs = FakePrefsSource()
        var fail: Exception? = null
        val repo = repo(prefs) { _, _ -> fail?.let { throw it } ?: matchupBody }
        assertEquals("no league id set", repo.matchups(2026, 4, ScoringPresets.PPR).error)
        repo.configure("42", null, null)
        fail = IOException("HTTP 401 from lm-api-reads.fantasy.espn.com")
        assertTrue(repo.matchups(2026, 4, ScoringPresets.PPR).error!!.contains("private"))
        fail = LiveFormatException("ESPN changed its matchup format")
        assertEquals("ESPN changed its matchup format", repo.matchups(2026, 4, ScoringPresets.PPR).error)
        fail = null
        assertNull(repo.matchups(2026, 4, ScoringPresets.PPR).error)
    }

    @Test
    fun `a public league sends no cookie and sync again replaces the roster`() = runTest {
        val prefs = FakePrefsSource()
        var headers: Map<String, String>? = null
        val repo = repo(prefs) { _, h -> headers = h; body }
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        assertTrue(headers!!.isEmpty())
        assertTrue(prefs.prefs.first().rosters.isEmpty())
        repo.chooseTeam(1)
        assertEquals(listOf("espn-42"), prefs.prefs.first().rosters.map { it.id })
        repo.chooseTeam(2)
        assertEquals(listOf("Mine"), prefs.prefs.first().rosters.map { it.name })
    }

    @Test
    fun `failures say why and keep the last league`() = runTest {
        val prefs = FakePrefsSource()
        var fail: Exception? = null
        val repo = repo(prefs) { _, _ -> fail?.let { throw it } ?: body }
        assertFalse(repo.sync(2026).ok)
        repo.configure("42", null, null)
        assertTrue(repo.sync(2026).ok)
        fail = IOException("HTTP 401 from lm-api-reads.fantasy.espn.com")
        assertTrue(repo.sync(2026).message.contains("private"))
        fail = LiveFormatException("ESPN changed its league format")
        assertFalse(repo.sync(2026).ok)
        assertNotNull(repo.league.value)
    }

    @Test
    fun `leagues keep their own snapshots, teams and rosters, and every screen follows the active one`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { url, _ -> if ("/leagues/43" in url) body.replace("Mine", "Other") else body }
        repo.configure("42", null, "{ME}")
        assertTrue(repo.sync(2026).ok)
        repo.configure("43", null, "{ME}")
        assertNull(repo.league.value, "a league not yet synced has no snapshot")
        assertTrue(repo.sync(2026).ok)
        assertEquals("43", repo.league.value!!.leagueId)
        assertEquals(listOf("espn-42", "espn-43"), prefs.prefs.first().rosters.map { it.id })
        assertEquals(listOf("Mine", "Other"), prefs.prefs.first().rosters.map { it.name })

        repo.setActive("42")
        assertEquals("42", repo.league.value!!.leagueId)
        assertEquals("Mine", repo.myTeam.first()!!.teamName)
        assertEquals("42", prefs.prefs.first().espnLeague!!.leagueId)
        assertEquals(listOf(true, false), repo.leagues.first().map { it.active })
        assertEquals(listOf("Sunday League", "Sunday League"), repo.leagues.first().map { it.name })
        // A fresh repository reads the active league's own file.
        assertEquals("42", repo(prefs) { _, _ -> error("offline") }.also { it.load() }.league.value!!.leagueId)
        // An id that was never added changes nothing.
        repo.setActive("99")
        assertEquals("42", repo.league.value!!.leagueId)
    }

    @Test
    fun `removing a league drops its snapshot and roster, and the first left becomes active`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { _, _ -> body }
        repo.configure("42", null, "{ME}")
        repo.sync(2026)
        repo.configure("43", null, "{ME}")
        repo.sync(2026)
        repo.removeLeague("43")
        assertEquals("42", repo.league.value!!.leagueId)
        assertEquals(listOf("espn-42"), prefs.prefs.first().rosters.map { it.id })
        assertFalse(File(dir, "league-43.json").exists())
        repo.removeLeague("42")
        assertNull(repo.league.value)
        assertNull(prefs.prefs.first().espnLeague)
        assertTrue(prefs.prefs.first().rosters.isEmpty())
    }

    @Test
    fun `the chosen team is per league, and the login is shared`() = runTest {
        val prefs = FakePrefsSource()
        val seen = mutableListOf<String?>()
        val repo = repo(prefs) { _, headers -> seen += headers["Cookie"]; body }
        repo.configure("42", "S2VALUE", "{ME}")
        repo.sync(2026)
        repo.chooseTeam(1)
        repo.addLeague("43")
        repo.sync(2026)
        assertEquals(listOf("espn_s2=S2VALUE; SWID={ME}", "espn_s2=S2VALUE; SWID={ME}"), seen)
        // League 43 found its team from the SWID; league 42 keeps the one chosen by hand.
        assertEquals(listOf(1, 2), prefs.prefs.first().espnLeagues.map { it.teamId })
    }

    @Test
    fun `a single league file from before several leagues becomes that league's file`() = runTest {
        val prefs = FakePrefsSource()
        val first = repo(prefs) { _, _ -> body }
        first.configure("42", null, "{ME}")
        first.sync(2026)
        File(dir, "league-42.json").renameTo(File(dir, "league.json"))
        val again = repo(prefs) { _, _ -> error("offline") }
        again.load()
        assertEquals("42", again.league.value!!.leagueId)
        assertTrue(File(dir, "league-42.json").isFile)
        assertFalse(File(dir, "league.json").exists())
    }

    @Test
    fun `a league id must be digits`() = runTest {
        assertThrows<IllegalArgumentException> { repo(FakePrefsSource()) { _, _ -> body }.addLeague("abc") }
    }
}
