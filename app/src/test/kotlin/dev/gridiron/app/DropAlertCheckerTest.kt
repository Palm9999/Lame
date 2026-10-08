package dev.gridiron.app

import dev.gridiron.core.data.live.ActivityItem
import dev.gridiron.core.data.live.ActivityKind
import dev.gridiron.core.data.live.ActivityMove
import dev.gridiron.core.projections.LineupCandidate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DropAlertCheckerTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val slots = mapOf("WR" to 1)
    private val roster = listOf(LineupCandidate("mine", "WR", 40.0))
    private val ros = mapOf(
        "star" to LineupCandidate("star", "WR", 90.0),
        "meh" to LineupCandidate("meh", "WR", 42.0),
    )

    private fun drop(id: String, player: String, from: Int) =
        ActivityItem(id, 6, ActivityKind.entries.first(), from, null, 1L, listOf(ActivityMove("e$player", player, player.uppercase(), from, 0)))

    @Test
    fun aNewDropThatLiftsTheRosterAlertsOnceAndTheFirstCheckOnlyRemembers() {
        val checker = DropAlertChecker(dir.root.resolve("drops.txt"))
        val old = drop("1", "star", 3)
        assertEquals(emptyList<DropAlert>(), checker.check(listOf(old), myTeamId = 1, rostered = emptySet(), slots, roster, ros))

        val items = listOf(old, drop("2", "star", 4), drop("3", "meh", 5), drop("4", "star", 1))
        val alerts = checker.check(items, myTeamId = 1, rostered = emptySet(), slots, roster, ros)
        // The star's new drop alerts (+50); the small gain doesn't, nor does the user's own drop or the old one.
        assertEquals(listOf("star" to 50.0), alerts.map { it.pickup.add.playerId to it.pickup.gain })
        assertEquals(emptyList<DropAlert>(), checker.check(items, myTeamId = 1, rostered = emptySet(), slots, roster, ros))
    }

    @Test
    fun aDroppedPlayerAlreadyPickedUpAgainIsLeftOut() {
        val checker = DropAlertChecker(dir.root.resolve("drops.txt"))
        checker.check(emptyList(), 1, emptySet(), slots, roster, ros)
        assertEquals(emptyList<DropAlert>(), checker.check(listOf(drop("2", "star", 4)), 1, rostered = setOf("star"), slots, roster, ros))
    }
}
