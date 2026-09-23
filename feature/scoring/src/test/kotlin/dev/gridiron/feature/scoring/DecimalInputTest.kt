package dev.gridiron.feature.scoring

import dev.gridiron.feature.scoring.DecimalInput.Result.Blank
import dev.gridiron.feature.scoring.DecimalInput.Result.Invalid
import dev.gridiron.feature.scoring.DecimalInput.Result.Value
import org.junit.Assert.assertEquals
import org.junit.Test

class DecimalInputTest {
    @Test
    fun parsesWhatPeopleType() {
        assertEquals(Value(6.0), DecimalInput.parse("6"))
        assertEquals(Value(0.04), DecimalInput.parse("0.04"))
        assertEquals(Value(0.04), DecimalInput.parse(".04"))
        assertEquals(Value(-2.0), DecimalInput.parse("-2"))
        assertEquals(Value(1.5), DecimalInput.parse("1,5")) // comma decimal, any locale
        assertEquals(Value(1.0), DecimalInput.parse("1."))
        assertEquals(Value(3.0), DecimalInput.parse(" 3 "))
    }

    @Test
    fun rejectsEverythingElseWithoutThrowing() {
        assertEquals(Blank, DecimalInput.parse(""))
        assertEquals(Blank, DecimalInput.parse("   "))
        for (bad in listOf("-", ".", ",", "1e3", "NaN", "Infinity", "1.2.3", "1,000.5", "abc", "99999", "--1")) {
            assertEquals(bad, Invalid, DecimalInput.parse(bad))
        }
    }

    @Test
    fun formatsWithoutNoise() {
        assertEquals("0.04", DecimalInput.format(0.04))
        assertEquals("6", DecimalInput.format(6.0))
        assertEquals("-0.5", DecimalInput.format(-0.5))
        assertEquals("0", DecimalInput.format(-0.0))
    }
}
