package dev.gridiron.core.data

import dev.gridiron.core.data.live.FantasyLeague
import dev.gridiron.core.data.live.HttpGet
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

class DynastyRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-10-05T18:00:00Z")
    private var body: () -> String = { recorded() }
    private val urls = mutableListOf<String>()
    private val http = HttpGet { url ->
        urls += url
        body()
    }

    private fun recorded(): String = checkNotNull(javaClass.getResource("/fantasycalc/values.json")).readText()

    /** Gibbs has an app id; nobody else does. */
    private fun players(): PlayerDirectory {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('4429795', '00-0038542', 'Jahmyr Gibbs', 'RB', 'DET')")
            }
        }
        return PlayerDirectory(JdbcQueryExecutor(file.path))
    }

    private val repository by lazy { DynastyRepository(http, players()) { now } }
    private val ppr12 = DynastyFormat(teams = 12, qbs = 1, ppr = 1.0)

    @Test
    fun `parses the recorded values`() = runTest {
        val result = repository.load(ppr12)
        assertNull(result.error)
        val gibbs = result.values.first()
        assertEquals("Jahmyr Gibbs", gibbs.name)
        assertEquals("00-0038542", gibbs.playerId)
        assertEquals("RB", gibbs.position)
        assertEquals("DET", gibbs.team)
        assertEquals(1, gibbs.rank)
        assertEquals(1, gibbs.positionRank)
        assertTrue(gibbs.value > result.values.last().value)
        assertTrue(gibbs.redraftValue > 0)
    }

    @Test
    fun `rookie picks are dropped and ranks count players only`() = runTest {
        val values = repository.load(ppr12).values
        assertTrue(values.none { it.position == "PICK" })
        assertEquals((1..values.size).toList(), values.map { it.rank })
        assertEquals(values.sortedByDescending { it.value }, values)
    }

    @Test
    fun `a player without an ESPN id stays unlinked`() = runTest {
        val hibner = repository.load(ppr12).values.single { it.name == "Matt Hibner" }
        assertNull(hibner.espnId)
        assertNull(hibner.playerId)
    }

    @Test
    fun `the URL follows the format`() {
        assertEquals(
            "https://api.fantasycalc.com/values/current?isDynasty=true&numQbs=2&numTeams=10&ppr=0.5",
            FantasyCalcParser.url(DynastyFormat(10, 2, 0.5)),
        )
        assertEquals(
            "https://api.fantasycalc.com/values/current?isDynasty=true&numQbs=1&numTeams=12&ppr=1",
            FantasyCalcParser.url(ppr12),
        )
    }

    @Test
    fun `format from league and profile`() {
        val ppr = ScoringPresets.PPR
        assertEquals(ppr12, dynastyFormat(null, ppr))
        val league = FantasyLeague("1", "L", 2026, 5, emptyList(), lineupSlots = mapOf("QB" to 1, "OP" to 1))
        assertEquals(DynastyFormat(12, 2, 1.0), dynastyFormat(league.copy(teams = emptyList()), ppr))
        assertEquals(0.5, dynastyFormat(null, ScoringPresets.HALF_PPR).ppr, 0.0)
        assertEquals(0.0, dynastyFormat(null, ScoringPresets.STANDARD).ppr, 0.0)
    }

    @Test
    fun `six hours of cache per format`() = runTest {
        repository.load(ppr12)
        now += Duration.ofHours(5)
        repository.load(ppr12)
        assertEquals(1, urls.size)
        repository.load(ppr12.copy(qbs = 2))
        assertEquals(2, urls.size)
        now += Duration.ofHours(2)
        repository.load(ppr12)
        assertEquals(3, urls.size)
    }

    @Test
    fun `a failure keeps the last list and says why`() = runTest {
        val good = repository.load(ppr12)
        now += Duration.ofHours(7)
        body = { throw IOException("offline") }
        val failed = repository.load(ppr12)
        assertEquals("couldn't reach FantasyCalc", failed.error)
        assertEquals(good.values, failed.values)
    }

    @Test
    fun `not JSON is a format change`() = runTest {
        body = { "<html>" }
        val result = repository.load(ppr12)
        assertEquals("FantasyCalc changed its format", result.error)
        assertEquals(emptyList<DynastyValue>(), result.values)
    }
}
