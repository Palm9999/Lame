package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class EspnTest {
    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/espn/$name")) { name }.readText()

    @Test
    fun `news articles keep their tagged athletes, and untagged articles still appear`() {
        val news = EspnParser.news(recorded("news.json"))

        assertEquals(listOf("50029598", "50029379", "50028554"), news.map { it.id })
        val barkley = news[0]
        assertEquals("Eagles' Saquon Barkley to be 'fearless' vs. Bears despite stinger", barkley.headline)
        assertEquals(Instant.parse("2026-09-25T23:01:03Z"), barkley.published)
        assertEquals("https://www.espn.com/nfl/story/_/id/50029598/eagles-saquon-barkley-fearless-vs-bears-stinger", barkley.url)
        assertEquals(listOf(TaggedAthlete("3929630", "Saquon Barkley")), barkley.athletes)
        assertEquals(listOf("3912547", "4046675", "4247808"), news[1].athletes.map { it.espnId })
        assertEquals(emptyList<TaggedAthlete>(), news[2].athletes)
    }

    @Test
    fun `injuries carry the athlete id from the player link and times without seconds`() {
        val injuries = EspnParser.injuries(recorded("injuries.json"))

        assertEquals(5, injuries.size)
        val melton = injuries.single { it.name == "Max Melton" }
        assertEquals(
            EspnInjury(
                espnId = "4698113",
                name = "Max Melton",
                team = "ARI",
                position = "CB",
                status = "Questionable",
                abbr = "Q",
                shortComment = "Melton (toe) was a limited participant in Thursday's practice.",
                longComment = melton.longComment,
                date = Instant.parse("2026-09-25T03:06:00Z"),
            ),
            melton,
        )
        val active = injuries.single { it.name == "Hjalte Froholdt" }
        assertEquals("A", active.abbr)
        assertNull(active.shortComment)
        assertEquals(listOf("A", "Q", "O", "O", "O"), injuries.map { it.abbr })
    }

    @Test
    fun `an article without a link or an injury without an athlete id is skipped`() {
        val news = EspnParser.news(
            """{"articles": [
                 {"id": 1, "headline": "No link", "published": "2026-09-25T10:00:00Z"},
                 {"id": 2, "headline": "Fine", "published": "2026-09-25T10:00:00Z", "links": {"web": {"href": "https://x/2"}}}
               ]}""",
        )
        assertEquals(listOf("2"), news.map { it.id })

        val injuries = EspnParser.injuries(
            """{"injuries": [{"injuries": [
                 {"status": "Out", "athlete": {"displayName": "No Id", "links": []}},
                 {"status": "Out", "athlete": {"displayName": "Has Id", "links": [{"href": "https://www.espn.com/nfl/player/_/id/77/has-id"}]}}
               ]}]}""",
        )
        assertEquals(listOf("77"), injuries.map { it.espnId })
        assertEquals("O", injuries.single().abbr)
    }

    @Test
    fun `a response in another shape is a format error, not an empty list`() {
        assertThrows<LiveFormatException> { EspnParser.news("<html>maintenance</html>") }
        assertThrows<LiveFormatException> { EspnParser.news("""{"articles": 3}""") }
        assertThrows<LiveFormatException> { EspnParser.injuries("""{"teams": []}""") }
    }

    @Test
    fun `entries that all changed shape are a format error, not an empty list`() {
        // ESPN drops the fields every entry needs: nothing parses, so nothing may be wiped.
        assertThrows<LiveFormatException> { EspnParser.injuries(recorded("injuries.json").replace("\"links\"", "\"gone\"")) }
        assertThrows<LiveFormatException> { EspnParser.news(recorded("news.json").replace("\"headline\"", "\"title\"")) }
        // A genuinely empty list is still fine.
        assertEquals(emptyList<EspnInjury>(), EspnParser.injuries("""{"injuries": []}"""))
        assertEquals(emptyList<NewsArticle>(), EspnParser.news("""{"articles": []}"""))
    }

    @Test
    fun `times parse with and without seconds`() {
        assertEquals(Instant.parse("2026-09-25T21:57:00Z"), parseEspnTime("2026-09-25T21:57Z"))
        assertEquals(Instant.parse("2026-09-25T23:01:03Z"), parseEspnTime("2026-09-25T23:01:03Z"))
        assertNull(parseEspnTime("yesterday"))
    }

    @Test
    fun `a scoreboard reads each game's teams in nflverse codes, scores, state and clock`() {
        val games = EspnParser.scoreboard(recorded("scoreboard.json"))

        assertEquals(5, games.size)
        val final = games[0]
        assertEquals(EspnGame("401772938", "ARI", "SEA", Instant.parse("2025-09-26T00:15:00Z"), EspnGame.State.FINAL, "Final", 20, 23), final)
        // ESPN writes WSH; nflverse, and so the game table, writes WAS.
        assertEquals("ATL" to "WAS", games[1].home to games[1].away)
        assertEquals("Final/OT", games[2].detail)
        assertEquals(40, games[2].homeScore)
        val scheduled = games[3]
        assertEquals(EspnGame.State.SCHEDULED, scheduled.state)
        assertNull(scheduled.homeScore)
        assertEquals("10/1 - 8:15 PM EDT", scheduled.detail)
        // Built by hand from the ATL game: ESPN's in-progress shape (state "in", the quarter and clock in shortDetail).
        val live = games[4]
        assertEquals(EspnGame.State.LIVE, live.state)
        assertEquals("4:14 - 3rd", live.detail)
        assertEquals(17 to 13, live.homeScore to live.awayScore)
    }

    @Test
    fun `the scoreboard url maps nflverse's weeks to ESPN's seasons and weeks`() {
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?seasontype=2&week=4&dates=2025", EspnParser.scoreboardUrl(2025, 4))
        assertEquals("seasontype=2&week=18&dates=2025", EspnParser.scoreboardUrl(2025, 18).substringAfter('?'))
        assertEquals("seasontype=3&week=1&dates=2025", EspnParser.scoreboardUrl(2025, 19).substringAfter('?'))
        assertEquals("seasontype=3&week=3&dates=2025", EspnParser.scoreboardUrl(2025, 21).substringAfter('?'))
        // The Pro Bowl is ESPN's fourth postseason week; the Super Bowl is its fifth.
        assertEquals("seasontype=3&week=5&dates=2025", EspnParser.scoreboardUrl(2025, 22).substringAfter('?'))
    }

    @Test
    fun `a scoreboard that is not one, or whose games all fail to read, is a format error`() {
        assertThrows<LiveFormatException> { EspnParser.scoreboard("""{"events": "none"}""") }
        assertThrows<LiveFormatException> { EspnParser.scoreboard("[]") }
        assertThrows<LiveFormatException> { EspnParser.scoreboard("""{"events": [{"id": "1"}]}""") }
        assertEquals(emptyList<EspnGame>(), EspnParser.scoreboard("""{"events": []}"""))
    }
}
