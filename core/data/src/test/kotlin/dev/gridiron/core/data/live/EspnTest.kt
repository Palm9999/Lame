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
    fun `times parse with and without seconds`() {
        assertEquals(Instant.parse("2026-09-25T21:57:00Z"), parseEspnTime("2026-09-25T21:57Z"))
        assertEquals(Instant.parse("2026-09-25T23:01:03Z"), parseEspnTime("2026-09-25T23:01:03Z"))
        assertNull(parseEspnTime("yesterday"))
    }
}
