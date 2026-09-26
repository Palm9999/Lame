package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExpectedTest {
    private val header = listOf("season", "posteam", "week", "game_id", "player_id") +
        EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS

    @Test
    fun `expected components are renamed, keyed and zero-filled`() {
        val rows = listOf(
            mapOf("season" to "2025", "posteam" to "AAA", "week" to "3.0", "player_id" to "WR1",
                "receptions_exp" to 5.25, "rec_yards_gained_exp" to 61.4),
            mapOf("season" to "2025", "posteam" to "AAA", "week" to "3.0", "player_id" to null, "receptions_exp" to 9.0),
        )
        val out = readExpected(Fixtures.csv(header, rows).byteInputStream(), "ep_weekly_2025.csv")
        val week = out.single().toPlayerWeek()
        assertEquals(listOf(2025, 3, "AAA", "WR1"), listOf(week.season, week.week, week.team, week.playerId))
        assertEquals(5.25, week.values["x_receptions"])
        assertEquals(61.4, week.values["x_receiving_yards"])
        assertEquals(0.0, week.values["x_passing_tds"])
        assertEquals(15, week.values.size)
    }
}
