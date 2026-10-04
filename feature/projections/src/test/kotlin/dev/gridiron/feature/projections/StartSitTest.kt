package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSitTest {
    private fun row(id: String, points: Double, width: Double = 10.0) =
        ProjectionRow(id, "Player $id", "WR", "KC", points, points - width / 2, points + width / 2)

    @Test
    fun `equal players split the chance, and the chances add to one`() {
        val c = chanceToLead(listOf(row("a", 12.0), row("b", 12.0)))
        assertEquals(1.0, c.sum(), 1e-9)
        assertEquals(0.5, c[0], 0.02)
    }

    @Test
    fun `two players match the normal formula`() {
        val a = row("a", 14.0)
        val b = row("b", 10.0)
        val sd = spread(a)
        val expected = dev.gridiron.core.model.normalCdf(4.0 / kotlin.math.sqrt(2 * sd * sd))
        assertEquals(expected, chanceToLead(listOf(a, b))[0], 0.01)
    }

    @Test
    fun `an Out player never leads, and three players order by projection`() {
        val out = ProjectionRow("o", "Out Guy", "WR", "KC", 0.0, 0.0, 0.0, out = true)
        val c = chanceToLead(listOf(row("a", 15.0), row("b", 12.0), out))
        assertEquals(0.0, c[2], 1e-9)
        assertTrue(c[0] > c[1])
        assertEquals(1.0, c.sum(), 1e-9)
    }
}
