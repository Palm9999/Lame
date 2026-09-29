package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayerStatSetsTest {
    @Test
    fun `every position has a game log that leads with fantasy points`() {
        for (p in Position.entries) {
            val log = PlayerStatSets.logColumns(p)
            assertEquals(StatColumn.FANTASY_POINTS, log.first(), "$p")
            assertEquals(5, log.size, "$p")
            assertEquals(log.distinct(), log, "$p")
        }
    }

    @Test
    fun `kickers and defenses log no expected points`() {
        for (p in listOf(Position.K, Position.DST)) {
            val log = PlayerStatSets.logColumns(p)
            assertTrue(StatColumn.EXPECTED_FANTASY_POINTS !in log && StatColumn.FPOE !in log, "$p")
        }
    }

    @Test
    fun `an unknown position logs like a receiver, and a fullback like a back`() {
        assertEquals(PlayerStatSets.logColumns(Position.WR), PlayerStatSets.logColumns(null))
        assertEquals(PlayerStatSets.logColumns(Position.RB), PlayerStatSets.logColumns(Position.FB))
    }

    @Test
    fun `each position's game log is scored under its own stats`() {
        assertTrue(StatColumn.PASSING_YARDS in PlayerStatSets.logColumns(Position.QB))
        assertTrue(StatColumn.CARRIES in PlayerStatSets.logColumns(Position.RB))
        assertTrue(StatColumn.TARGETS in PlayerStatSets.logColumns(Position.TE))
        assertTrue(StatColumn.FG_ATT in PlayerStatSets.logColumns(Position.K))
        assertTrue(StatColumn.POINTS_ALLOWED in PlayerStatSets.logColumns(Position.DST))
    }

    @Test
    fun `a defense's game log reads fantasy points, points allowed, yards allowed, sacks and interceptions`() {
        assertEquals(
            listOf(StatColumn.FANTASY_POINTS, StatColumn.POINTS_ALLOWED, StatColumn.YARDS_ALLOWED, StatColumn.DST_SACKS, StatColumn.DST_INTERCEPTIONS),
            PlayerStatSets.logColumns(Position.DST),
        )
    }
}
