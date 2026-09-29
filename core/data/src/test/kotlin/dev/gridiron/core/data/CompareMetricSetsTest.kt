package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompareMetricSetsTest {
    @Test
    fun `each position has all four groups and a fantasy scoring group`() {
        for (p in listOf(Position.QB, Position.RB, Position.WR, Position.TE)) {
            val g = CompareMetricSets.groupsFor(p)
            assertEquals(CompareGroup.entries.toSet(), g.keys, "$p")
            assertTrue(g.getValue(CompareGroup.SCORING).containsAll(listOf(StatColumn.FANTASY_POINTS, StatColumn.EXPECTED_FANTASY_POINTS, StatColumn.FPOE)))
        }
    }

    @Test
    fun `a mixed comparison shows the union in group order without duplicates`() {
        val union = CompareMetricSets.union(listOf(Position.QB, Position.WR))
        assertEquals(CompareGroup.entries, union.map { it.first })
        val opportunity = union.first().second
        assertTrue(StatColumn.DROPBACKS in opportunity && StatColumn.TARGETS in opportunity)
        assertEquals(opportunity.distinct(), opportunity)
    }

    @Test
    fun `radar axes are six to eight and qualifiers follow the spec`() {
        for (p in listOf(Position.QB, Position.RB, Position.WR, Position.TE)) {
            assertTrue(CompareMetricSets.radarAxes(p).size in 6..8)
        }
        assertEquals(StatColumn.DROPBACKS, CompareMetricSets.qualifier(Position.QB))
        assertEquals(StatColumn.CARRIES, CompareMetricSets.qualifier(Position.RB))
        assertEquals(StatColumn.TARGETS, CompareMetricSets.qualifier(Position.WR))
        assertEquals(StatColumn.TARGETS, CompareMetricSets.qualifier(Position.TE))
        assertEquals(CompareMetricSets.groupsFor(Position.RB), CompareMetricSets.groupsFor(Position.FB))
    }

    @Test
    fun `kickers and defenses have their own sets without expected points`() {
        for (p in listOf(Position.K, Position.DST)) {
            val columns = CompareMetricSets.groupsFor(p).values.flatten()
            assertTrue(StatColumn.FANTASY_POINTS in columns, "$p")
            assertTrue(StatColumn.EXPECTED_FANTASY_POINTS !in columns && StatColumn.FPOE !in columns, "$p")
            assertTrue(CompareMetricSets.radarAxes(p).size >= 3, "$p")
        }
        assertTrue(StatColumn.YARDS_ALLOWED in CompareMetricSets.groupsFor(Position.DST).getValue(CompareGroup.EFFICIENCY))
        assertEquals(7, CompareMetricSets.radarAxes(Position.DST).size)
        assertEquals(StatColumn.FG_ATT, CompareMetricSets.qualifier(Position.K))
        assertEquals(StatColumn.POINTS_ALLOWED, CompareMetricSets.qualifier(Position.DST))
    }

    @Test
    fun `a union leaves out groups no compared position has rows for`() {
        assertEquals(
            listOf(CompareGroup.OPPORTUNITY, CompareGroup.EFFICIENCY, CompareGroup.SCORING),
            CompareMetricSets.union(listOf(Position.K)).map { it.first },
        )
        assertEquals(
            listOf(CompareGroup.EFFICIENCY, CompareGroup.SCORING, CompareGroup.CONTEXT),
            CompareMetricSets.union(listOf(Position.DST)).map { it.first },
        )
    }

    @Test
    fun `a kicker beside a quarterback compares on both sets`() {
        val union = CompareMetricSets.union(listOf(Position.K, Position.QB))
        assertEquals(CompareGroup.entries, union.map { it.first })
        val opportunity = union.first().second
        assertTrue(StatColumn.FG_ATT in opportunity && StatColumn.DROPBACKS in opportunity)
    }
}
