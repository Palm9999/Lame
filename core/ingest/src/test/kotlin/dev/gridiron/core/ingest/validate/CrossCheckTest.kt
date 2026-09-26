package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.EXPECTED_ACTUAL_COLUMNS
import dev.gridiron.core.ingest.EXPECTED_COLUMNS
import dev.gridiron.core.ingest.ExpectedRow
import dev.gridiron.core.ingest.pbp.PlayerWeek
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossCheckTest {
    private fun ep(week: Int = 1, values: Map<String, Double> = emptyMap()): ExpectedRow =
        ExpectedRow("WR1", 2025, week, "AAA", (EXPECTED_COLUMNS.keys + EXPECTED_ACTUAL_COLUMNS).associateWith { 0.0 } + values)

    private fun weekly(week: Int = 1, values: Map<String, Double> = emptyMap()): PlayerWeek {
        val zeros = listOf(
            "completions", "receptions", "passing_yards", "receiving_yards", "rushing_yards",
            "passing_tds", "receiving_tds", "rushing_tds", "passing_2pt", "receiving_2pt",
            "rushing_2pt", "passing_first_downs", "receiving_first_downs", "rushing_first_downs",
            "interceptions", "fumbles_lost",
        ).associateWith { 0.0 }
        return PlayerWeek(2025, week, "AAA", "WR1", (zeros + values).toMutableMap())
    }

    @Test
    fun `agreeing sources pass silently`() {
        val warnings = mutableListOf<String>()
        val problems = crossCheck(
            listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0))),
            listOf(ep(values = mapOf("receptions" to 5.0, "rec_yards_gained" to 61.0))),
            warnings,
        )
        assertEquals(emptyList<String>(), problems)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a single mismatch is a warning, not a failure`() {
        val warnings = mutableListOf<String>()
        val problems = crossCheck(
            listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0))),
            listOf(ep(values = mapOf("receptions" to 5.0, "rec_yards_gained" to 75.0))),
            warnings,
        )
        assertEquals(emptyList<String>(), problems)
        assertTrue(warnings.any { "receiving_yards" in it && "WR1" in it }, "$warnings")
    }

    @Test
    fun `our extra sack fumbles are allowed`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), crossCheck(listOf(weekly(values = mapOf("fumbles_lost" to 2.0))), listOf(ep(values = mapOf("rush_fumble_lost" to 1.0))), warnings))
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `trailing ffopportunity's fumbles by one is reported`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), crossCheck(listOf(weekly()), listOf(ep(values = mapOf("rec_fumble_lost" to 1.0))), warnings))
        assertTrue(warnings.any { "fumbles_lost" in it })
    }

    @Test
    fun `a systematic fumble regression fails the season budget`() {
        val n = FUMBLE_POLICY.seasonBudget + 5
        val problems = crossCheck(
            (1..n).map { weekly(it) },
            (1..n).map { ep(it, mapOf("rec_fumble_lost" to 1.0)) },
            mutableListOf(),
        )
        assertTrue(problems.any { "fumbles_lost" in it && "budget" in it }, "$problems")
    }

    @Test
    fun `the fantasy contract matches the reference profile`() {
        // 5 rec, 61 yds, 1 TD, 1 fumble: 5 + 6.1 + 6 - 2 = 15.1 in the file.
        val ours = listOf(weekly(values = mapOf("receptions" to 5.0, "receiving_yards" to 61.0, "receiving_tds" to 1.0, "fumbles_lost" to 1.0)))
        val theirs = ep(values = mapOf(
            "receptions" to 5.0, "rec_yards_gained" to 61.0, "rec_touchdown" to 1.0, "rec_fumble_lost" to 1.0,
            "total_fantasy_points" to 15.1, "receptions_exp" to 4.0, "rec_yards_gained_exp" to 50.0,
            "rec_touchdown_exp" to 0.5, "total_fantasy_points_exp" to 12.0,
        ))
        assertEquals(emptyList<String>(), fantasyContract(ours, listOf(theirs), mutableListOf()))

        val off = ExpectedRow(theirs.playerId, theirs.season, theirs.week, theirs.team, theirs.values + ("total_fantasy_points" to 10.1))
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), fantasyContract(ours, listOf(off), warnings))
        assertTrue(warnings.any { "total_fantasy_points" in it })
    }

    private data class Row(val season: Int, val m: Double)

    private fun policyProblems(rows: List<Row>, policy: ComparisonPolicy, warnings: MutableList<String> = mutableListOf()) =
        applyPolicy(rows, { it.season }, { it.m }, { "m=${it.m}" }, policy, warnings)

    @Test
    fun `an outlier within budget passes and warns`() {
        val warnings = mutableListOf<String>()
        assertEquals(emptyList<String>(), policyProblems(listOf(Row(2025, 2.0)), ComparisonPolicy("test", 1.0, 10.0, 3), warnings))
        assertTrue(warnings.any { "test" in it })
    }

    @Test
    fun `budget plus one outliers in a season fails`() {
        assertTrue(policyProblems(List(4) { Row(2025, 2.0) }, ComparisonPolicy("test", 1.0, 10.0, 3)).any { "budget" in it })
    }

    @Test
    fun `the budget is per season`() {
        val rows = List(3) { Row(2024, 2.0) } + List(3) { Row(2025, 2.0) }
        assertEquals(emptyList<String>(), policyProblems(rows, ComparisonPolicy("test", 1.0, 10.0, 3)))
    }

    @Test
    fun `a single row beyond the hard cap fails`() {
        assertTrue(policyProblems(listOf(Row(2025, 6.0)), ComparisonPolicy("test", 1.0, 5.0, 10)).any { "hard cap" in it })
    }

    @Test
    fun `expected coverage is complete when every week has expected rows`() {
        assertEquals(3 to null, expectedCoverage(2025, (1..3).map { weekly(it) }, (1..3).map { ep(it) }))
    }

    @Test
    fun `expected coverage warns when ffopportunity lags the newest week`() {
        val (through, warning) = expectedCoverage(2026, (1..3).map { weekly(it) }, (1..2).map { ep(it) })
        assertEquals(2, through)
        assertTrue(warning!!.contains("2026") && warning.contains("week 3"), warning)
    }

    @Test
    fun `expected coverage stops at the first gap`() {
        val (through, warning) = expectedCoverage(2025, (1..4).map { weekly(it) }, listOf(1, 3, 4).map { ep(it) })
        assertEquals(1, through)
        assertTrue(warning!!.contains("week 2"), warning)
    }

    @Test
    fun `no expected data at all is week zero`() {
        val (through, warning) = expectedCoverage(2026, (1..2).map { weekly(it) }, null)
        assertEquals(0, through)
        assertTrue(warning!!.contains("weeks 1, 2"), warning)
    }
}
