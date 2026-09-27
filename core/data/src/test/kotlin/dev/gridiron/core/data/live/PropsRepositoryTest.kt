package dev.gridiron.core.data.live

import dev.gridiron.core.forecast.ANYTIME_TD
import dev.gridiron.core.forecast.PropEvent
import dev.gridiron.core.forecast.PropQuote
import dev.gridiron.core.forecast.PropsSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant

class PropsRepositoryTest {
    @TempDir
    lateinit var dir: File

    /** A Monday: e4 (Sunday) has kicked off; e1 (Thursday night) and e2 (Sunday) are this week; e3 is next week. */
    private var now = Instant.parse("2026-09-28T12:00:00Z")
    private val responses = mutableMapOf<String, () -> HttpResponse>()
    private val urls = mutableListOf<String>()
    private val http = HttpClient { url ->
        urls += url
        responses[url]?.invoke() ?: throw IOException("GET $url failed")
    }
    private val db by lazy { LiveDb(File(dir, "live.db")) }
    private val props by lazy { PropsRepository(db, http) { now } }

    @AfterEach
    fun close() {
        db.close()
    }

    private fun recorded(name: String): String = checkNotNull(javaClass.getResource("/odds/$name")) { name }.readText()

    private fun ok(body: String, left: Int? = null) =
        HttpResponse(200, body, left?.let { mapOf("x-requests-remaining" to "$it") }.orEmpty())

    private val events = OddsApi.eventsUrl(KEY)

    private fun odds(id: String) = OddsApi.oddsUrl(KEY, id)

    /** The events list, then each odds call costing 5 credits from [left]. */
    private fun serveWeek(left: Int = 480) {
        responses[events] = { ok(recorded("events.json"), left) }
        responses[odds("e1")] = { ok(recorded("odds-e1.json"), left - 5) }
        responses[odds("e2")] = { ok(recorded("odds-e2.json"), left - 10) }
    }

    private val e2Quotes = listOf(
        PropQuote("draftkings", ANYTIME_TD, "Christian McCaffrey", null, 1.62, null),
        PropQuote("draftkings", ANYTIME_TD, "Puka Nacua", null, 2.3, null),
        PropQuote("draftkings", "player_reception_yds", "Puka Nacua", 84.5, 1.87, 1.95),
        PropQuote("fanduel", "player_reception_yds", "Puka Nacua", 85.5, 1.9, 1.9),
    )

    @Test
    fun `this week's games are fetched, not ones that kicked off or next week's`() = runTest {
        serveWeek()

        assertNull(props.refresh(KEY))

        assertEquals(listOf(events, odds("e1"), odds("e2")), urls)
        // e1 has no props posted yet, so only e2 is in the snapshot, with nflverse's team codes.
        assertEquals(PropsSnapshot(listOf(PropEvent("LA", "SF", e2Quotes))), props.snapshot())
        assertEquals(PropsStatus(creditsLeft = 470, fetchedAt = now, error = null), props.status.first())
    }

    @Test
    fun `a game fetched in the last 24 hours isn't fetched again`() = runTest {
        serveWeek()
        props.refresh(KEY)
        urls.clear()

        now = now.plus(Duration.ofHours(23))
        assertNull(props.refresh(KEY))
        // e1 had no props yet, so it's asked again; e2's are fresh.
        assertEquals(listOf(events, odds("e1")), urls)

        now = now.plus(Duration.ofHours(2))
        props.refresh(KEY)
        assertEquals(listOf(events, odds("e1"), events, odds("e1"), odds("e2")), urls)
    }

    @Test
    fun `a call that could overdraw the credits is skipped, and Settings says why`() = runTest {
        serveWeek(left = 7) // e1's answer leaves 2, less than one call's 5

        val error = props.refresh(KEY)

        assertEquals("out of Odds API credits (2 left)", error)
        assertEquals(listOf(events, odds("e1")), urls)
        // e1 had no props posted, so none have arrived yet.
        assertEquals(PropsStatus(creditsLeft = 2, fetchedAt = null, error = error), props.status.first())
    }

    @Test
    fun `a refused key says so and keeps the props already fetched`() = runTest {
        serveWeek()
        props.refresh(KEY)
        val kept = props.snapshot()
        responses[events] = { HttpResponse(401, recorded("error.json")) }
        now = now.plus(Duration.ofHours(1))

        val error = props.refresh(KEY)

        assertEquals("the Odds API refused the key (API key is not valid)", error)
        assertNotNull(kept)
        assertEquals(kept, props.snapshot())
        assertEquals(error, props.status.first().error)
    }

    @Test
    fun `no connection or an error body is a reason, never a throw, and never shows the key`() = runTest {
        val offline = props.refresh(KEY)
        assertEquals("couldn't reach the Odds API", offline)
        assertNull(props.snapshot())

        responses[events] = { HttpResponse(500, """{"message": "no such key $KEY"}""") }
        val echoed = props.refresh(KEY)!!
        assertEquals("the Odds API answered HTTP 500 (no such key …)", echoed)
        assertFalse(KEY in echoed)
        assertFalse(KEY in props.status.first().error!!)
    }

    @Test
    fun `a game's props are kept through the game and dropped 12 hours after kickoff`() = runTest {
        serveWeek()
        props.refresh(KEY)
        responses[events] = { ok("[]") }
        val kickoff = Instant.parse("2026-10-04T17:00:00Z")

        now = kickoff.plus(Duration.ofHours(11))
        props.refresh(KEY)
        assertNotNull(props.snapshot())

        now = kickoff.plus(Duration.ofHours(13))
        props.refresh(KEY)
        assertNull(props.snapshot())
    }

    @Test
    fun `a game with a team the app doesn't know is left out`() = runTest {
        responses[events] = {
            ok("""[{"id": "x1", "commence_time": "2026-10-02T00:15:00Z", "home_team": "London Monarchs", "away_team": "Kansas City Chiefs"}]""")
        }

        assertNull(props.refresh(KEY))

        assertEquals(listOf(events), urls)
    }

    @Test
    fun `the week runs to the Wednesday after its first kickoff`() {
        assertEquals(Instant.parse("2026-10-07T00:00:00Z"), nextWednesday(Instant.parse("2026-10-02T00:15:00Z")))
        // A Wednesday game (Christmas) starts a week that runs to the next Wednesday.
        assertEquals(Instant.parse("2026-12-30T00:00:00Z"), nextWednesday(Instant.parse("2026-12-23T18:00:00Z")))
    }

    @Test
    fun `a game with no props yet is asked again on the next refresh, once they're posted`() = runTest {
        serveWeek()
        props.refresh(KEY)
        responses[odds("e1")] = { ok(recorded("odds-e2.json").replace("\"e2\"", "\"e1\""), 460) }
        urls.clear()

        now = now.plus(Duration.ofHours(2))
        assertNull(props.refresh(KEY))

        assertEquals(listOf(events, odds("e1")), urls)
        assertEquals(2, props.snapshot()!!.events.size)
    }

    @Test
    fun `a game's props are dropped 12 hours after kickoff even when the Odds API refuses the key`() = runTest {
        serveWeek()
        props.refresh(KEY)
        responses[events] = { HttpResponse(401, recorded("error.json")) }

        now = Instant.parse("2026-10-04T17:00:00Z").plus(Duration.ofHours(13))
        props.refresh(KEY)

        assertNull(props.snapshot())
    }

    private companion object {
        const val KEY = "k3y-SECRET"
    }
}
