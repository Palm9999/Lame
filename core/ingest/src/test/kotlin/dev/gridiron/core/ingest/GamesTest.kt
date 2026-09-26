package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GamesTest {
    private val header = listOf(
        "game_id", "season", "game_type", "week", "gameday", "home_team", "away_team", "home_score", "away_score",
        "spread_line", "total_line", "roof", "home_qb_id", "away_qb_id", "home_coach", "away_coach",
    )

    private val csv = Fixtures.csv(
        header,
        listOf(
            mapOf(
                "game_id" to "2026_01_NE_SEA", "season" to 2026, "game_type" to "REG", "week" to 1, "gameday" to "2026-09-09",
                "home_team" to "SEA", "away_team" to "NE", "home_score" to 13, "away_score" to 10, "spread_line" to 3,
                "total_line" to 44.5, "roof" to "outdoors", "home_qb_id" to "00-0034869", "away_qb_id" to "00-0039851",
                "home_coach" to "Mike Macdonald", "away_coach" to "Mike Vrabel",
            ),
            mapOf(
                "game_id" to "2026_05_KC_BUF", "season" to 2026, "game_type" to "REG", "week" to 5,
                "home_team" to "BUF", "away_team" to "KC", "spread_line" to "NA", "home_qb_id" to "NA",
            ),
            mapOf("game_id" to "2019_01_OAK_DEN", "season" to 2019, "game_type" to "REG", "week" to 1, "home_team" to "DEN", "away_team" to "OAK"),
        ),
    )

    @Test
    fun `keeps the requested seasons with results, lines, starting QBs and coaches`() {
        val games = readGames(csv.byteInputStream(), "games.csv", setOf(2026))

        assertEquals(listOf("2026_01_NE_SEA", "2026_05_KC_BUF"), games.map { it.gameId })
        val played = games[0]
        assertEquals(2026, played.season)
        assertEquals(1, played.week)
        assertEquals("REG", played.gameType)
        assertEquals("SEA", played.homeTeam)
        assertEquals("NE", played.awayTeam)
        assertEquals(13, played.homeScore)
        assertEquals(10, played.awayScore)
        assertEquals(3.0, played.spreadLine)
        assertEquals(44.5, played.totalLine)
        assertEquals("outdoors", played.roof)
        assertEquals("00-0034869", played.homeQbId)
        assertEquals("Mike Vrabel", played.awayCoach)
    }

    @Test
    fun `an unplayed game has no scores, and NA reads as missing`() {
        val future = readGames(csv.byteInputStream(), "games.csv", setOf(2026))[1]
        assertNull(future.homeScore)
        assertNull(future.awayScore)
        assertNull(future.spreadLine)
        assertNull(future.totalLine)
        assertNull(future.homeQbId)
    }
}
