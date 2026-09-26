package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.METRICS
import dev.gridiron.core.ingest.db.StatsDbWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatabaseChecksTest {
    @TempDir
    lateinit var dir: File

    private fun problems(vararg facts: Pair<String, Double>): List<String> =
        StatsDbWriter.create(File(dir, "t.db")).use { w ->
            w.writeMetrics(METRICS)
            w.execute("INSERT INTO player (player_id, full_name, search_name, position, team) VALUES ('p1', 'Test Player', 'test player', 'WR', 'AAA')")
            for ((metric, value) in facts) {
                w.execute("INSERT INTO player_week_stat VALUES (?, ?, ?, ?, ?, ?)", "p1", 2025, 1, "AAA", metric, value)
            }
            validateDatabase(w.connection)
        }

    @Test
    fun `a computed metric with facts fails`() {
        assertTrue(problems("g" to 1.0, "target_share" to 0.2, "fantasy_points" to 12.0).any { "computed" in it })
    }

    @Test
    fun `long TD counts must nest`() {
        val p = problems("g" to 1.0, "target_share" to 0.2, "receiving_tds" to 1.0, "receiving_tds_40" to 1.0, "receiving_tds_50" to 2.0)
        assertTrue(p.any { "50+ receiving" in it }, "$p")
    }

    @Test
    fun `consistent scoring inputs pass`() {
        assertEquals(
            emptyList<String>(),
            problems("g" to 1.0, "target_share" to 0.2, "receptions" to 3.0, "receiving_tds" to 2.0,
                "receiving_tds_40" to 1.0, "receiving_first_downs" to 2.0),
        )
    }

    @Test
    fun `efficiency carries exceeding carries fail`() {
        val p = problems("g" to 1.0, "target_share" to 0.2, "carries" to 1.0, "carries_eff" to 2.0, "team_carries" to 2.0)
        assertTrue(p.any { "efficiency carries within carries" in it }, "$p")
    }

    @Test
    fun `efficiency carries within carries and team carries pass`() {
        assertEquals(emptyList<String>(), problems("g" to 1.0, "target_share" to 0.2, "carries" to 2.0, "carries_eff" to 1.0, "team_carries" to 3.0))
    }

    @Test
    fun `an out-of-range share fails`() {
        val p = problems("g" to 1.0, "target_share" to 1.4)
        assertTrue(p.any { it.startsWith("target_share: observed [1.400, 1.400] outside expected [0.0, 1.0]") }, "$p")
    }

    @Test
    fun `a week with no target share fails`() {
        assertTrue(problems("g" to 1.0).any { "weeks with no target_share rows" in it })
    }
}
