package dev.gridiron.core.data.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class NewsAlertsTest {
    private val t0 = Instant.parse("2026-10-05T12:00:00Z")
    private fun item(id: String, minutes: Long, vararg players: Pair<String, String?>) =
        NewsItem(id, t0.plusSeconds(minutes * 60), "Headline $id", null, "https://espn.com/$id", players.map { NewsPlayer(it.first, it.second) })

    @Test
    fun `only stories after the last check about a rostered player, newest first, capped`() {
        val news = listOf(
            item("old", -5, "Pat" to "p1"),
            item("a", 5, "Pat" to "p1", "Other" to "x"),
            item("b", 10, "Nobody" to "x"),
            item("c", 15, "Sam" to "p2", "Pat" to "p1"),
            item("d", 20, "Unmatched" to null),
        )
        val alerts = NewsAlerts.fresh(news, setOf("p1", "p2"), t0)
        assertEquals(listOf("c", "a"), alerts.map { it.item.id })
        assertEquals("Sam, Pat", alerts.first().title)
        assertEquals("Headline c", alerts.first().text)
        assertEquals(1, NewsAlerts.fresh(news, setOf("p1", "p2"), t0, limit = 1).size)
    }
}
