package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForecastMathTest {
    @Test
    fun `ewma of nothing is null and of one value is that value`() {
        assertNull(ewma(emptyList(), 4.0))
        assertEquals(3.0, ewma(listOf(3.0), 4.0))
    }

    @Test
    fun `ewma with a half-life of one game moves halfway to each new value`() {
        assertEquals(0.5, ewma(listOf(1.0, 0.0), 1.0)!!, 1e-12)
        assertEquals(0.75, ewma(listOf(1.0, 0.0, 1.0), 1.0)!!, 1e-12)
    }

    @Test
    fun `ewmaRatio divides the two averages and needs a positive denominator`() {
        assertEquals(0.5, ewmaRatio(listOf(2.0, 4.0), listOf(4.0, 8.0), 2.0)!!, 1e-12)
        assertNull(ewmaRatio(listOf(1.0), listOf(0.0), 4.0))
        assertNull(ewmaRatio(emptyList(), emptyList(), 4.0))
    }

    @Test
    fun `shrink weighs a sample of n against k pseudo-observations of the baseline`() {
        assertEquals(0.2, shrink(0.3, 5.0, 0.1, 5.0), 1e-12)
        assertEquals(0.1, shrink(null, 3.0, 0.1, 5.0), 1e-12)
        assertEquals(0.1, shrink(0.3, 0.0, 0.1, 5.0), 1e-12)
    }

    @Test
    fun `carryover starts at 0_55 in week 1 and is gone by week 6`() {
        assertEquals(0.55, carryoverWeight(1), 1e-12)
        assertEquals(0.33, carryoverWeight(3), 1e-12)
        assertEquals(0.0, carryoverWeight(6), 1e-12)
        assertEquals(0.0, carryoverWeight(12), 1e-12)
    }

    @Test
    fun `variance is calibrated so the CV at a mean of 10 is the position's`() {
        assertEquals(49.0, varianceFor(10.0, 0.7), 1e-9)
        assertEquals(0.0, varianceFor(0.0, 0.7))
        assertEquals(0.0, varianceFor(-1.0, 0.7))
    }

    @Test
    fun `capAround clamps a multiplier to one plus or minus the cap`() {
        assertEquals(1.15, 1.3.capAround(0.15), 1e-12)
        assertEquals(0.85, 0.5.capAround(0.15), 1e-12)
        assertEquals(1.02, 1.02.capAround(0.05), 1e-12)
    }
}
