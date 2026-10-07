package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.testing.FakePrefsSource
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.Instant

/** `mTransactions2` as remembered, unverified against a live league. */
class LeagueActivityTest {
    @TempDir
    lateinit var dir: File

    private val week3 = """
        {"transactions":[
          {"id":"a1","type":"WAIVER","status":"EXECUTED","teamId":2,"bidAmount":12,"processDate":1790000000000,
           "items":[{"playerId":101,"fromTeamId":0,"toTeamId":2,"type":"ADD"},{"playerId":102,"fromTeamId":2,"toTeamId":0,"type":"DROP"}]},
          {"id":"a2","type":"FREEAGENT","status":"EXECUTED","teamId":1,"processDate":1790000500000,
           "items":[{"playerId":103,"fromTeamId":0,"toTeamId":1,"type":"ADD"}]},
          {"id":"t1","type":"TRADE_ACCEPT","status":"EXECUTED","teamId":1,"processDate":1790001000000,
           "items":[{"playerId":104,"fromTeamId":1,"toTeamId":2,"type":"TRADE"},{"playerId":105,"fromTeamId":2,"toTeamId":1,"type":"TRADE"}]},
          {"id":"x1","type":"WAIVER","status":"FAILED_INVALIDPLAYERSOURCE","teamId":1,"items":[{"playerId":106,"fromTeamId":0,"toTeamId":1,"type":"ADD"}]},
          {"id":"x2","type":"TRADE_PROPOSAL","status":"PENDING","teamId":1,"items":[{"playerId":107,"fromTeamId":1,"toTeamId":2,"type":"TRADE"}]},
          {"id":"x3","type":"ROSTER","status":"EXECUTED","teamId":1,"items":[{"playerId":108,"fromTeamId":1,"toTeamId":1,"type":"LINEUP"}]}
        ]}
    """.trimIndent()

    @Test
    fun `executed adds, drops and trades parse and the rest is skipped`() {
        val items = EspnFantasyParser.activity(week3, 3)
        assertEquals(listOf("a1", "a2", "t1"), items.map { it.id })
        val waiver = items.first()
        assertEquals(ActivityKind.ADD, waiver.kind)
        assertEquals(2, waiver.teamId)
        assertEquals(12, waiver.bid)
        assertEquals(3, waiver.week)
        assertEquals(listOf(ActivityMove("101", null, null, 0, 2), ActivityMove("102", null, null, 2, 0)), waiver.moves)
        assertNull(items[1].bid)
        assertEquals(ActivityKind.TRADE, items[2].kind)
        assertEquals(emptyList<ActivityItem>(), EspnFantasyParser.activity("not json", 3))
    }

    private fun players(): PlayerDirectory {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('101', 'p101', 'Waiver Back', 'RB', 'KC'), ('104', 'p104', 'Traded Receiver', 'WR', 'DAL')")
            }
        }
        return PlayerDirectory(JdbcQueryExecutor(file.path))
    }

    private val league = """
        {"seasonId":2026,"scoringPeriodId":4,"settings":{"name":"Sunday League"},
         "members":[{"id":"{ME}","displayName":"Dr Palm"}],
         "teams":[{"id":1,"name":"Rivals","primaryOwner":"{YOU}"},{"id":2,"name":"Mine","primaryOwner":"{ME}"}]}
    """.trimIndent()

    @Test
    fun `the league's activity reads every week so far, newest first, with names`() = runTest {
        val asked = mutableListOf<String>()
        var now = Instant.parse("2026-10-01T00:00:00Z")
        val repo = FantasyLeagueRepository(
            FakePrefsSource(),
            { url, _ ->
                if ("mTransactions2" in url) {
                    asked += url
                    if ("scoringPeriodId=3" in url) week3 else """{"transactions":[]}"""
                } else {
                    league
                }
            },
            players(), dir,
        ) { now }
        assertEquals("no league id set", repo.activity(2026).error)
        repo.setLogin(null, null)
        repo.addLeague("42")
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        val result = repo.activity(2026)
        assertNull(result.error)
        assertEquals((4 downTo 1).map { EspnFantasyParser.transactionsUrl("42", 2026, it) }, asked)
        assertEquals(listOf("t1", "a2", "a1"), result.items.map { it.id })
        assertEquals(2, result.myTeamId)
        assertEquals(mapOf(1 to "Rivals", 2 to "Mine"), result.teams)
        val add = result.items.single { it.id == "a1" }.moves.first()
        assertEquals("p101", add.playerId)
        assertEquals("Waiver Back", add.name)
        // Held for fifteen minutes.
        repo.activity(2026)
        assertEquals(4, asked.size)
        now = now.plusSeconds(16 * 60)
        repo.activity(2026)
        assertEquals(8, asked.size)
    }
}
