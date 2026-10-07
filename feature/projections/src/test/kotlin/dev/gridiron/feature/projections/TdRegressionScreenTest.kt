package dev.gridiron.feature.projections

import dev.gridiron.core.data.TdRegressionRow
import org.junit.Assert.assertEquals
import org.junit.Test

class TdRegressionScreenTest {
    @Test
    fun `a line reads touchdowns on expected with the signed gap`() {
        assertEquals("8 TDs on 4.6 expected (+3.4)", tdLine(TdRegressionRow("a", "A", "WR", "KC", 8.0, 4.6)))
        assertEquals("2 TDs on 5.5 expected (−3.5)", tdLine(TdRegressionRow("b", "B", "RB", null, 2.0, 5.5)))
    }
}
