package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.pbp.PlayerWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NgsValidationTest {
    private fun ngs(id: String, targets: Double, sepAvg: Double = 3.0) =
        PlayerWeek(2025, 1, "AAA", id, mutableMapOf("ngs_targets" to targets, "ngs_sep_w" to sepAvg * targets, "ngs_separation" to sepAvg))

    private fun pbp(id: String, targets: Double) = PlayerWeek(2025, 1, "AAA", id, mutableMapOf("targets" to targets))

    @Test
    fun `counts that match play-by-play give no warning`() {
        val warnings = mutableListOf<String>()
        checkNgs(2025, (1..25).map { ngs("P$it", 8.0) }, (1..25).map { pbp("P$it", 8.0) }, warnings)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `counts that mostly disagree with play-by-play warn once`() {
        val warnings = mutableListOf<String>()
        checkNgs(2025, (1..25).map { ngs("P$it", 8.0) }, (1..25).map { pbp("P$it", 4.0) }, warnings)
        assertEquals(1, warnings.size)
        assertTrue("differ from play-by-play" in warnings.single(), "$warnings")
    }

    @Test
    fun `a few matched rows are too few to judge`() {
        val warnings = mutableListOf<String>()
        checkNgs(2025, listOf(ngs("P1", 10.0)), listOf(pbp("P1", 1.0)), warnings)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `an impossible separation is removed from its row and nothing else is`() {
        val row = ngs("P1", 10.0, sepAvg = -2.0)
        val warnings = mutableListOf<String>()
        checkNgs(2025, listOf(row), emptyList(), warnings)
        assertEquals(null, row.values["ngs_sep_w"])
        assertEquals(null, row.values["ngs_separation"])
        assertEquals(10.0, row.values["ngs_targets"])
        assertTrue(warnings.single().contains("dropped"), "$warnings")
    }

    @Test
    fun `a huge YAC over expected on one catch is real and stays`() {
        // An 80-yard screen on a play the model expected to gain 5 after the catch.
        val row = PlayerWeek(2025, 1, "AAA", "P1", mutableMapOf("ngs_receptions" to 1.0, "ngs_yacoe_w" to 75.0))
        val warnings = mutableListOf<String>()
        checkNgs(2025, listOf(row), emptyList(), warnings)
        assertEquals(75.0, row.values["ngs_yacoe_w"])
        assertEquals(emptyList<String>(), warnings)
    }
}
