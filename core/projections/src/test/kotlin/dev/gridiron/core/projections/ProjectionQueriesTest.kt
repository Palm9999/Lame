package dev.gridiron.core.projections

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectionQueriesTest {
    @Test
    fun `weekly query selects from player_week_projection filtered by player ids`() {
        val query = ProjectionQueries.weekly(setOf("P1", "P2"), season = 2026, week = 3)
        assertTrue(query.sql.contains("player_week_projection"))
        assertTrue(query.sql.contains("season"))
        assertTrue(query.sql.contains("week"))
    }

    @Test
    fun `weekly query with no player ids still produces valid, non-crashing SQL`() {
        // An empty selection (e.g. a page with zero rows) must not build SQL
        // with an empty IN () clause, which is invalid in SQLite.
        val query = ProjectionQueries.weekly(emptySet(), season = 2026, week = 3)
        assertTrue(query.sql.contains("0 = 1") || query.sql.contains("FALSE"))
    }
}
