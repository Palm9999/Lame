package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EspnBlendTest {
    private val model = mapOf("targets" to 8.0, "receiving_yards" to 70.0, "receiving_tds" to 0.4, "receiving_tds_40" to 0.08, "carries" to 0.5)

    @Test
    fun `a WR moves his position's weight toward ESPN, and long TDs keep their share`() {
        val espn = mapOf("targets" to 10.0, "receiving_yards" to 90.0, "receiving_tds" to 0.6)
        val w = K.ESPN_WEIGHT.getValue("WR")
        val out = withEspn(model, espn, "WR")

        assertEquals((1 - w) * 8.0 + w * 10.0, out.getValue("targets"), 1e-12)
        assertEquals((1 - w) * 70.0 + w * 90.0, out.getValue("receiving_yards"), 1e-12)
        val tds = (1 - w) * 0.4 + w * 0.6
        assertEquals(tds, out.getValue("receiving_tds"), 1e-12)
        assertEquals(0.08 * tds / 0.4, out.getValue("receiving_tds_40"), 1e-12)
        // ESPN made a projection without carries: it projects none.
        assertEquals((1 - w) * 0.5, out.getValue("carries"), 1e-12)
    }

    @Test
    fun `without an ESPN projection, or for a kicker, the model stands alone`() {
        assertSame(model, withEspn(model, null, "WR"))
        assertSame(model, withEspn(model, emptyMap(), "WR"))
        assertSame(model, withEspn(model, mapOf("targets" to 3.0), "K"))
    }

    @Test
    fun `the waterfall note reads ESPN's projection in reference points`() {
        assertEquals("ESPN projects 15.0 pts", espnNote(mapOf("receptions" to 6.0, "receiving_yards" to 90.0)))
    }
}
