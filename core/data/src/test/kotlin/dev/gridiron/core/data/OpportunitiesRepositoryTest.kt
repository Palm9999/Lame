package dev.gridiron.core.data

import dev.gridiron.core.data.live.LiveInjury
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.testing.JdbcQueryExecutor
import dev.gridiron.core.testing.StatsDb
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "GRIDIRON_STATS_DB", matches = ".+")
class OpportunitiesRepositoryTest {
    private lateinit var executor: JdbcQueryExecutor
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private var hurt: List<LiveInjury> = emptyList()

    private lateinit var repo: OpportunitiesRepository

    @BeforeAll
    fun open() {
        executor = JdbcQueryExecutor(checkNotNull(StatsDb.path))
        repo = OpportunitiesRepository(executor, ProjectionsRepository(executor), { hurt }) { now }
    }

    @AfterAll
    fun close() = executor.close()

    private fun injury(playerId: String, abbr: String) =
        LiveInjury("e-$playerId", playerId, "Player $playerId", null, null, "status", abbr, null, now)

    @Test
    fun `the usage ranking reads a real window of play`() = runTest {
        val usage = repo.usage(2025, WeekRange(15, 18), ScoringPresets.PPR)
        val backs = usage.filter { it.position == "RB" }.groupBy { it.team }
        assertTrue(backs.size >= 30, "${backs.size} teams with backs")
        // Every team has a back who carried and was targeted a lot over four games, and a QB who dropped back a lot.
        val busyBacks = backs.values.count { team -> team.maxOf { it.usage } > 30 }
        val qbs = usage.filter { it.position == "QB" }.groupBy { it.team }
        val busyQbs = qbs.values.count { team -> team.maxOf { it.usage } > 100 }
        assertTrue(busyBacks >= 28, "$busyBacks teams with a busy back")
        assertTrue(busyQbs >= 20, "$busyQbs of ${qbs.size} teams with a busy QB; best ${qbs.values.map { t -> t.maxOf { it.usage } }.sorted()}")
        val noPoints = usage.filter { it.games > 0 && it.pointsPerGame == null }
        assertTrue(noPoints.isEmpty(), "no points for ${noPoints.take(3)}")
    }

    @Test
    fun `a hurt starter's team sends its next man up, projected`() = runTest {
        val season = 2026
        // A team on bye in the upcoming week has no projections to rank; take one that plays. The depth chart is the
        // repository's own window: the last four weeks before the upcoming one.
        val projections = ProjectionsRepository(executor)
        val week = checkNotNull(projections.status().upcoming[season])
        val usage = repo.usage(season, WeekRange(maxOf(1, week - 4), week - 1), ScoringPresets.PPR)
        val team = usage.filter { it.position == "RB" }.groupBy { it.team }.values
            .first { it.size >= 3 && projections.game(season, week, it.first().team) != null }
            .sortedWith(compareByDescending { it.usage })
        hurt = listOf(injury(team[0].playerId, "O"))

        val result = repo.find(season, ScoringPresets.PPR)

        assertEquals(null, result.message)
        val mine = result.rows.filter { it.beneficiary.player.team == team[0].team && it.beneficiary.player.position == "RB" }
        assertEquals(listOf(team[1].playerId, team[2].playerId), mine.map { it.beneficiary.player.playerId }.sortedBy { listOf(team[1].playerId, team[2].playerId).indexOf(it) })
        assertEquals(setOf(1, 2), mine.map { it.beneficiary.toRank }.toSet())
        assertEquals(team[0].playerId, mine.first().beneficiary.injured.single().playerId)
        assertNotNull(mine.first().projected)
        assertTrue(result.rows.zipWithNext().all { (a, b) -> (a.uptick ?: -1e9) >= (b.uptick ?: -1e9) })
    }

    @Test
    fun `a season with no upcoming week says so, and no injuries says so`() = runTest {
        hurt = listOf(injury("nobody", "O"))
        assertEquals("No upcoming games in 2023.", repo.find(2023, ScoringPresets.PPR).message)
        hurt = emptyList()
        assertEquals("No injury list from ESPN yet. Refresh stats.", repo.find(2026, ScoringPresets.PPR).message)
    }
}
