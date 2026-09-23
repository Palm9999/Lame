package dev.gridiron.core.data

import dev.gridiron.core.data.DecimalInput.Result.Blank
import dev.gridiron.core.data.DecimalInput.Result.Invalid
import dev.gridiron.core.data.DecimalInput.Result.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DecimalInputTest {
    @Test
    fun `parses what people type`() {
        assertEquals(Value(6.0), DecimalInput.parse("6"))
        assertEquals(Value(0.04), DecimalInput.parse("0.04"))
        assertEquals(Value(0.04), DecimalInput.parse(".04"))
        assertEquals(Value(-2.0), DecimalInput.parse("-2"))
        assertEquals(Value(1.5), DecimalInput.parse("1,5")) // comma decimal, any locale
        assertEquals(Value(1.0), DecimalInput.parse("1."))
        assertEquals(Value(3.0), DecimalInput.parse(" 3 "))
    }

    @Test
    fun `rejects everything else without throwing`() {
        assertEquals(Blank, DecimalInput.parse(""))
        assertEquals(Blank, DecimalInput.parse("   "))
        for (bad in listOf("-", ".", ",", "1e3", "NaN", "Infinity", "1.2.3", "1,000.5", "abc", "99999", "--1")) {
            assertEquals(Invalid, DecimalInput.parse(bad), bad)
        }
    }

    @Test
    fun `a caller can raise the limit`() {
        assertEquals(Value(4500.0), DecimalInput.parse("4500", limit = 100_000.0))
        assertEquals(Invalid, DecimalInput.parse("4500"))
        assertEquals(Invalid, DecimalInput.parse("100001", limit = 100_000.0))
    }

    @Test
    fun `formats without noise`() {
        assertEquals("0.04", DecimalInput.format(0.04))
        assertEquals("6", DecimalInput.format(6.0))
        assertEquals("-0.5", DecimalInput.format(-0.5))
        assertEquals("0", DecimalInput.format(-0.0))
    }
}
