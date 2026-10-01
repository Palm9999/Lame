package dev.gridiron.core.data

import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The shared week-points query against the real ETL-built database. */
class WeekPointsTest {
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

    @Test
    fun `scores only the ids asked for`() = runTest {
        val game = ScoresRepository(executor, { error("no ESPN") }).detail(2025, 4, "KC", "BAL", ScoringPresets.PPR)
        val wanted = (game.home + game.away).take(3)

        val scored = executor.weekPoints(2025, 4, wanted.map { it.playerId }.toSet(), ScoringPresets.PPR)

        assertEquals(wanted.map { it.playerId }.toSet(), scored.map { it.playerId }.toSet())
        for (p in scored) assertEquals(wanted.single { it.playerId == p.playerId }.points, p.points)
        assertTrue(scored.all { it.name.isNotBlank() })
    }

    @Test
    fun `an empty id set asks nothing`() = runTest {
        assertEquals(emptyList<GamePlayer>(), executor.weekPoints(2025, 4, emptySet(), ScoringPresets.PPR))
    }
}
