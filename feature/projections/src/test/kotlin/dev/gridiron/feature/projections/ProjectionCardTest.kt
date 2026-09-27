package dev.gridiron.feature.projections

import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.ResultRow
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringPresets
import dev.gridiron.core.projections.GameLine
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

private class CardRow(private val columns: List<Any?>) : ResultRow {
    override fun isNull(index: Int): Boolean = columns[index] == null
    override fun text(index: Int): String = columns[index] as String
    override fun long(index: Int): Long = (columns[index] as Number).toLong()
    override fun double(index: Int): Double = (columns[index] as Number).toDouble()
}

/** Answers the card's queries by what they read. KC is at BUF in week 4, three games left. */
private class CardExecutor(private val status: String = "ok", private val bye: Boolean = false) : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val sql = query.sql
        val rows: List<List<Any?>> = when {
            "schema_meta" in sql -> listOf(listOf("forecast_status", status), listOf("forecast_week:2026", "4"))
            "player_week_projection_factor" in sql -> emptyList()
            "FROM player_week_projection" in sql && bye -> emptyList()
            "FROM player_week_projection" in sql -> listOf(
                listOf("W1", "receptions", "final", 5.0, 5.0, "binomial"),
                listOf("W1", "receiving_yards", "final", 60.0, 900.0, "gamma"),
            )
            "FROM player_ros_projection" in sql -> listOf(
                listOf("W1", "receptions", 40.0, 30.0, "binomial"),
                listOf("W1", "receiving_yards", 480.0, 5000.0, "gamma"),
            )
            "COUNT(*)" in sql -> listOf(listOf(3L))
            "FROM game" in sql && bye -> emptyList()
            "FROM game" in sql -> listOf(listOf("BUF", "KC", 2.5, 47.5))
            else -> emptyList()
        }
        return rows.map { map(CardRow(it)) }
    }
}

class ProjectionCardTest {
    @Test
    fun `the card scores this week and rest of season with the profile`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = "Q")!!

        assertEquals(2026, card.season)
        assertEquals(4, card.week)
        assertEquals("@ BUF", card.matchup)
        assertEquals("KC +2.5 · O/U 47.5", card.line)
        assertEquals(11.0, card.points, 1e-9)
        assertTrue(card.floor < 11.0 && card.ceiling > 11.0)
        assertEquals(88.0, card.rosPoints!!, 1e-9)
        assertEquals(88.0 / 3, card.rosPerGame!!, 1e-9)
        assertEquals(false, card.out)
    }

    @Test
    fun `the scoring runs on the compute dispatcher, not the caller's`() = runTest {
        var dispatches = 0
        val compute = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                block.run()
            }
        }

        loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, null, compute)

        assertTrue("the simulation never ran on the compute dispatcher", dispatches > 0)
    }

    @Test
    fun `an Out or IR player's week is zero`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = "IR")!!
        assertTrue(card.out)
        assertEquals(0.0, card.points, 0.0)
    }

    @Test
    fun `on a bye the card says so and keeps rest of season`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(bye = true)), "W1", "KC", ScoringPresets.PPR, Position.WR, null)!!
        assertTrue(card.bye)
        assertNull(card.matchup)
        assertEquals(88.0, card.rosPoints!!, 1e-9)
    }

    @Test
    fun `no card without a successful forecast`() = runTest {
        assertNull(loadProjectionCard(ProjectionsRepository(CardExecutor(status = "no schedule")), "W1", "KC", ScoringPresets.PPR, Position.WR, null))
    }

    @Test
    fun `lines read from the player's team's side`() {
        assertEquals("vs KC", matchupText(GameLine("BUF", "KC", home = true, spread = 2.5, total = 47.5)))
        assertEquals("BUF −2.5 · O/U 47.5", lineText(GameLine("BUF", "KC", home = true, spread = 2.5, total = 47.5)))
        assertEquals("Pick'em · O/U 44", lineText(GameLine("BUF", "KC", home = true, spread = 0.0, total = 44.0)))
        assertEquals(null, lineText(GameLine("BUF", "KC", home = true, spread = null, total = null)))
    }
}
