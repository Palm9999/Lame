package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.TeamDefenseRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DstTest {
    private fun row(pointsAllowed: Double) =
        TeamDefenseRow("KC", 2025, 1, pointsAllowed, 300.0, 3.0, 1.0, 2.0, 1.0, 1.0, 1.0)

    @Test
    fun `a team-week becomes its team defense's week`() {
        val week = dstWeeks(listOf(row(17.0))).single()
        assertEquals("DST_KC", week.playerId)
        assertEquals("KC", week.team)
        assertEquals(
            mapOf(
                "g" to 1.0, "dst_sacks" to 3.0, "dst_interceptions" to 1.0, "dst_fumble_recoveries" to 2.0,
                // One defensive TD and one kickoff return TD.
                "dst_tds" to 2.0, "dst_safeties" to 1.0, "points_allowed" to 17.0,
            ),
            week.values,
        )
    }

    @Test
    fun `a shutout stores its zero points allowed`() {
        assertEquals(0.0, dstWeeks(listOf(row(0.0))).single().values["points_allowed"])
    }

    @Test
    fun `a team defense is named for its team and found by searching dst`() {
        assertEquals(PlayerInfo("DST_KC", "KC D/ST", "kc dst", "DST", "KC", null, null), dstPlayer("KC"))
    }
}
