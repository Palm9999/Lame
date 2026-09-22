package dev.gridiron.core.data

import dev.gridiron.core.statquery.StatColumn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class StatFormatTest {
    private val us = StatFormat(Locale.US)

    @Test
    fun `shares and rates render as percentages`() {
        assertEquals("27.3%", us.format(StatColumn.TARGET_SHARE, 0.2734, perGame = false))
        assertEquals("100.0%", us.format(StatColumn.SNAP_SHARE, 1.0, perGame = true))
    }

    @Test
    fun `counting stats are whole as totals and one decimal per game`() {
        assertEquals("1412", us.format(StatColumn.RECEIVING_YARDS, 1412.0, perGame = false))
        assertEquals("83.1", us.format(StatColumn.RECEIVING_YARDS, 83.0588, perGame = true))
    }

    @Test
    fun `efficiency stats keep their own precision`() {
        assertEquals("0.87", us.format(StatColumn.WOPR, 0.8662, perGame = false))
        assertEquals("11.4", us.format(StatColumn.ADOT, 11.43, perGame = false))
        assertEquals("0.21", us.format(StatColumn.EPA_PER_DROPBACK, 0.2149, perGame = true))
    }

    @Test
    fun `missing values and non-finite values render as a dash`() {
        assertEquals(StatFormat.MISSING, us.format(StatColumn.ADOT, null, perGame = false))
        assertEquals(StatFormat.MISSING, us.format(StatColumn.ADOT, Double.NaN, perGame = false))
    }

    @Test
    fun `tiny negatives never render as negative zero`() {
        assertEquals("0.0", us.format(StatColumn.CPOE, -0.04, perGame = false))
        assertEquals("0.00", us.format(StatColumn.RUSH_EPA_PER_CARRY, -0.001, perGame = false))
        assertEquals("-0.1", us.format(StatColumn.CPOE, -0.06, perGame = false))
    }

    @Test
    fun `follows the device locale`() {
        assertEquals("27,3%", StatFormat(Locale.GERMANY).format(StatColumn.TARGET_SHARE, 0.2734, perGame = false))
    }
}
