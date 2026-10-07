package dev.gridiron.core.forecast

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReturnCurveTest {
    private fun game(id: String, week: Int) = PlayerGame(id, 2024, week, "KC", emptyMap())

    @Test
    fun `each listing after a game played counts once he is back, and too few listings give no curve`() {
        val weeks = mapOf(("KC" to 2024) to (1..10).toList())
        // Listed week 3 after playing week 2: back for week 5. Another back at once. A third never listed after a game.
        val history = mapOf(
            "a" to listOf(game("a", 1), game("a", 2), game("a", 5), game("a", 6)),
            "b" to listOf(game("b", 2), game("b", 3)),
            "c" to listOf(game("c", 1)),
        )
        val absent = setOf(Triple("a", 2024, 3), Triple("b", 2024, 3), Triple("c", 2024, 4))
        assertNull(returnCurve(absent, history, weeks, games = 3))

        val many = (1..15).flatMap { listOf("a$it" to history.getValue("a"), "b$it" to history.getValue("b")) }
            .associate { (id, games) -> id to games.map { PlayerGame(id, 2024, it.week, "KC", emptyMap()) } }
        val listed = many.keys.mapTo(HashSet()) { Triple(it, 2024, 3) }
        // Weeks 3, 4, 5: half back at once, all by week 5.
        assertArrayEquals(doubleArrayOf(0.5, 0.5, 1.0), returnCurve(listed, many, weeks, games = 3), 1e-9)
        // A window running past the played weeks isn't counted.
        assertNull(returnCurve(listed, many, mapOf(("KC" to 2024) to (1..4).toList()), games = 3))
    }
}
