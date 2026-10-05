package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.SqlQuery
import androidx.sqlite.execSQL
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

class WaiverTrendsTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-10-05T18:00:00Z")
    private var body: () -> String = { recorded() }
    private var calls = 0
    private val sent = mutableListOf<Pair<String, Map<String, String>>>()
    private val http = HeaderHttpGet { url, headers ->
        calls++
        sent += url to headers
        body()
    }
    private val db by lazy { LiveDb(File(dir, "live.db")) }

    @AfterEach
    fun close() {
        db.close()
    }

    private fun recorded(): String = checkNotNull(javaClass.getResource("/espn/waiver_trends.json")).readText()

    /** Tyreek Hill has an app id; nobody else does. */
    private fun players(): PlayerDirectory {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('3116406', '00-0033040', 'Tyreek Hill', 'WR', 'MIA')")
            }
        }
        return PlayerDirectory(JdbcQueryExecutor(file.path))
    }

    private val repository by lazy { WaiverTrendsRepository(db, http, players()) { now } }

    @Test
    fun `the recorded list parses roster share, ESPN's change, position and team`() {
        val parsed = EspnTrendsParser.parse(recorded())
        assertEquals(25, parsed.size)
        val hill = parsed.single { it.name == "Tyreek Hill" }
        // Unsigned in ESPN's list: pro team 0, so no team.
        assertEquals(EspnRostership("3116406", "Tyreek Hill", "WR", null, 28.52, 1.72), hill)
        assertEquals("DET", parsed.single { it.name == "Jahmyr Gibbs" }.team)
        val bears = parsed.single { it.espnId == "-16003" }
        assertEquals("DST", bears.position)
        assertEquals("CHI", bears.team)
    }

    @Test
    fun `a list without players is a format change, not an empty list`() {
        assertThrows<LiveFormatException> { EspnTrendsParser.parse("""{"teams":[]}""") }
        assertThrows<LiveFormatException> { EspnTrendsParser.parse("<html>") }
        assertThrows<LiveFormatException> { EspnTrendsParser.parse("""{"players":[{"player":{"id":1}}]}""") }
        assertEquals(emptyList<EspnRostership>(), EspnTrendsParser.parse("""{"players":[]}"""))
    }

    @Test
    fun `the request asks ESPN for the thousand most rostered with one stat line`() {
        assertTrue(EspnTrendsParser.url(2026).endsWith("/seasons/2026/segments/0/leaguedefaults/3?view=kona_player_info"))
        val filter = EspnTrendsParser.headers(2026).getValue("X-Fantasy-Filter")
        assertTrue("\"limit\":1000" in filter && "\"sortPercOwned\"" in filter && "\"002026\"" in filter, filter)
    }

    @Test
    fun `players link to app ids, a defense to its team's`() = runTest {
        val result = repository.load(2026)
        assertNull(result.error)
        assertEquals("00-0033040", result.trends.single { it.name == "Tyreek Hill" }.playerId)
        assertEquals("DST_CHI", result.trends.single { it.espnId == "-16003" }.playerId)
        assertNull(result.trends.single { it.name == "Joe Mixon" }.playerId)
    }

    @Test
    fun `no weekly change until a snapshot from a week ago exists`() = runTest {
        val first = repository.load(2026)
        assertFalse(first.weekly)
        assertTrue(first.trends.all { it.weekChange == null })

        now += Duration.ofDays(6)
        val sixDays = repository.load(2026)
        assertFalse(sixDays.weekly)

        // A week on, Hill is up 10 points and Mixon was nowhere on the list.
        now += Duration.ofDays(1)
        body = {
            recorded().replace("\"percentOwned\": 28.52", "\"percentOwned\": 38.52")
        }
        db.write { it.execSQL("DELETE FROM roster_pct WHERE espn_id = '3116385'") }
        val week = repository.load(2026)
        assertTrue(week.weekly)
        assertEquals(10.0, week.trends.single { it.name == "Tyreek Hill" }.weekChange!!, 1e-9)
        assertEquals(3.23, week.trends.single { it.name == "Joe Mixon" }.weekChange!!, 1e-9)
        assertEquals(0.0, week.trends.single { it.name == "Jahmyr Gibbs" }.weekChange!!, 1e-9)
    }

    @Test
    fun `a snapshot more than ten days old is too stale to compare`() = runTest {
        repository.load(2026)
        now += Duration.ofDays(11)
        assertFalse(repository.load(2026).weekly)
    }

    @Test
    fun `a second open within fifteen minutes reuses the list`() = runTest {
        repository.load(2026)
        now += Duration.ofMinutes(14)
        repository.load(2026)
        assertEquals(1, calls)
        now += Duration.ofMinutes(2)
        repository.load(2026)
        assertEquals(2, calls)
        assertEquals(EspnTrendsParser.headers(2026), sent.last().second)
    }

    @Test
    fun `a failed fetch keeps the last list and says why`() = runTest {
        val good = repository.load(2026)
        now += Duration.ofHours(1)
        body = { throw IOException("offline") }
        val failed = repository.load(2026)
        assertEquals("couldn't reach ESPN", failed.error)
        assertEquals(good.trends, failed.trends)

        body = { "{}" }
        assertEquals("ESPN changed its player list format (no players)", repository.load(2026).error)
    }

    @Test
    fun `a failure with nothing fetched before shows an empty list`() = runTest {
        body = { throw IOException("offline") }
        val result = repository.load(2026)
        assertEquals(emptyList<WaiverTrend>(), result.trends)
        assertEquals("couldn't reach ESPN", result.error)
    }

    @Test
    fun `without a stats database the list still shows, unlinked`() = runTest {
        val noStats = PlayerDirectory(
            object : QueryExecutor {
                override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = error("No stats yet")
            },
        )
        val result = WaiverTrendsRepository(db, http, noStats) { now }.load(2026)
        assertEquals(25, result.trends.size)
        assertNull(result.trends.single { it.name == "Tyreek Hill" }.playerId)
    }
}
