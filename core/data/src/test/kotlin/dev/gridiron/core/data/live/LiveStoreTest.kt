package dev.gridiron.core.data.live

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant

class LiveStoreTest {
    @TempDir
    lateinit var dir: File

    private lateinit var db: LiveDb

    @BeforeEach
    fun open() {
        db = LiveDb(File(dir, "live.db"))
    }

    @AfterEach
    fun close() {
        db.close()
    }

    private val t0 = Instant.parse("2026-09-25T12:00:00Z")
    private val statusNames = mapOf("A" to "Active", "Q" to "Questionable", "D" to "Doubtful", "O" to "Out", "IR" to "Injured Reserve")

    private fun article(id: String, at: Instant, vararg athletes: Pair<String, String>) =
        NewsArticle(id, at, "Headline $id", null, "https://x/$id", athletes.map { TaggedAthlete(it.first, it.second) })

    private fun injury(espnId: String, abbr: String, comment: String?, date: Instant? = t0, team: String = "KC", name: String = "Player $espnId") =
        EspnInjury(espnId, name, team, "WR", statusNames.getValue(abbr), abbr, comment, null, date)

    @Test
    fun `news comes back newest first with linked and unlinked players`() = runTest {
        db.write {
            it.saveNews(listOf(article("1", t0, "10" to "Linked Guy", "11" to "Unknown Guy"), article("2", t0.plusSeconds(60))), mapOf("10" to "P10"))
        }
        val news = db.read { it.news(null) }

        assertEquals(listOf("2", "1"), news.map { it.id })
        assertEquals(listOf(NewsPlayer("Linked Guy", "P10"), NewsPlayer("Unknown Guy", null)), news[1].players)
        assertEquals(listOf("1"), db.read { it.news("P10") }.map { it.id })
    }

    @Test
    fun `saving an article again replaces its tags instead of duplicating them`() = runTest {
        db.write { it.saveNews(listOf(article("1", t0, "10" to "A")), emptyMap()) }
        db.write { it.saveNews(listOf(article("1", t0, "10" to "A", "12" to "B")), emptyMap()) }
        assertEquals(listOf("A", "B"), db.read { it.news(null) }.single().players.map { it.name })
    }

    @Test
    fun `the status snapshot is replaced and notes are appended only when the comment changes`() = runTest {
        val ids = mapOf("10" to "P10", "11" to "P11")
        db.write { it.saveInjuries(listOf(injury("10", "Q", "Limited Wednesday"), injury("11", "O", "Out for the week")), ids, t0) }
        db.write { it.saveInjuries(listOf(injury("10", "Q", "Limited Wednesday", date = t0.plusSeconds(3600))), ids, t0.plusSeconds(3600)) }
        db.write { it.saveInjuries(listOf(injury("10", "D", "Did not practice Thursday", date = t0.plusSeconds(7200))), ids, t0.plusSeconds(7200)) }

        assertEquals("D", db.read { it.status("P10") }?.abbr)
        assertNull(db.read { it.status("P11") })
        assertEquals(listOf("Did not practice Thursday", "Limited Wednesday"), db.read { it.notes("P10") }.map { it.comment })
        // A player's history outlives their place on the report.
        assertEquals(listOf("Out for the week"), db.read { it.notes("P11") }.map { it.comment })
    }

    @Test
    fun `badges skip active players and players with no app id`() = runTest {
        db.write {
            it.saveInjuries(listOf(injury("10", "Q", null), injury("11", "A", null), injury("12", "IR", null)), mapOf("10" to "P10", "11" to "P11"), t0)
        }
        assertEquals(mapOf("P10" to "Q"), db.read { it.badges() })
    }

    @Test
    fun `the injury list leaves out active players and orders by team, then severity, then name`() = runTest {
        db.write {
            it.saveInjuries(
                listOf(
                    injury("1", "Q", null, team = "KC", name = "Zed"),
                    injury("2", "O", null, team = "KC", name = "Amy"),
                    injury("3", "A", null, team = "KC"),
                    injury("4", "D", null, team = "BUF", name = "Bo"),
                ),
                emptyMap(),
                t0,
            )
        }
        assertEquals(listOf("Bo", "Amy", "Zed"), db.read { it.injuries() }.map { it.name })
    }

    @Test
    fun `rows older than thirty days are pruned`() = runTest {
        val old = t0.minus(Duration.ofDays(31))
        db.write {
            it.saveNews(listOf(article("old", old, "10" to "A"), article("new", t0)), emptyMap())
            it.saveInjuries(listOf(injury("10", "Q", "old note", date = old)), mapOf("10" to "P10"), t0)
            it.saveInjuries(listOf(injury("10", "Q", "new note", date = t0)), mapOf("10" to "P10"), t0)
            it.prune(t0)
        }
        assertEquals(listOf("new"), db.read { it.news(null) }.map { it.id })
        assertEquals(listOf("new note"), db.read { it.notes("P10") }.map { it.comment })
        // The pruned article's tag went with it.
        assertEquals(emptySet<String>(), db.read { it.unlinkedEspnIds() })
    }

    @Test
    fun `relinking fills in player ids that weren't known before`() = runTest {
        db.write {
            it.saveNews(listOf(article("1", t0, "10" to "A")), emptyMap())
            it.saveInjuries(listOf(injury("10", "Q", "note")), emptyMap(), t0)
        }
        assertEquals(setOf("10"), db.read { it.unlinkedEspnIds() })

        db.write { it.relink(mapOf("10" to "P10")) }

        assertEquals(emptySet<String>(), db.read { it.unlinkedEspnIds() })
        assertEquals(listOf("1"), db.read { it.news("P10") }.map { it.id })
        assertEquals(listOf("note"), db.read { it.notes("P10") }.map { it.comment })
        assertEquals("Q", db.read { it.status("P10") }?.abbr)
    }

    @Test
    fun `meta values round-trip`() = runTest {
        assertNull(db.read { it.meta("news_fetched_at") })
        db.write { it.setMeta("news_fetched_at", "123") }
        db.write { it.setMeta("news_fetched_at", "456") }
        assertEquals("456", db.read { it.meta("news_fetched_at") })
    }

    @Test
    fun `a failed write rolls back`() = runTest {
        val e = runCatching {
            db.write {
                it.saveNews(listOf(article("1", t0)), emptyMap())
                error("boom")
            }
        }.exceptionOrNull()
        assertEquals("boom", e?.message)
        assertEquals(0, db.read { it.news(null) }.size)
    }

    @Test
    fun `a corrupt file is replaced with an empty database`() = runTest {
        db.close()
        val file = File(dir, "live.db")
        file.writeText("this is not a database")
        db = LiveDb(file)

        assertEquals(emptyList<NewsItem>(), db.read { it.news(null) })
        db.write { it.saveNews(listOf(article("1", t0)), emptyMap()) }
        assertEquals(1, db.read { it.news(null) }.size)
    }
}
