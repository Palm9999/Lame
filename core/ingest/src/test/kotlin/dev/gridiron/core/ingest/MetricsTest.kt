package dev.gridiron.core.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsTest {
    private val byId = METRICS.associateBy { it.id }

    private val actual = listOf(
        "passing_first_downs", "rushing_first_downs", "receiving_first_downs",
        "passing_2pt", "rushing_2pt", "receiving_2pt", "fumbles_lost",
        "passing_tds_40", "passing_tds_50", "rushing_tds_40", "rushing_tds_50",
        "receiving_tds_40", "receiving_tds_50",
    )
    private val expected = listOf(
        "x_completions", "x_receptions", "x_passing_yards", "x_rushing_yards",
        "x_receiving_yards", "x_passing_tds", "x_rushing_tds", "x_receiving_tds",
        "x_passing_2pt", "x_rushing_2pt", "x_receiving_2pt", "x_passing_first_downs",
        "x_rushing_first_downs", "x_receiving_first_downs", "x_interceptions",
    )

    @Test
    fun `ids are unique and every Python metric is here`() {
        assertEquals(METRICS.size, byId.size)
        assertEquals(81, METRICS.size)
    }

    @Test
    fun `scoring inputs are internal, sparse and not computed`() {
        for (id in actual + expected) {
            val m = byId.getValue(id)
            assertTrue(m.isInternal, id)
            assertFalse(m.computed, id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
            assertEquals(id.uppercase(), m.abbr)
        }
    }

    @Test
    fun `fantasy columns are visible and computed`() {
        for ((id, abbr) in mapOf("fantasy_points" to "FPTS", "expected_fantasy_points" to "xFP", "fpoe" to "FPOE")) {
            val m = byId.getValue(id)
            assertTrue(m.computed && !m.isInternal, id)
            assertEquals(abbr, m.abbr)
            assertEquals("fantasy", m.group)
            assertFalse(id in SPARSE_METRIC_IDS)
        }
    }

    @Test
    fun `existing metrics keep their zeros`() {
        for (id in listOf("targets", "carries", "interceptions", "g", "team_targets", "carries_eff")) {
            assertFalse(id in SPARSE_METRIC_IDS, id)
        }
    }

    @Test
    fun `carries_eff is an internal denominator like its peers`() {
        val m = byId.getValue("carries_eff")
        assertTrue(m.isInternal)
        assertFalse(m.computed)
        assertEquals("context", m.group)
    }
}
