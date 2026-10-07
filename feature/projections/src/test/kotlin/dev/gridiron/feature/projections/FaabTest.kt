package dev.gridiron.feature.projections

import org.junit.Assert.assertEquals
import org.junit.Test

class FaabTest {
    @Test
    fun `a bid scales with the gain, stops at half of what is left and is at least a dollar`() {
        assertEquals(25, faabBid(60.0, 50))
        assertEquals(25, faabBid(200.0, 50))
        assertEquals(10, faabBid(12.0, 50))
        assertEquals(1, faabBid(0.3, 50))
        assertEquals(0, faabBid(10.0, 0))
        assertEquals(0, faabBid(0.0, 50))
    }
}
