package dev.gridiron.core.data

import dev.gridiron.core.model.Position
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatColumn.FTN_BLITZ_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CATCHABLE_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CONTESTED_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_CREATED_REC
import dev.gridiron.core.statquery.StatColumn.FTN_DROPS
import dev.gridiron.core.statquery.StatColumn.FTN_DROP_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_INT_WORTHY_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_OUT_OF_POCKET_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_PLAY_ACTION_RATE
import dev.gridiron.core.statquery.StatColumn.FTN_THROWAWAY_RATE
import dev.gridiron.core.statquery.StatColumn.NGS_AGGRESSIVENESS
import dev.gridiron.core.statquery.StatColumn.NGS_CUSHION
import dev.gridiron.core.statquery.StatColumn.NGS_INTENDED_AIR_YARDS
import dev.gridiron.core.statquery.StatColumn.NGS_RUSH_EFFICIENCY
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE
import dev.gridiron.core.statquery.StatColumn.NGS_RYOE_PER_ATT
import dev.gridiron.core.statquery.StatColumn.NGS_SEPARATION
import dev.gridiron.core.statquery.StatColumn.NGS_STACKED_BOX_PCT
import dev.gridiron.core.statquery.StatColumn.NGS_TIME_TO_THROW
import dev.gridiron.core.statquery.StatColumn.NGS_YAC_OVER_EXPECTED
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

    @Test
    fun `each position's efficiency group carries its Next Gen Stats and FTN charting`() {
        fun efficiency(p: Position) = CompareMetricSets.groupsFor(p).getValue(CompareGroup.EFFICIENCY)
        run {
            assertTrue(efficiency(Position.QB).containsAll(listOf(NGS_TIME_TO_THROW, NGS_INTENDED_AIR_YARDS, NGS_AGGRESSIVENESS, FTN_PLAY_ACTION_RATE, FTN_BLITZ_RATE, FTN_OUT_OF_POCKET_RATE, FTN_THROWAWAY_RATE, FTN_INT_WORTHY_RATE)))
            assertTrue(efficiency(Position.RB).containsAll(listOf(NGS_RYOE, NGS_RYOE_PER_ATT, NGS_RUSH_EFFICIENCY, NGS_STACKED_BOX_PCT)))
            for (p in listOf(Position.WR, Position.TE)) {
                assertTrue(efficiency(p).containsAll(listOf(NGS_SEPARATION, NGS_CUSHION, NGS_YAC_OVER_EXPECTED, FTN_CATCHABLE_RATE, FTN_DROP_RATE, FTN_CONTESTED_RATE)), "$p")
                assertTrue(CompareMetricSets.groupsFor(p).getValue(CompareGroup.CONTEXT).containsAll(listOf(FTN_DROPS, FTN_CREATED_REC)), "$p")
            }
        }
        for (p in listOf(Position.K, Position.DST)) {
            assertTrue(CompareMetricSets.groupsFor(p).values.flatten().none { it in CompareMetricSets.CHARTED }, "$p")
        }
    }

    @Test
    fun `every charted column belongs to some position's set`() {
        val shown = listOf(Position.QB, Position.RB, Position.WR).flatMap { CompareMetricSets.groupsFor(it).values.flatten() }.toSet()
        assertEquals(CompareMetricSets.CHARTED, shown.filter { it in CompareMetricSets.CHARTED }.toSet())
    }
}
