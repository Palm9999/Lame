package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RidgeTest {
    // Every offense gains 10 against A and B and 12 against C: C's defense allows 2 more.
    private val rows = listOf(
        RidgeRow("A", "B", home = false, value = 10.0),
        RidgeRow("A", "C", home = false, value = 12.0),
        RidgeRow("B", "A", home = false, value = 10.0),
        RidgeRow("B", "C", home = false, value = 12.0),
        RidgeRow("C", "A", home = false, value = 10.0),
        RidgeRow("C", "B", home = false, value = 10.0),
    )

    @Test
    fun `cholesky solves a small positive-definite system`() {
        val x = choleskySolve(arrayOf(doubleArrayOf(4.0, 2.0), doubleArrayOf(2.0, 3.0)), doubleArrayOf(2.0, 1.0))
        assertArrayEquals(doubleArrayOf(0.5, 0.0), x, 1e-12)
    }

    @Test
    fun `a light penalty recovers the defensive difference`() {
        val fit = fitRidge(rows, lambda = 1e-6)
        assertEquals(64.0 / 6, fit.mean, 1e-12)
        assertEquals(2.0, fit.defense.getValue("C") - fit.defense.getValue("A"), 1e-3)
        assertEquals(0.0, fit.defense.getValue("B") - fit.defense.getValue("A"), 1e-3)
        assertEquals(2, fit.defenseGames.getValue("C"))
    }

    @Test
    fun `a heavy penalty pulls every rating to zero`() {
        val fit = fitRidge(rows, lambda = 1e6)
        assertTrue(fit.defense.values.all { abs(it) < 1e-3 })
        assertTrue(fit.offense.values.all { abs(it) < 1e-3 })
    }

    @Test
    fun `home advantage is its own coefficient`() {
        val homeRows = listOf(
            RidgeRow("A", "B", home = true, value = 12.0),
            RidgeRow("B", "A", home = false, value = 10.0),
            RidgeRow("B", "A", home = true, value = 12.0),
            RidgeRow("A", "B", home = false, value = 10.0),
        )
        assertEquals(2.0, fitRidge(homeRows, lambda = 1e-6).home, 1e-3)
    }
}
