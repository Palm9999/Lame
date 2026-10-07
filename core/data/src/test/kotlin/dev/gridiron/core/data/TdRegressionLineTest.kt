package dev.gridiron.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TdRegressionLineTest {
    private fun row(tds: Double, expected: Double) = TdRegressionRow("p", "P", "WR", "KC", tds, expected)

    @Test
    fun `the Player page says which way he runs and by how much`() {
        assertEquals("7 TDs against 4.3 expected through week 5: running hot (+2.7)", tdRegressionLine(row(7.0, 4.3), 5))
        assertEquals("1 TDs against 3.6 expected through week 5: running cold (-2.6)", tdRegressionLine(row(1.0, 3.6), 5))
    }

    @Test
    fun `Trade's tag is signed and the board maps gaps by player`() {
        assertEquals("TDs -2.6 vs exp", tdGapTag(-2.6))
        assertEquals(mapOf("p" to 2.0), TdRegressionBoard(5, listOf(row(5.0, 3.0))).gaps)
    }
}
