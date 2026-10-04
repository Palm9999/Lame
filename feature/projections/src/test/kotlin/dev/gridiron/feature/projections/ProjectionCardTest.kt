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
private class CardExecutor(
    private val status: String = "ok",
    private val bye: Boolean = false,
    private val defense: Boolean = false,
    private val stash: Boolean = false,
    private val weeks: Boolean = false,
) : QueryExecutor {
    override suspend fun <T> query(query: SqlQuery, map: (ResultRow) -> T): List<T> {
        val sql = query.sql
        val rows: List<List<Any?>> = when {
            "schema_meta" in sql -> listOf(listOf("forecast_status", status), listOf("forecast_week:2026", "4"))
            "FROM player_ros_week" in sql && weeks -> listOf(
                listOf("W1", "WR", 12L, "receptions", 9.0, 1.0),
                listOf("W1", "WR", 15L, "receptions", 5.0, 1.0),
                listOf("W1", "WR", 15L, "receiving_yards", 60.0, 1.0),
                listOf("W1", "WR", 16L, "receptions", 4.0, 1.0),
            )
            "player_week_projection_factor" in sql -> emptyList()
            "FROM player_week_projection" in sql && defense -> listOf(
                listOf("DST_KC", "dst_sacks", "final", 3.0, 3.0, "negbinom"),
                listOf("DST_KC", "points_allowed", "final", 10.0, 0.0, "normal"),
                listOf("DST_KC", "g", "final", 1.0, 0.0, null),
            )
            "FROM player_ros_projection" in sql && defense -> listOf(
                listOf("DST_KC", "dst_sacks", 9.0, 9.0, "negbinom"),
                listOf("DST_KC", "points_allowed", 30.0, 0.0, "normal"),
                listOf("DST_KC", "g", 3.0, 0.0, null),
            )
            "FROM player_ros_projection" in sql && "JOIN player pl" in sql -> listOf(
                listOf("W1", "Wide One", "WR", "KC", "receptions", 40.0, 30.0, "binomial"),
                listOf("W1", "Wide One", "WR", "KC", "receiving_yards", 480.0, 5000.0, "gamma"),
                listOf("W2", "Wide Two", "WR", "BUF", "receptions", 50.0, 30.0, "binomial"),
                listOf("W2", "Wide Two", "WR", "BUF", "receiving_yards", 600.0, 5000.0, "gamma"),
                listOf("W3", "Wide Three", "WR", "MIA", "receptions", 10.0, 30.0, "binomial"),
                listOf("R1", "Run One", "RB", "MIA", "receptions", 90.0, 30.0, "binomial"),
            )
            "FROM player_week_projection" in sql && (bye || stash) -> emptyList()
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
        // W2's 110 beat his 88; W3's 10 and the RB don't count against a WR.
        assertEquals(2, card.rosPlace)
        assertEquals(3, card.rosOf)
    }

    @Test
    fun `the card adds his points in the playoff weeks when weekly projections exist`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(weeks = true)), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = null)!!
        // PPR: week 15 is 5 + 6.0, week 16 is 4; week 12 isn't a playoff week.
        assertEquals(15.0, card.playoffPoints!!, 1e-9)
        assertEquals(listOf(15, 16, 17), card.playoffWeeks)
        val shifted = loadProjectionCard(
            ProjectionsRepository(CardExecutor(weeks = true)), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = null, playoffWeeks = listOf(12),
        )!!
        assertEquals(9.0, shifted.playoffPoints!!, 1e-9)
        // An older database has no weekly rows: no playoff line.
        assertNull(loadProjectionCard(ProjectionsRepository(CardExecutor()), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = null)!!.playoffPoints)
    }

    @Test
    fun `a player out for now with rest of season still gets a card`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(stash = true)), "W1", "KC", ScoringPresets.PPR, Position.WR, injuryAbbr = "IR")!!
        assertTrue(card.notThisWeek)
        assertEquals(false, card.bye)
        assertEquals(88.0, card.rosPoints!!, 1e-9)
        assertEquals("@ BUF", card.matchup)
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

    @Test
    fun `a team defense's card scores each game's points-allowed tier`() = runTest {
        val card = loadProjectionCard(ProjectionsRepository(CardExecutor(defense = true)), "DST_KC", "KC", ScoringPresets.PPR, Position.DST, null)!!

        // 3 sacks, and 10 allowed: the 7-13 tier's 3.
        assertEquals(6.0, card.points, 1e-9)
        assertTrue(card.floor < 6.0 && card.ceiling > 6.0)
        // Three games of 3 sacks and 10 allowed. The tier of 30 allowed would be -4.
        assertEquals(18.0, card.rosPoints!!, 1e-9)
        assertEquals(6.0, card.rosPerGame!!, 1e-9)
    }
}
