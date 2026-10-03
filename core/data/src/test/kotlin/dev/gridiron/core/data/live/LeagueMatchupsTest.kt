package dev.gridiron.core.data.live

import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ScoresRepository
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import dev.gridiron.core.testing.FakePrefsSource
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
import java.time.Instant

/** League matchups with the app's points, against the real ETL-built database and a canned ESPN response. */
class LeagueMatchupsTest {
    @TempDir
    lateinit var dir: File

    private lateinit var executor: JdbcQueryExecutor

    @BeforeEach
    fun setUp() {
        assumeTrue(StatsDb.path != null, "GRIDIRON_STATS_DB not set")
        executor = JdbcQueryExecutor(StatsDb.path!!)
    }

    @AfterEach
    fun tearDown() {
        if (::executor.isInitialized) executor.close()
    }

    private fun entry(id: String, name: String, slot: Int, points: Double) =
        """{"playerId":$id,"lineupSlotId":$slot,"playerPoolEntry":{"appliedStatTotal":$points,"player":{"fullName":"$name"}}}"""

    private suspend fun repo(body: String): FantasyLeagueRepository {
        val prefs = FakePrefsSource()
        val repo = FantasyLeagueRepository(
            prefs, { _, _ -> body }, PlayerDirectory(executor), dir, executor,
        ) { Instant.parse("2026-10-01T00:00:00Z") }
        repo.addLeague("42")
        return repo
    }

    /** Week 4 of 2025's KC-BAL game: its scored players that ESPN could know, with their ESPN ids. */
    private suspend fun knownPlayers(): List<Triple<String, String, Double?>> {
        val game = ScoresRepository(executor, { error("no ESPN") }).detail(2025, 4, "KC", "BAL", ScoringPresets.PPR)
        val scored = (game.home + game.away).filter { it.position != "DST" }
        val xref = executor.query(
            SqlQuery(
                "SELECT player_id, espn_id FROM player_xref WHERE espn_id IS NOT NULL AND player_id IN (${scored.joinToString(",") { "?" }})",
                scored.map { Bind.Text(it.playerId) },
            ),
        ) { it.text(0) to it.text(1) }.toMap()
        return scored.filter { it.playerId in xref }.take(3).map { Triple(xref.getValue(it.playerId), it.name, it.points) }
    }

    @Test
    fun `app points equal the Scores game view's, starters sum and the bench is left out of the total`() = runTest {
        val known = knownPlayers()
        assertTrue(known.size >= 2, "the database has too few ESPN ids for KC and BAL")
        val (a, b) = known
        val body = """
            {"schedule":[{"matchupPeriodId":4,
              "home":{"teamId":1,"totalPoints":50.5,"rosterForCurrentScoringPeriod":{"entries":[
                ${entry(a.first, a.second, 0, 12.0)},
                ${entry(b.first, b.second, 20, 7.0)},
                ${entry("99999999", "Nobody Known", 4, 3.0)}]}},
              "away":{"teamId":2,"totalPoints":40.0,"rosterForCurrentScoringPeriod":{"entries":[]}}}]}
        """.trimIndent()

        val result = repo(body).matchups(2025, 4, ScoringPresets.PPR)

        assertNull(result.error)
        val home = result.matchups.single().home
        val byEspn = home.lineup.associateBy { it.espnId }
        assertEquals(12.0, byEspn.getValue(a.first).espnPoints)
        assertEquals(a.third, byEspn.getValue(a.first).appPoints)
        assertEquals(b.third, byEspn.getValue(b.first).appPoints)
        assertNull(byEspn.getValue("99999999").playerId)
        assertNull(byEspn.getValue("99999999").appPoints)
        // The bench player's points are in the lineup but not the total; the unmatched starter adds nothing.
        assertEquals(a.third, home.appTotal)
        assertEquals(50.5, home.espnTotal)
        assertNull(result.matchups.single().away!!.appTotal)
    }

    @Test
    fun `a D-ST gets app points through its team id`() = runTest {
        val game = ScoresRepository(executor, { error("no ESPN") }).detail(2025, 4, "KC", "BAL", ScoringPresets.PPR)
        val dst = game.home.single { it.position == "DST" }
        val body = """
            {"schedule":[{"matchupPeriodId":4,
              "home":{"teamId":1,"totalPoints":9.0,"rosterForCurrentScoringPeriod":{"entries":[${entry("-16012", "Chiefs D/ST", 16, 9.0)}]}}}]}
        """.trimIndent()

        val side = repo(body).matchups(2025, 4, ScoringPresets.PPR).matchups.single().home

        assertEquals("DST_KC", dst.playerId)
        assertNotNull(side.lineup.single().appPoints)
        assertEquals(dst.points, side.lineup.single().appPoints)
        assertEquals(dst.points, side.appTotal)
    }
}
