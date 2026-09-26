package dev.gridiron.core.ingest.pbp

import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReadPlaysTest {
    private fun csv(columns: List<String>, values: Map<String, String>): String =
        columns.joinToString(",") + "\n" + columns.joinToString(",") { values[it] ?: "" } + "\n"

    @Test
    fun `reads a play-by-play row into a play`() {
        val values = mapOf(
            "season" to "2025", "week" to "3", "season_type" to "REG", "posteam" to "AAA",
            "play_type" to "pass", "receiver_player_id" to "00-1", "air_yards" to "12", "epa" to "-0.25",
        )
        val plays = mutableListOf<Play>()
        readPlays(csv(PBP_COLUMNS, values).byteInputStream(), "pbp.csv") { plays += it }
        val p = plays.single()
        assertEquals(2025, p.season)
        assertEquals(3, p.week)
        assertEquals("00-1", p.receiver)
        assertEquals(12.0, p.airYards)
        assertEquals(-0.25, p.epa)
        assertNull(p.cpoe)
        assertNull(p.rusher)
    }

    @Test
    fun `a play-by-play file missing a needed column is rejected by name`() {
        val e = assertThrows<MissingColumnsException> {
            readPlays(csv(PBP_COLUMNS - "epa", mapOf("season" to "2025", "week" to "1")).byteInputStream(), "pbp.csv") {}
        }
        assertEquals(listOf("epa"), e.missing)
    }
}
