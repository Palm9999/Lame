package dev.gridiron.core.data.live

import dev.gridiron.core.forecast.ANYTIME_TD
import dev.gridiron.core.forecast.PropQuote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.Instant

class OddsTest {
    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/odds/$name")) { name }.readText()

    @Test
    fun `the event list gives each game's id, kickoff and teams`() {
        val events = OddsApi.events(recorded("events.json"))

        assertEquals(listOf("e4", "e1", "e2", "e3"), events.map { it.id })
        assertEquals(OddsEvent("e1", Instant.parse("2026-10-02T00:15:00Z"), "Kansas City Chiefs", "Los Angeles Chargers"), events[1])
    }

    @Test
    fun `an event's odds become one quote per book, market, player and line`() {
        assertEquals(
            listOf(
                PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
                PropQuote("draftkings", ANYTIME_TD, "Puka Nacua", null, 2.3, null),
                PropQuote("draftkings", ANYTIME_TD, "Christian McCaffrey", null, 1.62, null),
                PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
            ),
            OddsApi.quotes(recorded("odds-e2.json")),
        )
    }

    @Test
    fun `a game with no props posted yet has no quotes`() {
        assertTrue(OddsApi.quotes(recorded("odds-e1.json")).isEmpty())
    }

    @Test
    fun `a response that isn't the expected shape is a format error`() {
        assertThrows<LiveFormatException> { OddsApi.events("{}") }
        assertThrows<LiveFormatException> { OddsApi.events("not json") }
        assertThrows<LiveFormatException> { OddsApi.events("""[{"id": 1}]""") }
        assertThrows<LiveFormatException> { OddsApi.quotes("[]") }
    }

    @Test
    fun `an error body's message is read, and anything else has none`() {
        assertEquals("API key is not valid", OddsApi.errorMessage(recorded("error.json")))
        assertNull(OddsApi.errorMessage("<html>Bad gateway</html>"))
        assertNull(OddsApi.errorMessage("[]"))
    }

    @Test
    fun `every URL goes to the Odds API, asks for the five markets, and encodes the key`() {
        assertEquals(
            "https://api.the-odds-api.com/v4/sports/americanfootball_nfl/events/e1/odds?apiKey=k+y%26z&regions=us" +
                "&markets=player_receptions,player_reception_yds,player_rush_yds,player_pass_yds,player_anytime_td" +
                "&oddsFormat=decimal&dateFormat=iso",
            OddsApi.oddsUrl("k y&z", "e1"),
        )
        assertEquals("api.the-odds-api.com", URI(OddsApi.eventsUrl("k")).host)
        assertEquals(5, OddsApi.ODDS_CALL_COST)
    }

    @Test
    fun `every team maps to its own nflverse abbreviation`() {
        assertEquals(32, NFL_TEAMS.size)
        assertEquals(32, NFL_TEAMS.values.toSet().size)
        assertEquals("LA", NFL_TEAMS["Los Angeles Rams"])
        assertEquals("LAC", NFL_TEAMS["Los Angeles Chargers"])
        assertEquals("WAS", NFL_TEAMS["Washington Commanders"])
    }
}
