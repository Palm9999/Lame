package dev.gridiron.feature.players

import dev.gridiron.core.statquery.Condition
import dev.gridiron.core.statquery.Filter
import dev.gridiron.core.statquery.StatColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterDraftTest {
    private fun row(column: StatColumn, op: FilterOp, a: String, b: String = "") = FilterRowDraft(0, column, op, a, b)

    @Test
    fun `complete rows become filters in stored units`() {
        assertEquals(Filter(StatColumn.TARGETS, Condition.AtLeast(50.0)), row(StatColumn.TARGETS, FilterOp.AT_LEAST, "50").toFilter())
        assertEquals(Filter(StatColumn.CATCH_RATE, Condition.AtMost(0.6)), row(StatColumn.CATCH_RATE, FilterOp.AT_MOST, "60").toFilter())
        assertEquals(Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.5)), row(StatColumn.TARGETS, FilterOp.BETWEEN, "20", "40,5").toFilter())
        assertEquals(Filter(StatColumn.PASSING_YARDS, Condition.AtLeast(4500.0)), row(StatColumn.PASSING_YARDS, FilterOp.AT_LEAST, "4500").toFilter())
    }

    @Test
    fun `bad or partial input is incomplete, never an exception`() {
        for (bad in listOf("", "-", ".", "abc", "1e3", "NaN", "Infinity", "100001")) {
            assertNull(bad, row(StatColumn.TARGETS, FilterOp.AT_LEAST, bad).toFilter())
        }
        assertNull(row(StatColumn.TARGETS, FilterOp.BETWEEN, "40", "20").toFilter()) // min > max is not swapped
        assertNull(row(StatColumn.TARGETS, FilterOp.BETWEEN, "20", "").toFilter())
        assertFalse(row(StatColumn.TARGETS, FilterOp.AT_LEAST, "").showsError)
        assertTrue(row(StatColumn.TARGETS, FilterOp.AT_LEAST, "abc").showsError)
        assertTrue(row(StatColumn.TARGETS, FilterOp.BETWEEN, "40", "20").showsError)
    }

    @Test
    fun `a draft round-trips applied filters and respects the limit`() {
        val applied = listOf(
            Filter(StatColumn.CATCH_RATE, Condition.AtLeast(0.65)),
            Filter(StatColumn.TARGETS, Condition.Between(20.0, 40.0)),
        )
        val draft = FilterDraft.of(applied)
        assertEquals("65", draft.rows[0].first)
        assertEquals(applied, draft.complete)

        var d = FilterDraft.of(emptyList())
        repeat(10) { d = d.add(StatColumn.TARGETS) }
        assertEquals(8, d.rows.size)
        assertFalse(d.canAdd)
        assertEquals(7, d.remove(d.rows.first().id).rows.size)
        assertTrue(d.clear().rows.isEmpty())
    }
}
