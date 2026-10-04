package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EspnProjectionsTest {
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/espn/projections_2025.json"))

    @Test
    fun `weekly projections come out in our metric ids, ESPN's actual stats left out`() {
        val rows = fixture().use { readEspnProjections(it, 2025) }

        assertEquals(setOf("3918298" to 1, "3918298" to 2, "4362628" to 1, "4362628" to 2), rows.map { it.espnId to it.week }.toSet())
        val allen = rows.single { it.espnId == "3918298" && it.week == 1 }.stats
        assertEquals(237.8772664, allen.getValue("passing_yards"), 1e-9)
        assertEquals(1.505542455, allen.getValue("passing_tds"), 1e-9)
        // Ours count sacks as attempts; ESPN's don't.
        assertEquals(32.60893388 + 1.789413169, allen.getValue("attempts"), 1e-9)
        val chase = rows.single { it.espnId == "4362628" && it.week == 1 }.stats
        assertEquals(87.7871816, chase.getValue("receiving_yards"), 1e-9)
        assertEquals(6.61536426, chase.getValue("receptions"), 1e-9)
        assertEquals(0.0, chase.getValue("attempts"), 0.0) // a stat ESPN leaves out is zero
    }

    @Test
    fun `another season's rows are not this season's`() {
        assertEquals(emptyList<EspnProjection>(), fixture().use { readEspnProjections(it, 2024) })
    }

    @Test
    fun `a response that isn't ESPN's shape says so`() {
        assertThrows<EspnFormatException> { readEspnProjections("<html>".byteInputStream(), 2025) }
        assertThrows<EspnFormatException> { readEspnProjections("""{"players": 3}""".byteInputStream(), 2025) }
        assertThrows<EspnFormatException> { readEspnProjections("""{"players": [{"id": 1}]}""".byteInputStream(), 2025) }
    }
}
