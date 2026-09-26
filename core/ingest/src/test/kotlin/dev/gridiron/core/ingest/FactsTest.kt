package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.pbp.carry
import dev.gridiron.core.ingest.pbp.kneel
import dev.gridiron.core.ingest.pbp.target
import dev.gridiron.core.ingest.pbp.weeklyPlayerStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FactsTest {
    private fun value(facts: List<Fact>, pid: String, metric: String): Double? =
        facts.singleOrNull { it.playerId == pid && it.metricId == metric }?.value

    @Test
    fun `nulls are dropped and values kept`() {
        val facts = toFacts(weeklyPlayerStats(listOf(carry("RB1", 5.0))), listOf("carries", "adot"), emptySet())
        assertTrue(facts.any { it.metricId == "carries" })
        assertFalse(facts.any { it.metricId == "adot" })
    }

    @Test
    fun `sparse metrics drop zeros, others keep them`() {
        val facts = toFacts(weeklyPlayerStats(listOf(target("WR1", 10.0), carry("RB1", 3.0))), listOf("targets", "fumbles_lost"), setOf("fumbles_lost"))
        assertTrue(facts.none { it.metricId == "fumbles_lost" })
        assertEquals(0.0, value(facts, "RB1", "targets"))
    }

    @Test
    fun `carries_eff is stored and excludes kneels`() {
        val facts = toFacts(weeklyPlayerStats(listOf(carry("RB1", 5.0), kneel("QB1", -2.0))))
        assertEquals(1.0, value(facts, "QB1", "carries"))
        assertEquals(0.0, value(facts, "QB1", "carries_eff"))
        assertEquals(1.0, value(facts, "RB1", "carries_eff"))
    }

    @Test
    fun `the default metric list stores only registered metrics`() {
        val facts = toFacts(weeklyPlayerStats(listOf(target("WR1", 10.0))))
        val registered = METRICS.map { it.id }.toSet()
        assertTrue(facts.all { it.metricId in registered })
        assertNull(value(facts, "WR1", "team_plays"))
        assertEquals("AAA", facts.first().team)
    }
}
