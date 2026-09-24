package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfidenceTest {
    @Test
    fun `td dependence is the TD points share of the total`() {
        // 6 points from TDs out of 14.2 total.
        assertEquals(6.0 / 14.2, tdDependence(tdComponentPoints = 6.0, totalPoints = 14.2), 1e-9)
    }

    @Test
    fun `td dependence is zero, not NaN, when total points is zero`() {
        assertEquals(0.0, tdDependence(tdComponentPoints = 0.0, totalPoints = 0.0), 1e-9)
    }

    @Test
    fun `confidence is low for a small shrinkage weight`() {
        assertEquals(ConfidenceLevel.LOW, confidenceFrom(shrinkageWeight = 0.05))
    }

    @Test
    fun `confidence is high for a shrinkage weight near 1`() {
        assertEquals(ConfidenceLevel.HIGH, confidenceFrom(shrinkageWeight = 0.9))
    }
}
