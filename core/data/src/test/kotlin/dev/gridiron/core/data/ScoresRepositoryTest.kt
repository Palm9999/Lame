package dev.gridiron.core.data

import dev.gridiron.core.data.live.EspnGame
import dev.gridiron.core.data.live.HttpGet
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.sql.DriverManager
import java.time.Instant

/** The week-by-week scores against the real ETL-built database, with ESPN's scoreboard canned. */
class ScoresRepositoryTest {
    @TempDir
    lateinit var dir: File

    private lateinit var executor: JdbcQueryExecutor
    private val opened = mutableListOf<JdbcQueryExecutor>()
    private var requests = 0

    private fun recorded() = checkNotNull(javaClass.getResource("/espn/scoreboard.json")).readText()

    @BeforeEach
    fun setUp() {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
        opened += executor
    }

    @AfterEach
    fun tearDown() = opened.forEach { it.close() }

    private fun repo(http: HttpGet = HttpGet { error("a finished week must not ask ESPN") }) = ScoresRepository(executor, http)

    /** A copy of the database where [week] of 2025 has no scores yet: what the table holds before nflverse catches up. */
    private fun unplayed(week: Int): JdbcQueryExecutor {
        val copy = File(dir, "unplayed.db")
        File(StatsDb.path!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE game SET home_score = NULL, away_score = NULL WHERE season = 2025 AND week = $week") }
        }
        return JdbcQueryExecutor(copy.path).also { opened += it }
    }

    @Test
    fun `a finished week comes from the game table alone, with scores, spreads and byes`() = runTest {
        val week = repo().week(2025, 4)

        assertEquals(16, week.games.size)
        assertNull(week.liveError)
        val kc = week.games.single { it.home == "KC" }
        assertEquals("BAL", kc.away)
        assertEquals(37 to 20, kc.homeScore to kc.awayScore)
        assertEquals(GameState.FINAL, kc.state)
        assertEquals("KC", kc.winner)
        // nflverse's line is the home side's: -2.5 at home makes the visitor the favorite.
        assertEquals("BAL -2.5", kc.spreadText)
        assertEquals("NE -5.5", week.games.single { it.home == "NE" }.spreadText)
        // 16 games use all 32 teams in week 4 of 2025.
        assertEquals(emptyList<String>(), week.byes)
    }

    @Test
    fun `a week with games to play asks ESPN and overlays kickoff, clock and live scores`() = runTest {
        executor = unplayed(4)
        val live = ScoresRepository(executor) { url ->
            requests++
            assertTrue("week=4" in url && "dates=2025" in url, url)
            recorded()
        }.week(2025, 4)

        assertEquals(1, requests)
        assertNull(live.liveError)
        // ESPN's recorded week-4 games replace the table's blanks: ARI-SEA is final, ATL-WAS is the hand-built live game.
        val ari = live.games.single { it.home == "ARI" }
        assertEquals(GameState.FINAL, ari.state)
        assertEquals(20 to 23, ari.homeScore to ari.awayScore)
        assertEquals(Instant.parse("2025-09-26T00:15:00Z"), ari.kickoff)
        // A game the table lists as unplayed and ESPN doesn't know keeps the table's blank score.
        val other = live.games.first { it.home == "KC" }
        assertNull(other.homeScore)
        assertEquals(GameState.SCHEDULED, other.state)
        // Games with a kickoff come first, in order; the rest keep the table's.
        val kickoffs = live.games.mapNotNull { it.kickoff }
        assertEquals(kickoffs.sorted(), kickoffs)
        assertNotNull(live.games.first().kickoff)
    }

    @Test
    fun `an ESPN failure keeps the table's view and says why`() = runTest {
        executor = unplayed(4)
        val offline = ScoresRepository(executor) { throw IOException("timeout") }.week(2025, 4)
        assertEquals(16, offline.games.size)
        assertTrue(offline.games.all { it.state == GameState.SCHEDULED && it.homeScore == null })
        assertEquals("Couldn't reach ESPN: timeout", offline.liveError)

        val changed = ScoresRepository(executor) { """{"events": [{"id": "1"}]}""" }.week(2025, 4)
        assertEquals(16, changed.games.size)
        assertTrue(changed.liveError!!.startsWith("ESPN changed its scoreboard format"), changed.liveError)
    }

    @Test
    fun `the current week is the first with a game to play, else the season's last`() = runTest {
        assertEquals(22, repo().currentWeek(2025))
        executor = unplayed(7)
        assertEquals(7, repo().currentWeek(2025))
        assertNull(repo().currentWeek(1999))
        assertEquals((1..18).toList() + listOf(19, 20, 21, 22), repo().weeks(2025))
    }

    @Test
    fun `a game's players are both teams' that week, best fantasy week first, scored with the profile`() = runTest {
        val detail = repo().detail(2025, 4, "KC", "BAL", ScoringPresets.PPR)

        assertTrue(detail.home.size >= 10 && detail.away.size >= 10)
        for (side in listOf(detail.home, detail.away)) {
            val points = side.mapNotNull { it.points }
            assertEquals(points.sortedDescending(), points)
        }
        // Mahomes and Lamar Jackson both played that Sunday.
        assertTrue(detail.home.any { it.name.contains("Mahomes") } && detail.away.any { it.name.contains("Jackson") })
        assertTrue(detail.home.any { it.position == "DST" } && detail.away.any { it.position == "DST" })
        assertEquals(GameDetail(emptyList(), emptyList()), repo().detail(2025, 4, "XXX", "YYY", ScoringPresets.PPR))
    }

    @Test
    fun `merge lets ESPN's started and finished games win, and adds a game only ESPN lists`() {
        fun table(home: String, away: String) = ScoreGame(home, away, null, null, GameState.SCHEDULED, null, null, -3.0, 44.5)
        fun espn(home: String, away: String, state: EspnGame.State, hs: Int?, a: Int?, ko: Long) =
            EspnGame(home + away, home, away, Instant.ofEpochSecond(ko), state, "x", hs, a)

        val merged = ScoresRepository.merge(
            listOf(table("AAA", "BBB"), table("CCC", "DDD"), table("EEE", "FFF")),
            listOf(
                espn("CCC", "DDD", EspnGame.State.LIVE, 7, 3, 200),
                espn("AAA", "BBB", EspnGame.State.SCHEDULED, null, null, 100),
                espn("GGG", "HHH", EspnGame.State.FINAL, 10, 9, 50),
            ),
        )

        assertEquals(listOf("GGG", "AAA", "CCC", "EEE"), merged.map { it.home })
        assertEquals(GameState.LIVE, merged.single { it.home == "CCC" }.state)
        assertEquals(7 to 3, merged.single { it.home == "CCC" }.let { it.homeScore to it.awayScore })
        assertEquals(-3.0, merged.single { it.home == "CCC" }.spread)
        assertEquals(GameState.SCHEDULED, merged.single { it.home == "AAA" }.state)
        assertNull(merged.single { it.home == "AAA" }.homeScore)
        assertEquals(GameState.FINAL, merged.single { it.home == "GGG" }.state)
        assertNull(merged.single { it.home == "EEE" }.kickoff)
    }

    @Test
    fun `a week's started teams are those live, final or past their kickoff`() {
        val now = java.time.Instant.parse("2026-10-04T18:00:00Z")
        fun g(home: String, away: String, state: GameState, kickoff: String?) =
            ScoreGame(home, away, null, null, state, kickoff?.let(java.time.Instant::parse), null, null, null)
        val week = ScoresWeek(
            2026, 5,
            listOf(
                g("KC", "BUF", GameState.FINAL, "2026-10-01T00:15:00Z"),
                g("DAL", "NYG", GameState.LIVE, null),
                g("SF", "SEA", GameState.SCHEDULED, "2026-10-04T17:00:00Z"),
                g("GB", "CHI", GameState.SCHEDULED, "2026-10-04T20:25:00Z"),
                g("MIA", "NE", GameState.SCHEDULED, null),
            ),
            emptyList(), null,
        )
        assertEquals(setOf("KC", "BUF", "DAL", "NYG", "SF", "SEA"), week.started(now))
        // GB-CHI kicks off at 20:25: its inactives post at 18:55.
        val early = week.kickoffs(now)
        assertEquals(week.started(now), early.started)
        assertEquals(week.started(now), early.inactivesPosted)
        val posted = week.kickoffs(java.time.Instant.parse("2026-10-04T18:55:00Z"))
        assertEquals(week.started(now) + setOf("GB", "CHI"), posted.inactivesPosted)
        // Each team's kickoff where ESPN gave one.
        assertEquals(java.time.Instant.parse("2026-10-04T20:25:00Z"), early.times["CHI"])
        assertEquals(setOf("KC", "BUF", "SF", "SEA", "GB", "CHI"), early.times.keys)
    }
}
