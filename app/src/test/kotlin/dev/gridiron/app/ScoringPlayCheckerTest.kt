package dev.gridiron.app

import dev.gridiron.core.data.live.MatchupPlayer
import dev.gridiron.core.data.live.MatchupSide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScoringPlayCheckerTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun side(vararg players: Pair<String, Double?>, bench: String? = null) = MatchupSide(
        1, 0.0,
        players.map { (id, pts) -> MatchupPlayer(id, id.uppercase(), if (id == bench) "BE" else "WR", pts) },
    )

    @Test
    fun aStartersJumpOfFiveOrMoreAlertsOnceAndTheWeeksFirstCheckOnlyRemembers() {
        val checker = ScoringPlayChecker(dir.root.resolve("plays.txt"))
        assertEquals(emptyList<ScoringPlay>(), checker.check("2026-6", side("a" to 2.0, "b" to 1.0)))
        assertEquals(listOf(ScoringPlay("a", "A", 6.5, 8.5)), checker.check("2026-6", side("a" to 8.5, "b" to 4.0)))
        assertEquals(emptyList<ScoringPlay>(), checker.check("2026-6", side("a" to 8.5, "b" to 4.0)))
    }

    @Test
    fun aNewWeekABenchPlayerAndAPlayerNewToTheLineupDontAlert() {
        val checker = ScoringPlayChecker(dir.root.resolve("plays.txt"))
        checker.check("2026-6", side("a" to 0.0, "c" to 0.0))
        assertEquals(emptyList<ScoringPlay>(), checker.check("2026-7", side("a" to 20.0)))
        assertEquals(emptyList<ScoringPlay>(), checker.check("2026-7", side("a" to 20.0, "c" to 30.0, "n" to 12.0, bench = "c")))
    }
}
