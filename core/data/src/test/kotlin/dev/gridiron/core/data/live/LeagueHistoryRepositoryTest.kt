package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Instant

class LeagueHistoryRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val players = PlayerDirectory(
        object : QueryExecutor {
            override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> = emptyList()
        },
    )

    /** A season where team 2 ("Mine") is owned by {ME}. */
    private fun season(year: Int, extra: String = "") = """
        {"seasonId":$year,"scoringPeriodId":4,"settings":{"name":"Sunday League"},
         "members":[{"id":"{ME}","displayName":"Dr Palm"}],
         "teams":[{"id":1,"name":"Rivals","primaryOwner":"{YOU}"},{"id":2,"name":"Mine","primaryOwner":"{ME}"}]$extra}
    """.trimIndent()

    private val fetched = mutableListOf<String>()
    private val failing = mutableSetOf<Int>()

    private val http = HeaderHttpGet { url, _ ->
        fetched += url
        when {
            "view=mStatus" in url -> season(2026, ""","status":{"previousSeasons":[2016,2024,2025]}""")
            else -> {
                val year = Regex("seasons/(\\d+)/|seasonId=(\\d+)").find(url)!!.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
                if (year in failing) throw IOException("HTTP 500 from espn") else season(year)
            }
        }
    }

    private suspend fun configured(): FantasyLeagueRepository {
        val repo = FantasyLeagueRepository(FakePrefsSource(), http, players, dir) { Instant.parse("2026-10-01T00:00:00Z") }
        repo.setLogin(null, null)
        repo.addLeague("42")
        assertTrue(repo.sync(2026).ok)
        repo.chooseTeam(2)
        fetched.clear()
        return repo
    }

    private fun seasonReads() = fetched.filter { "mMatchupScore" in it }

    @Test
    fun `history reads each season once and the current one every time`() = runTest {
        val repo = configured()
        val first = repo.history(2026)
        assertEquals(listOf(2016, 2024, 2025, 2026), first.seasons.map { it.season })
        assertEquals("{ME}", first.me)
        assertEquals(emptyList<Pair<Int, String>>(), first.skipped)
        assertTrue(seasonReads().any { "leagueHistory/42?seasonId=2016" in it })
        assertEquals(4, seasonReads().size)

        fetched.clear()
        val second = repo.history(2026)
        assertEquals(first.seasons, second.seasons)
        assertEquals(listOf(EspnHistoryParser.url("42", 2026)), seasonReads())
    }

    @Test
    fun `a failed season is skipped and named`() = runTest {
        val repo = configured()
        failing += 2024
        val result = repo.history(2026)
        assertEquals(listOf(2016, 2025, 2026), result.seasons.map { it.season })
        assertEquals(listOf(2024 to "HTTP 500 from espn"), result.skipped)
        // Not cached: the next open asks again.
        failing.clear()
        fetched.clear()
        assertEquals(listOf(2016, 2024, 2025, 2026), repo.history(2026).seasons.map { it.season })
        assertTrue(seasonReads().any { "seasons/2024/" in it })
    }

    @Test
    fun `an unreadable cached file is fetched again`() = runTest {
        val repo = configured()
        repo.history(2026)
        File(dir, "history-42-2025.json").writeText("garbage")
        fetched.clear()
        assertEquals(listOf(2016, 2024, 2025, 2026), repo.history(2026).seasons.map { it.season })
        assertTrue(seasonReads().any { "seasons/2025/" in it })
    }

    @Test
    fun `no league says so`() = runTest {
        val repo = FantasyLeagueRepository(FakePrefsSource(), http, players, dir) { Instant.parse("2026-10-01T00:00:00Z") }
        assertEquals("no league id set", repo.history(2026).error)
    }
}
