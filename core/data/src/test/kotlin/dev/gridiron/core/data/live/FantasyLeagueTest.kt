package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.Roster
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.flow.first
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

    private val players = PlayerDirectory(
        object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
        },
    )

    private fun repo(prefs: FakePrefsSource, http: HeaderHttpGet) =
        FantasyLeagueRepository(prefs, http, players, File(dir, "league.json")) { Instant.parse("2026-10-01T00:00:00Z") }

    @Test
    fun `sync sends the cookies, saves the league and the user's team as a roster`() = runTest {
        val prefs = FakePrefsSource()
        var seen: Pair<String, Map<String, String>>? = null
        val repo = repo(prefs) { url, headers -> seen = url to headers; body }
        repo.setConfig(" 42 ", "S2VALUE", "{ME}")
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
    fun `a public league sends no cookie and sync again replaces the roster`() = runTest {
        val prefs = FakePrefsSource()
        var headers: Map<String, String>? = null
        val repo = repo(prefs) { _, h -> headers = h; body }
        repo.setConfig("42", null, null)
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
        repo.setConfig("42", null, null)
        assertTrue(repo.sync(2026).ok)
        fail = IOException("HTTP 401 from lm-api-reads.fantasy.espn.com")
        assertTrue(repo.sync(2026).message.contains("private"))
        fail = LiveFormatException("ESPN changed its league format")
        assertFalse(repo.sync(2026).ok)
        assertNotNull(repo.league.value)
    }

    @Test
    fun `switching leagues drops the old league and its roster`() = runTest {
        val prefs = FakePrefsSource()
        val repo = repo(prefs) { _, _ -> body }
        repo.setConfig("42", null, "{ME}")
        repo.sync(2026)
        repo.setConfig("43", null, "{ME}")
        assertNull(repo.league.value)
        assertTrue(prefs.prefs.first().rosters.isEmpty())
        repo.setConfig("", null, null)
        assertNull(prefs.prefs.first().espnLeague)
    }

    @Test
    fun `a league id must be digits`() = runTest {
        assertThrows<IllegalArgumentException> { repo(FakePrefsSource()) { _, _ -> body }.setConfig("abc", null, null) }
    }
}
