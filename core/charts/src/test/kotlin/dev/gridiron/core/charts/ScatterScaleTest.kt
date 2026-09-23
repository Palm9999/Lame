package dev.gridiron.core.charts

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScatterScaleTest {
    private fun p(id: String, x: Float, y: Float) = ScatterPoint(id, x, y)

    @Test
    fun boundsStartAtZeroAndRoundUpToAFive() {
        assertEquals(0f..25f, ScatterScale.bounds(listOf(p("a", 12f, 22.3f), p("b", 20.1f, 8f))))
    }

    @Test
    fun negativeValuesExtendTheLowerBound() {
        assertEquals(-5f..20f, ScatterScale.bounds(listOf(p("a", -1.2f, 3f), p("b", 16f, 18f))))
    }

    @Test
    fun emptyOrFlatDataStillHasARange() {
        assertEquals(0f..20f, ScatterScale.bounds(emptyList()))
        assertEquals(0f..5f, ScatterScale.bounds(listOf(p("a", 0f, 0f))))
    }

    @Test
    fun ticksEveryFiveWhenTheRangeIsSmallAndEveryTenWhenLarge() {
        assertEquals(listOf(0f, 5f, 10f, 15f, 20f, 25f), ScatterScale.ticks(0f..25f))
        assertEquals(listOf(0f, 10f, 20f, 30f, 40f), ScatterScale.ticks(0f..40f))
    }

    @Test
    fun nearestPicksTheClosestPointWithinTheRadius() {
        val pts = listOf(p("a", 1f, 1f), p("b", 2f, 2f))
        val screen = { q: ScatterPoint -> Offset(q.x * 100, q.y * 100) }
        assertEquals("b", ScatterScale.nearest(pts, screen, Offset(190f, 205f), 30f)?.id)
        assertNull(ScatterScale.nearest(pts, screen, Offset(500f, 500f), 30f))
    }
}
