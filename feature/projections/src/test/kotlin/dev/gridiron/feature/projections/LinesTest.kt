package dev.gridiron.feature.projections

import dev.gridiron.core.data.SeasonGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinesTest {
    @Test
    fun theLineSplitsTheTotalByTheSpreadAndTheSeasonScalesScoringByWhatTheOpponentAllows() {
        val games = listOf(
            // Week 1: KC 30-20 over BUF; DEN 10-20 to LV. League average 20.
            SeasonGame(1, "KC", "BUF", 30, 20, 3.0, 48.0),
            SeasonGame(1, "LV", "DEN", 20, 10, 1.0, 40.0),
            // Week 2: KC hosts DEN, KC by 7 with a total of 47.
            SeasonGame(2, "KC", "DEN", null, null, 7.0, 47.0),
        )
        val row = lineRows(games, 2).single()
        assertEquals(27.0, row.vegasHome!!, 1e-9)
        assertEquals(20.0, row.vegasAway!!, 1e-9)
        // KC scores 30 against a DEN that allows 20 (league 20): 30. DEN scores 10 against a KC that allows 20: 10.
        assertEquals(30.0, row.formHome!!, 1e-9)
        assertEquals(10.0, row.formAway!!, 1e-9)
        assertEquals("Season leans under by 7.0 · leans KC by 13.0", leanText(row))
    }

    @Test
    fun aTeamWithNoGamesYetHasNoSeasonNumber() {
        val row = lineRows(listOf(SeasonGame(1, "KC", "BUF", null, null, 3.0, 48.0)), 1).single()
        assertNull(row.formHome)
        assertNull(leanText(row))
    }
}
