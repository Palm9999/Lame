package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.MissingColumnsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlayersTest {
    private fun read(csv: String) = readPlayers(csv.byteInputStream(), "players.csv.gz")

    @Test
    fun `reads current nflverse columns, folds names and keeps the first row per id`() {
        val players = read(
            "gsis_id,display_name,position,latest_team,pfr_id,espn_id\n" +
                "00-1,Zoë Émile-Smith,WR,KC,EmilZo00,4361411.0\n" +
                "00-1,Duplicate,QB,NE,,\n" +
                "00-2,,RB,NE,,\n" +
                ",No Id,RB,NE,,\n",
        )
        assertEquals(
            listOf(PlayerInfo("00-1", "Zoë Émile-Smith", "zoe emilesmith", "WR", "KC", "EmilZo00", "4361411")),
            players,
        )
    }

    @Test
    fun `falls back to older column names`() {
        val players = read("player_id,full_name,position_group,team,pfr_player_id\n00-3,Old Name,TE,GB,OldNa00\n")
        assertEquals(listOf(PlayerInfo("00-3", "Old Name", "old name", "TE", "GB", "OldNa00", null)), players)
    }

    @Test
    fun `a file without id or name columns is rejected`() {
        assertThrows<MissingColumnsException> { read("x,y\n1,2\n") }
    }
}
