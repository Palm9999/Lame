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
        assertEquals(122, METRICS.size)
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

    private val ngsVisible = mapOf(
        "ngs_time_to_throw" to listOf("QB"), "ngs_aggressiveness" to listOf("QB"), "ngs_intended_air_yards" to listOf("QB"),
        "ngs_ryoe" to listOf("QB", "RB"), "ngs_ryoe_per_att" to listOf("QB", "RB"),
        "ngs_rush_efficiency" to listOf("QB", "RB"), "ngs_stacked_box_pct" to listOf("QB", "RB"),
        "ngs_separation" to listOf("RB", "WR", "TE"), "ngs_cushion" to listOf("RB", "WR", "TE"),
        "ngs_yac_over_expected" to listOf("RB", "WR", "TE"),
    )
    private val ngsInternal = listOf(
        "ngs_attempts", "ngs_carries", "ngs_targets", "ngs_receptions",
        "ngs_ttt_w", "ngs_aggr_w", "ngs_iay_w", "ngs_eff_w", "ngs_box_w", "ngs_sep_w", "ngs_cush_w", "ngs_yacoe_w",
    )

    @Test
    fun `the ten NGS metrics are visible tier B sparse and position-scoped`() {
        for ((id, positions) in ngsVisible) {
            val m = byId.getValue(id)
            assertFalse(m.isInternal, id)
            assertFalse(m.computed, id)
            assertEquals("B", m.tier, id)
            assertEquals("ngs", m.group, id)
            assertEquals(positions.sorted(), m.positions.sorted(), id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
        }
        assertFalse(byId.getValue("ngs_rush_efficiency").higherIsBetter)
    }

    @Test
    fun `the twelve NGS components are internal and sparse`() {
        for (id in ngsInternal) {
            val m = byId.getValue(id)
            assertTrue(m.isInternal, id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
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

    @Test
    fun `every projected stat has a distribution family the simulation knows`() {
        val projected = listOf(
            "attempts", "completions", "passing_yards", "passing_tds", "passing_tds_40", "passing_tds_50",
            "interceptions", "sacks_taken", "passing_first_downs", "passing_2pt",
            "carries", "rushing_yards", "rushing_tds", "rushing_tds_40", "rushing_tds_50", "rushing_first_downs", "rushing_2pt",
            "targets", "receptions", "receiving_yards", "receiving_tds", "receiving_tds_40", "receiving_tds_50",
            "receiving_first_downs", "receiving_2pt", "fumbles_lost",
            "fg_att", "fg_made", "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_made_50",
            "fg_missed", "xp_att", "xp_made", "xp_missed",
            "dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties", "points_allowed", "yards_allowed",
        )
        for (id in projected) {
            assertTrue(byId.getValue(id).distFamily in setOf("negbinom", "binomial", "gamma", "poisson", "normal"), id)
        }
        assertEquals("negbinom", byId.getValue("targets").distFamily)
        assertEquals("binomial", byId.getValue("receptions").distFamily)
        assertEquals("gamma", byId.getValue("receiving_yards").distFamily)
        assertEquals("poisson", byId.getValue("receiving_tds").distFamily)
        assertEquals(null, byId.getValue("target_share").distFamily)
        assertEquals(projected.toSet(), DIST_FAMILIES.keys)
    }

    @Test
    fun `kicking metrics are sparse, kicker-only counts, and only the Grid's are visible`() {
        val visible = listOf("fg_made", "fg_att", "fg_made_50", "xp_made", "xp_att")
        val kicking = visible + listOf(
            "fg_att_0_39", "fg_att_40_49", "fg_att_50", "fg_made_0_39", "fg_made_40_49", "fg_missed", "xp_missed",
        )
        for (id in kicking) {
            val m = byId.getValue(id)
            assertEquals(id !in visible, m.isInternal, id)
            assertTrue(id in SPARSE_METRIC_IDS, id)
            assertEquals(listOf("K"), m.positions, id)
            assertEquals("kicking", m.group, id)
            assertEquals("poisson", m.distFamily, id)
        }
    }

    @Test
    fun `team defense metrics are visible, and only points and yards allowed keep their zeros`() {
        val defense = listOf("dst_sacks", "dst_interceptions", "dst_fumble_recoveries", "dst_tds", "dst_safeties")
        val allowed = listOf("points_allowed", "yards_allowed")
        for (id in defense + allowed) {
            val m = byId.getValue(id)
            assertFalse(m.isInternal, id)
            assertEquals(listOf("DST"), m.positions, id)
            assertEquals("defense", m.group, id)
            assertEquals(id !in allowed, id in SPARSE_METRIC_IDS, id)
        }
        assertEquals("negbinom", byId.getValue("dst_sacks").distFamily)
        assertEquals("poisson", byId.getValue("dst_tds").distFamily)
        for (id in allowed) {
            assertEquals("normal", byId.getValue(id).distFamily, id)
            assertFalse(byId.getValue(id).higherIsBetter, id)
        }
        assertEquals("YA", byId.getValue("yards_allowed").abbr)
    }
}
