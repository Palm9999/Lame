package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MarketTest {
    private fun quote(player: String, market: String = "player_receptions") = PropQuote("dk", market, player, 5.5, 1.9, 1.9)

    private val candidates = listOf(
        PropCandidate("p1", "Amon-Ra St. Brown", "DET"),
        PropCandidate("p2", "Josh Allen", "BUF"),
        PropCandidate("p3", "Josh Allen", "JAX"),
        PropCandidate("p4", "Mike Williams", "PIT"),
        PropCandidate("p5", "Mike Williams", "PIT"),
    )

    @Test
    fun `a name matches the one player with it on the game's two teams, in either team order`() {
        val snapshot = PropsSnapshot(
            listOf(
                PropEvent("DET", "GB", listOf(quote("Amon Ra St Brown Jr."))),
                PropEvent("MIA", "BUF", listOf(quote("Josh Allen"))), // listed MIA-BUF, scheduled BUF-MIA
            ),
        )

        val match = MarketMatch(snapshot, listOf("DET" to "GB", "BUF" to "MIA"), candidates)

        // "Amon Ra" and "Amon-Ra" differ once punctuation goes, so only an exact normalized match counts.
        assertNull(match.quotes("p1"))
        assertEquals(listOf(quote("Josh Allen")), match.quotes("p2"))
        assertNull(match.quotes("p3")) // the other Josh Allen isn't in that game
    }

    @Test
    fun `a suffix or punctuation difference still matches`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("DET", "GB", listOf(quote("Amon-Ra St. Brown Jr.")))))
        val match = MarketMatch(snapshot, listOf("DET" to "GB"), candidates)
        assertEquals(1, match.quotes("p1")!!.size)
    }

    @Test
    fun `two players with one name in the game match neither`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("PIT", "CLE", listOf(quote("Mike Williams")))))
        val match = MarketMatch(snapshot, listOf("PIT" to "CLE"), candidates)
        assertNull(match.quotes("p4"))
        assertNull(match.quotes("p5"))
    }

    @Test
    fun `an event that isn't one of the week's games matches nobody`() {
        val snapshot = PropsSnapshot(listOf(PropEvent("BUF", "NYJ", listOf(quote("Josh Allen")))))
        val match = MarketMatch(snapshot, listOf("BUF" to "MIA"), candidates)
        assertNull(match.quotes("p2"))
    }

    @Test
    fun `the blend moves each priced stat and splits the TD market by the model's own split`() {
        val final = mapOf("receptions" to 5.0, "receiving_yards" to 60.0, "receiving_tds" to 0.3, "rushing_tds" to 0.1)
        val quotes = listOf(
            PropQuote("dk", "player_receptions", "X", 5.5, 1.8, 2.0),
            PropQuote("dk", ANYTIME_TD, "X", null, 2.2, 1.65),
        )

        val blended = blend(final, quotes, "WR")!!

        val cv = K.EMPIRICAL_CV.getValue("WR")
        val views = marketViews(quotes, cv)
        val tds = blendMean(0.4, views[1].mean, cv)
        assertEquals(blendMean(5.0, views[0].mean, cv), blended.components.getValue("receptions"), 1e-12)
        assertEquals(60.0, blended.components.getValue("receiving_yards")) // no yardage market: unchanged
        assertEquals(0.3 * tds / 0.4, blended.components.getValue("receiving_tds"), 1e-12)
        assertEquals(0.1 * tds / 0.4, blended.components.getValue("rushing_tds"), 1e-12)
        assertEquals("Props: 5.5 rec, TD 43%", blended.note)
    }

    @Test
    fun `a TD market for a player the model gave no TDs goes to his position's usual kind`() {
        val quotes = listOf(PropQuote("dk", ANYTIME_TD, "X", null, 2.2, 1.65))
        val cv = K.EMPIRICAL_CV.getValue("RB")
        val tds = marketViews(quotes, cv).single().mean

        val rb = blend(mapOf("carries" to 12.0), quotes, "RB")!!
        val te = blend(mapOf("targets" to 4.0), quotes, "TE")!!

        assertEquals(tds, rb.components.getValue("rushing_tds"), 1e-12)
        assertEquals(null, rb.components["receiving_tds"])
        assertEquals(marketViews(quotes, K.EMPIRICAL_CV.getValue("TE")).single().mean, te.components.getValue("receiving_tds"), 1e-12)
    }

    @Test
    fun `no usable quote means no blend`() {
        val quotes = listOf(PropQuote("dk", "player_receptions", "X", 5.5, 1.9, null))
        assertNull(blend(mapOf("receptions" to 5.0), quotes, "WR"))
    }
}
