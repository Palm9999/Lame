package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.carry
import dev.gridiron.core.ingest.pbp.row
import dev.gridiron.core.ingest.pbp.target
import dev.gridiron.core.ingest.pbp.weeklyPlayerStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SnapsTest {
    private fun snap(pfr: String, snaps: Double, pct: Double, team: String = "AAA", game: String = "g1") =
        SnapRow(game, 2025, 1, team, pfr, snaps, pct)

    private val crosswalk = mapOf("pWR1" to listOf("WR1"), "pRB1" to listOf("RB1"))

    @Test
    fun `team snaps are solved when nobody plays every snap`() {
        // 2025 SF week 11 ran 55 plays; the most any player logged was 53.
        val snaps = listOf(53.0 to 0.96, 50.0 to 0.91, 48.0 to 0.87, 39.0 to 0.71)
            .mapIndexed { i, (s, p) -> SnapRow("g", 2025, 11, "SF", "p$i", s, p) }
        assertEquals(mapOf(("g" to "SF") to 55), teamOffenseSnaps(snaps))
    }

    @Test
    fun `the snap join does not inflate or drop rows`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0)))
        val before = weekly.size
        attachSnapShare(weekly, listOf(snap("pWR1", 50.0, 0.8), snap("pRB1", 30.0, 0.5)), crosswalk)
        assertEquals(before, weekly.size)
        assertEquals(0.8, weekly.row("WR1")["snap_share"])
        assertEquals(50.0, weekly.row("WR1")["offense_snaps"])
    }

    @Test
    fun `unmapped snaps leave the player without snap data`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0)))
        attachSnapShare(weekly, listOf(snap("unknown", 50.0, 0.8)), crosswalk)
        assertEquals(2, weekly.size) // receiver and passer rows both survive
        assertNull(weekly.row("WR1")["snap_share"])
    }

    @Test
    fun `team offensive snaps come from the player's own team that game`() {
        val weekly = weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0)))
        val snaps = listOf(
            snap("pWR1", 50.0, 0.78), snap("pRB1", 30.0, 0.47), snap("pOL1", 64.0, 1.0),
            snap("pOTHER", 80.0, 1.0, team = "BBB"),
        )
        attachSnapShare(weekly, snaps, crosswalk)
        assertEquals(64.0, weekly.row("WR1")["team_offense_snaps"])
        assertEquals(64.0, weekly.row("RB1")["team_offense_snaps"])
    }

    @Test
    fun `reads the snap counts file`() {
        val csv = "game_id,pfr_game_id,season,game_type,week,player,pfr_player_id,position,team,opponent,offense_snaps,offense_pct\n" +
            "2025_01_ARI_NO,x,2025,REG,1,Kelvin Banks,BankKe01,T,NO,ARI,75,1\n"
        assertEquals(
            listOf(SnapRow("2025_01_ARI_NO", 2025, 1, "NO", "BankKe01", 75.0, 1.0)),
            readSnaps(csv.byteInputStream(), "snap_counts_2025.csv.gz"),
        )
    }
}
