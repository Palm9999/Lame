package dev.gridiron.core.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class OrdinalsTest {
    @Test
    fun `the usual 1, 2, 3 suffixes`() {
        assertEquals("1st", ordinal(1))
        assertEquals("2nd", ordinal(2))
        assertEquals("3rd", ordinal(3))
        assertEquals("4th", ordinal(4))
        assertEquals("21st", ordinal(21))
        assertEquals("22nd", ordinal(22))
        assertEquals("23rd", ordinal(23))
        assertEquals("101st", ordinal(101))
    }

    @Test
    fun `the 11-13 exception, including in later hundreds`() {
        assertEquals("11th", ordinal(11))
        assertEquals("12th", ordinal(12))
        assertEquals("13th", ordinal(13))
        assertEquals("111th", ordinal(111))
        assertEquals("112th", ordinal(112))
        assertEquals("113th", ordinal(113))
    }

    @Test
    fun `every other number is plain th`() {
        assertEquals("0th", ordinal(0))
        assertEquals("5th", ordinal(5))
        assertEquals("68th", ordinal(68))
        assertEquals("100th", ordinal(100))
    }
}
