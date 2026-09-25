package dev.gridiron.core.data

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterTextTest {
    private fun info(id: String, abbr: String) = MetricInfo(id, abbr, abbr, "", null, null, null)
    private val season = SeasonInfo(2025, lastWeek = 22)
    private val catalog = Catalog(
        persistentListOf(season),
        persistentMapOf(
            "targets" to info("targets", "TGT"),
            "catch_rate" to info("catch_rate", "CTCH%"),
            "snap_share" to info("snap_share", "SNAP%"),
        ),
    )

    @Test
    fun `percent columns read back in percent without float noise`() {
        assertEquals(0.65, FilterUnits.toStored(StatColumn.CATCH_RATE, 65.0))
        assertEquals(65.0, FilterUnits.toInput(StatColumn.CATCH_RATE, 0.65))
        assertEquals(57.3, FilterUnits.toInput(StatColumn.SNAP_SHARE, 0.573))
        assertEquals(50.0, FilterUnits.toStored(StatColumn.TARGETS, 50.0))
        assertEquals("CTCH% ≥ 65", describeFilter(Filter(StatColumn.CATCH_RATE, Condition.AtLeast(0.65)), catalog))
    }

    @Test
    fun `each operator has a short form`() {
        assertEquals("TGT ≥ 50", describeFilter(Filter(StatColumn.TARGETS, Condition.AtLeast(50.0)), catalog))
        assertEquals("TGT ≤ 7.5", describeFilter(Filter(StatColumn.TARGETS, Condition.AtMost(7.5)), catalog))
        assertEquals("TGT 20–40", describeFilter(Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.0)), catalog))
    }

    @Test
    fun `the view line names everything that shapes the table`() {
        val r = GridRequest(
            season, dev.gridiron.core.model.WeekRange(1, 8), StatPack.RECEIVING,
            positions = PositionFilter.WR, perGame = true, teams = setOf("KC", "BUF"), minSnapShare = 0.5,
            filters = listOf(Filter(StatColumn.TARGETS, Condition.AtLeast(5.0))),
        )
        assertEquals(
            "Gridiron · 2025 · Wk 1–8 · Receiving · WR · PPR · per game · BUF/KC · SNAP% ≥ 50 · TGT ≥ 5",
            describeView(r, catalog),
        )
    }

    @Test
    fun `filter picker lists the current pack first, then every other column once`() {
        val order = filterColumnOrder(StatPack.RUSHING)
        assertEquals(StatPack.RUSHING.columns, order.take(StatPack.RUSHING.columns.size))
        assertEquals(StatColumn.entries.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test
    fun `request rejects too many filters and odd snap shares`() {
        val f = Filter(StatColumn.TARGETS, Condition.AtLeast(1.0))
        assertTrue(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = List(8) { f }) }.isSuccess)
        assertFalse(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, filters = List(9) { f }) }.isSuccess)
        assertFalse(runCatching { GridRequest(season, season.defaultWeeks, StatPack.RECEIVING, minSnapShare = 1.5) }.isSuccess)
    }
}
