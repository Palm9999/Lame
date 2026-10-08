package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeagueTrendsTest {
    private fun item(at: Long?, vararg moves: ActivityMove) = ActivityItem("i$at", 5, ActivityKind.ADD, 1, null, at, moves.toList())

    private fun move(id: String, from: Int, to: Int) = ActivityMove(id, "p$id", "Name $id", from, to)

    @Test
    fun `counts adds and drops in the window, not trades or older moves`() {
        val trends = leagueTrends(
            listOf(
                item(100, move("1", 0, 3), move("2", 3, 0)),
                item(200, move("1", 0, 4)),
                item(300, move("3", 3, 4)), // a trade: neither
                item(50, move("1", 0, 5)), // before the window
                item(null, move("1", 0, 6)),
            ),
            sinceMillis = 100,
        ).associateBy { it.espnId }
        assertEquals(2, trends.getValue("1").adds)
        assertEquals(1, trends.getValue("2").drops)
        assertEquals(setOf("1", "2"), trends.keys)
    }
}
