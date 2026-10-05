package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Test

class RosCompareChartTest {
    private fun r(id: String) = ProjectionRow(id, "P $id", "WR", "KC", 10.0, 5.0, 15.0)

    @Test
    fun `series follow the weeks, a bye is null, and no more than three lines`() {
        val weekly = mapOf("a" to mapOf(5 to 10.0, 7 to 12.0), "b" to mapOf(5 to 8.0), "c" to mapOf(6 to 1.0), "d" to mapOf(5 to 2.0))
        val s = chartSeries(listOf(r("a"), r("x"), r("b"), r("c"), r("d")), weekly, listOf(5, 6, 7))
        assertEquals(listOf("a", "b", "c"), s.map { it.playerId })
        assertEquals(listOf(10.0, null, 12.0), s[0].points)
    }
}
