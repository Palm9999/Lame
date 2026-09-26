package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.JdbcQueryExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

class LiveRepositoryTest {
    @TempDir
    lateinit var dir: File

    private var now = Instant.parse("2026-09-26T00:00:00Z")
    private val responses = mutableMapOf<String, () -> String>()
    private var calls = 0
    private val http = HttpGet { url ->
        calls++
        responses[url]?.invoke() ?: throw IOException("offline")
    }

    /** Swappable, like the app's reopenable executor: starts with no stats database at all. */
    private var stats: QueryExecutor = object : QueryExecutor {
        override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = error("No stats yet")
    }
    private val players = PlayerDirectory(
        object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = stats.query(query, map)
        },
    )
    private val db by lazy { LiveDb(File(dir, "live.db")) }
    private val live by lazy { LiveRepository(db, http, players) { now } }

    @AfterEach
    fun close() {
        db.close()
    }

    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/espn/$name")) { name }.readText()

    private fun serveRecorded() {
        responses[EspnParser.NEWS_URL] = { recorded("news.json") }
        responses[EspnParser.INJURIES_URL] = { recorded("injuries.json") }
    }

    /** Barkley (news) and Melton (injuries, Q) have app ids; nobody else does. */
    private fun statsWithXref(): QueryExecutor {
        val file = File(dir, "stats.db")
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE player_xref (espn_id TEXT PRIMARY KEY, player_id TEXT NOT NULL, full_name TEXT NOT NULL, position TEXT, team TEXT)")
                st.executeUpdate("INSERT INTO player_xref VALUES ('3929630', '00-0034844', 'Saquon Barkley', 'RB', 'PHI'), ('4698113', '00-0039900', 'Max Melton', 'CB', 'ARI')")
            }
        }
        return JdbcQueryExecutor(file.path)
    }

    @Test
    fun `refresh stores both feeds and links players through player_xref`() = runTest {
        stats = statsWithXref()
        serveRecorded()

        val result = live.refresh()

        assertTrue(result.ok)
        val news = live.news()
        assertEquals(3, news.size)
        assertEquals(listOf(NewsPlayer("Saquon Barkley", "00-0034844")), news[0].players)
        assertEquals(listOf(null, null, null), news[1].players.map { it.playerId })
        assertEquals(mapOf("00-0039900" to "Q"), live.badges.first())
        assertEquals("Melton (toe) was a limited participant in Thursday's practice.", live.status("00-0039900")?.shortComment)
        assertEquals(4, live.injuries().size)
        assertEquals(now, live.fetchedAt())
    }

    @Test
    fun `with ESPN down the last data and its time are kept`() = runTest {
        serveRecorded()
        live.refresh()
        val fetched = now
        responses.clear()
        now = now.plus(Duration.ofHours(1))

        val result = live.refresh()

        assertEquals("couldn't reach ESPN", result.newsError)
        assertEquals("couldn't reach ESPN", result.injuriesError)
        assertEquals(3, live.news().size)
        assertEquals(4, live.injuries().size)
        assertEquals(fetched, live.fetchedAt())
    }

    @Test
    fun `a changed news format fails news only, and injuries still update`() = runTest {
        responses[EspnParser.NEWS_URL] = { """{"headlines": []}""" }
        responses[EspnParser.INJURIES_URL] = { recorded("injuries.json") }

        val result = live.refresh()

        assertTrue(result.newsError.orEmpty().contains("ESPN changed its news format"))
        assertNull(result.injuriesError)
        assertEquals(4, live.injuries().size)
        assertNull(live.fetchedAt(), "news has never arrived, so there is no as-of time for both feeds")
    }

    @Test
    fun `refreshIfStale waits fifteen minutes between fetches`() = runTest {
        serveRecorded()
        assertNotNull(live.refreshIfStale())
        assertEquals(2, calls)

        now = now.plus(Duration.ofMinutes(10))
        assertNull(live.refreshIfStale())
        assertEquals(2, calls)

        now = now.plus(Duration.ofMinutes(6))
        assertNotNull(live.refreshIfStale())
        assertEquals(4, calls)
    }

    @Test
    fun `news fetched before any stats database gets linked once player_xref exists`() = runTest {
        serveRecorded()
        live.refresh()
        assertEquals(emptyList<NewsItem>(), live.playerNews("00-0034844"))

        stats = statsWithXref()
        responses[EspnParser.NEWS_URL] = { """{"articles": []}""" }
        live.refresh()

        assertEquals(listOf("50029598"), live.playerNews("00-0034844").map { it.id })
    }

    @Test
    fun `articles older than thirty days are dropped even while ESPN still lists them`() = runTest {
        serveRecorded()
        now = Instant.parse("2026-10-27T00:00:00Z")

        live.refresh()

        assertEquals(emptyList<NewsItem>(), live.news())
    }

    @Test
    fun `every write bumps changes`() = runTest {
        serveRecorded()
        val before = live.changes.value
        live.refresh()
        assertEquals(before + 1, live.changes.value)
    }
}
