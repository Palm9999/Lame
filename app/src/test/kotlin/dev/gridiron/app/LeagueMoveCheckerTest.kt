package dev.gridiron.app

import dev.gridiron.core.data.live.ActivityItem
import dev.gridiron.core.data.live.ActivityKind
import dev.gridiron.core.data.live.ActivityMove
import dev.gridiron.core.data.live.ActivityResult
import dev.gridiron.core.data.live.TradeOffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LeagueMoveCheckerTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val teams = mapOf(1 to "Rivals", 2 to "Mine", 3 to "Others")
    private val ros = mapOf("a" to 100.0, "b" to 80.0, "w1" to 30.0, "w2" to 10.0)
    private val trade = ActivityItem("t1", 5, ActivityKind.TRADE, 1, null, 1L, listOf(ActivityMove("ea", "a", "Alpha", 1, 3), ActivityMove("eb", "b", "Bravo", 3, 1)))

    private fun claim(id: String, team: Int, player: String, bid: Int) =
        ActivityItem(id, 5, ActivityKind.ADD, team, bid, 1L, listOf(ActivityMove("e$player", player, player.uppercase(), 0, team)), waiver = true)

    @Test
    fun tradesOffersAndWaiverRunsAlertOnceAfterTheFirstCheckRemembers() {
        val checker = LeagueMoveChecker(dir.root.resolve("moves.txt"))
        val old = ActivityResult(listOf(claim("c0", 1, "w2", 3)), 2, teams, null)
        assertEquals(emptyList<LeagueMoveAlert>(), checker.check(old, emptyList(), ros, emptyMap()))

        val now = old.copy(items = listOf(trade, claim("c1", 3, "w2", 20), claim("c2", 2, "w1", 7)) + old.items)
        val offers = listOf(TradeOffer("o1", "Rivals", give = listOf("b"), get = listOf("a"), fromMe = false), TradeOffer("o2", "Others", listOf("a"), listOf("b"), fromMe = true))
        val alerts = checker.check(now, offers, ros, mapOf("a" to "Alpha", "b" to "Bravo"))
        assertEquals(
            listOf(
                LeagueMoveAlert("trade:t1", "Trade in your league", "Rivals traded Alpha to Others for Bravo. Rest of season: Others +20.0, Rivals −20.0."),
                LeagueMoveAlert("offer:o1", "Trade offer from Rivals", "Alpha for your Bravo: +20.0 rest-of-season points for you. Weigh it in Trade."),
                LeagueMoveAlert("waivers:c2", "Waivers ran: 2 claims", "Mine added W1 for \$7; Others added W2 for \$20."),
            ),
            alerts,
        )
        assertEquals(emptyList<LeagueMoveAlert>(), checker.check(now, offers, ros, emptyMap()))
    }
}
