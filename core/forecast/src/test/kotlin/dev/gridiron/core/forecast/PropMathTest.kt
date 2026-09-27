package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.E
import kotlin.math.exp
import kotlin.math.ln

class PropMathTest {
    @Test
    fun `names compare without accents, punctuation, suffixes or case`() {
        assertEquals("amonra st brown", normalizeName("Amon-Ra St. Brown"))
        assertEquals("marvin harrison", normalizeName("Marvin Harrison Jr."))
        assertEquals(normalizeName("DJ Moore"), normalizeName("D.J. Moore"))
        assertEquals(normalizeName("Kenneth Walker"), normalizeName("Kenneth Walker III"))
        assertEquals("donta foreman", normalizeName("D'Onta  Foreman"))
        assertEquals("jose gonzalez", normalizeName("José González"))
    }

    @Test
    fun `log gamma matches known values`() {
        assertEquals(ln(24.0), lnGamma(5.0), 1e-12)
        assertEquals(0.5723649429247004, lnGamma(0.5), 1e-12) // ln √π
        assertEquals(0.0, lnGamma(1.0), 1e-12)
    }

    @Test
    fun `the regularized incomplete gamma matches closed forms on both of its branches`() {
        // Shape 1 is the exponential: P = 1 - e^-x. x = 0.5 takes the series, x = 3 the continued fraction.
        assertEquals(1 - exp(-0.5), regularizedGammaP(1.0, 0.5), 1e-12)
        assertEquals(1 - exp(-3.0), regularizedGammaP(1.0, 3.0), 1e-12)
        // Shape 2: P = 1 - e^-x (1 + x).
        assertEquals(1 - 2 / E, regularizedGammaP(2.0, 1.0), 1e-12)
        assertEquals(1 - 6 * exp(-5.0), regularizedGammaP(2.0, 5.0), 1e-12)
        assertEquals(0.0, regularizedGammaP(2.0, 0.0))
    }

    @Test
    fun `a line and its chance of going over give the mean`() {
        // Variance m² makes every Gamma an exponential: P(X > 10) = e^(-10/m), so m = 10 / ln(1/p).
        assertEquals(10 / ln(2.0), meanForLine(10.0, 0.5) { m -> m * m }!!, 1e-6)
        assertEquals(10 / ln(4.0), meanForLine(10.0, 0.25) { m -> m * m }!!, 1e-6)
        // With layer 7's variance, the mean found gives back the chance it was found from.
        val mean = meanForLine(64.5, 0.55) { m -> varianceFor(m, 0.70) }!!
        val v = varianceFor(mean, 0.70)
        assertEquals(0.55, 1 - regularizedGammaP(mean * mean / v, 64.5 * mean / v), 1e-9)
        assertTrue(mean > 64.5, "a Gamma's median is below its mean: $mean")
    }

    @Test
    fun `a chance of zero or one, or a line of zero, has no mean`() {
        assertNull(meanForLine(10.0, 0.0) { m -> m * m })
        assertNull(meanForLine(10.0, 1.0) { m -> m * m })
        assertNull(meanForLine(0.0, 0.5) { m -> m * m })
    }

    @Test
    fun `a two-way price loses its margin in proportion`() {
        assertEquals((1 / 1.87) / (1 / 1.87 + 1 / 1.95), devig(1.87, 1.95), 1e-12)
        assertEquals(0.5, devig(1.9, 1.9), 1e-12)
    }

    @Test
    fun `an anytime TD is de-vigged against No, or discounted when the book only offers Yes`() {
        assertEquals(devig(2.2, 1.65), anytimeTdChance(2.2, 1.65), 1e-12)
        assertEquals((1 / 1.5) / K.ONE_SIDED_OVERROUND, anytimeTdChance(1.5, null), 1e-12)
        assertEquals(-ln(1 - 0.4), tdsFromChance(0.4), 1e-12)
        // A price that says a TD is certain is capped, never infinite.
        assertTrue(tdsFromChance(1.0).isFinite())
    }

    @Test
    fun `the blend weighs each mean by the inverse of its variance`() {
        val vModel = varianceFor(60.0, 0.7)
        val vMarket = K.MARKET_VARIANCE_RATIO * varianceFor(70.0, 0.7)
        val expected = (60.0 / vModel + 70.0 / vMarket) / (1 / vModel + 1 / vMarket)
        assertEquals(expected, blendMean(60.0, 70.0, 0.7), 1e-9)
        assertTrue(blendMean(60.0, 70.0, 0.7) > 65.0, "the market is weighted more heavily")
        assertEquals(60.0, blendMean(60.0, 60.0, 0.7), 1e-9)
        // A side with nothing gives way to the other.
        assertEquals(70.0, blendMean(0.0, 70.0, 0.7))
        assertEquals(60.0, blendMean(60.0, 0.0, 0.7))
    }

    @Test
    fun `each market's books are averaged, and a line needs both sides and real prices`() {
        val quotes = listOf(
            PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
            PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
            PropQuote("betmgm", "player_reception_yds", "Puka Nacua", 90.5, 1.9, null), // one side: skipped
            PropQuote("caesars", "player_reception_yds", "Puka Nacua", 70.5, 1.0, 1.9), // no real price: skipped
            PropQuote("draftkings", "player_anytime_td", "Puka Nacua", null, 2.2, 1.65),
            PropQuote("fanduel", "player_anytime_td", "Puka Nacua", null, 2.3, null),
        )

        val views = marketViews(quotes, 0.7)

        val variance = { m: Double -> varianceFor(m, 0.7) }
        val yards = (meanForLine(84.5, devig(1.87, 1.95), variance)!! + meanForLine(85.5, 0.5, variance)!!) / 2
        val tds = (tdsFromChance(devig(2.2, 1.65)) + tdsFromChance(anytimeTdChance(2.3, null))) / 2
        assertEquals(listOf("player_reception_yds", "player_anytime_td"), views.map { it.market })
        assertEquals(yards, views[0].mean, 1e-9)
        assertEquals("85.0 rec yds", views[0].label)
        assertEquals(tds, views[1].mean, 1e-9)
        assertEquals("TD ${Math.round((1 - exp(-tds)) * 100)}%", views[1].label)
    }

    @Test
    fun `no usable quote means no view`() {
        val quotes = listOf(PropQuote("betmgm", "player_receptions", "Puka Nacua", 6.5, 1.9, null))
        assertTrue(marketViews(quotes, 0.7).isEmpty())
    }
}
