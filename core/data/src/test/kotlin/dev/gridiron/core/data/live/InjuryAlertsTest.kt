package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.model.Roster
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager

class InjuryAlertsTest {
    private val q = SeenStatus("Q", "Questionable", "Pat Star")
    private val out = SeenStatus("O", "Out", "Pat Star")

    @Test
    fun `only rostered players whose status changed are alerted, by name`() {
        val before = mapOf("p1" to q, "p2" to q, "p3" to q)
        val now = mapOf("p1" to out, "p2" to q.copy(name = "Zed"), "p4" to out.copy(name = "Al New"))
        val alerts = InjuryAlerts.changes(before, now, rostered = setOf("p1", "p2", "p3", "p4", "p5"), starters = emptySet())
        // p2 kept Q; p5 was never listed; p3 left the list; p4 joined it.
        assertEquals(listOf("p4", "p1", "p3"), alerts.map { it.playerId })
        assertEquals(listOf("Al New: Out", "Pat Star: Out", "Pat Star: off the injury report"), alerts.map { it.title })
        assertEquals(listOf("Was healthy.", "Was Questionable.", "Was Questionable."), alerts.map { it.text })
        assertTrue(InjuryAlerts.changes(before, now, rostered = setOf("p9"), starters = emptySet()).isEmpty())
    }

    @Test
    fun `a starter ruled out is told to start someone else, a questionable one isn't`() {
        val outAlert = InjuryAlerts.changes(mapOf("p1" to q), mapOf("p1" to out), setOf("p1"), starters = setOf("p1")).single()
        assertEquals("Was Questionable. He's in your ESPN lineup: start someone else.", outAlert.text)
        val qAlert = InjuryAlerts.changes(emptyMap(), mapOf("p1" to q), setOf("p1"), starters = setOf("p1")).single()
        assertEquals("Was healthy.", qAlert.text)
        val benched = InjuryAlerts.changes(mapOf("p1" to q), mapOf("p1" to out), setOf("p1"), starters = emptySet()).single()
        assertEquals("Was Questionable.", benched.text)
    }

    @Test
    fun `starters are every slot but the bench and IR`() {
        val team = MyTeam(
            "Mine", 2026,
            listOf(LeaguePlayer("1", "A", "QB", "a"), LeaguePlayer("2", "B", "BE", "b"), LeaguePlayer("3", "C", "IR", "c"), LeaguePlayer("4", "D", "FLEX", null)),
            emptyMap(), slotsAreDefault = true,
        )
        assertEquals(setOf("a"), team.starterIds())
    }

    @TempDir
    lateinit var dir: File

    private var offline = false
    private val http = HttpGet { url ->
        if (offline) throw IOException("offline")
        checkNotNull(javaClass.getResource("/espn/${if (url == EspnParser.INJURIES_URL) "injuries.json" else "news.json"}")).readText()
    }
    private val db by lazy { LiveDb(File(dir, "live.db")) }

    @AfterEach
    fun close() {
        db.close()
    }

    /** Max Melton (Q in the recorded list) is the one matched player. */
    private fun players(): PlayerDirectory {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('4698113', '00-0039900', 'Max Melton', 'CB', 'ARI')")
            }
        }
        return PlayerDirectory(JdbcQueryExecutor(file.path))
    }

    @Test
    fun `the first check only stores, a change then alerts, and a failed fetch keeps the old listing`() = runTest {
        val state = File(dir, "alerts.json")
        val checker = InjuryAlertChecker(
            LiveRepository(db, http, players()),
            flowOf(listOf(Roster("r1", "Mine", listOf("00-0039900")))),
            starters = { setOf("00-0039900") },
            stateFile = state,
        )
        assertEquals(emptyList<InjuryAlert>(), checker.check())
        assertTrue(state.readText().contains("00-0039900"))

        // Pretend he was Out last time: now he is Questionable.
        state.writeText("""{"00-0039900":{"abbr":"O","status":"Out","name":"Max Melton"}}""")
        val alert = checker.check().single()
        assertEquals("Max Melton", alert.name)
        assertEquals("O", alert.from?.abbr)
        assertEquals("Q", alert.to?.abbr)

        // Offline: nothing, and the listing just stored stays.
        val stored = state.readText()
        offline = true
        assertEquals(emptyList<InjuryAlert>(), checker.check())
        assertEquals(stored, state.readText())
    }

    @Test
    fun `an unreadable state file counts as a first check`() = runTest {
        val state = File(dir, "alerts.json").apply { writeText("not json") }
        val checker = InjuryAlertChecker(LiveRepository(db, http, players()), flowOf(listOf(Roster("r1", "Mine", listOf("00-0039900")))), { emptySet() }, state)
        assertEquals(emptyList<InjuryAlert>(), checker.check())
        assertTrue(state.readText().startsWith("{"))
    }
}
