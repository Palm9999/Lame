package dev.gridiron.core.ingest.validate

import dev.gridiron.core.ingest.pbp.PlayerWeek
import kotlin.math.abs
import kotlin.math.max

/**
 * One NGS average: recovered as `sum / weight`, dropped when [impossible] (a
 * physical impossibility, so a bad row from upstream), and counted in a
 * warning when merely outside [usual].
 */
private class NgsCheck(
    val name: String,
    val sum: String,
    val weight: String,
    val impossible: (Double) -> Boolean,
    val usual: ClosedFloatingPointRange<Double>?,
)

private val NGS_CHECKS = listOf(
    NgsCheck("time to throw", "ngs_ttt_w", "ngs_attempts", { it <= 0.0 || it > 10.0 }, 1.5..4.5),
    NgsCheck("aggressiveness", "ngs_aggr_w", "ngs_attempts", { it < 0.0 || it > 100.0 }, 0.0..60.0),
    NgsCheck("intended air yards", "ngs_iay_w", "ngs_attempts", { it < -30.0 || it > 60.0 }, -5.0..25.0),
    NgsCheck("rushing efficiency", "ngs_eff_w", "ngs_carries", { it <= 0.0 }, null),
    NgsCheck("stacked box", "ngs_box_w", "ngs_carries", { it < 0.0 || it > 100.0 }, 0.0..100.0),
    NgsCheck("separation", "ngs_sep_w", "ngs_targets", { it < 0.0 || it > 20.0 }, 0.0..8.0),
    NgsCheck("cushion", "ngs_cush_w", "ngs_targets", { it < 0.0 || it > 40.0 }, 0.0..15.0),
    NgsCheck("YAC over expected", "ngs_yacoe_w", "ngs_receptions", { abs(it) > 30.0 }, null),
)

/** RYOE is stored as a total, so its per-carry range is checked separately. */
private val RYOE_PER_CARRY = -8.0..8.0

/** The share of matched player-weeks whose counts may disagree with play-by-play before a warning. */
private const val COUNT_DISAGREE_SHARE = 0.2
private const val COUNT_TOLERANCE = 0.10
private const val COUNT_MIN_MATCHED = 20

/**
 * Checks one season's NGS [rows] in place. An impossible average removes just
 * that metric's sum from its row (the build never fails on NGS); values
 * outside the usual range only count toward a warning. [weekly] is the
 * season's play-by-play, whose counts NGS's should roughly match.
 */
internal fun checkNgs(season: Int, rows: List<PlayerWeek>, weekly: List<PlayerWeek>, warnings: MutableList<String>) {
    val dropped = sortedMapOf<String, Int>()
    val unusual = sortedMapOf<String, Int>()
    for (row in rows) {
        for (check in NGS_CHECKS) {
            val sum = row.values[check.sum] ?: continue
            val weight = row.values[check.weight] ?: continue
            val average = sum / weight
            when {
                check.impossible(average) -> {
                    row.values.remove(check.sum)
                    dropped.merge(check.name, 1, Int::plus)
                }
                check.usual != null && average !in check.usual -> unusual.merge(check.name, 1, Int::plus)
            }
        }
        val ryoe = row.values["ngs_ryoe"]
        val carries = row.values["ngs_carries"]
        if (ryoe != null && carries != null && ryoe / carries !in RYOE_PER_CARRY) unusual.merge("RYOE per carry", 1, Int::plus)
    }
    if (dropped.isNotEmpty()) warnings += "$season: NGS dropped impossible values: ${dropped.entries.joinToString { "${it.key} ${it.value}" }}"
    if (unusual.isNotEmpty()) warnings += "$season: NGS values outside the usual range: ${unusual.entries.joinToString { "${it.key} ${it.value}" }}"
    crossCheckCounts(season, rows, weekly, warnings)
}

private fun crossCheckCounts(season: Int, rows: List<PlayerWeek>, weekly: List<PlayerWeek>, warnings: MutableList<String>) {
    val pbp = weekly.groupBy { it.week to it.playerId }
    var matched = 0
    var apart = 0
    for (row in rows) {
        val ours = pbp[row.week to row.playerId] ?: continue
        for ((ngs, own) in listOf("ngs_targets" to "targets", "ngs_carries" to "carries", "ngs_attempts" to "attempts")) {
            val n = row.values[ngs] ?: continue
            val p = ours.sumOf { it.values[own] ?: 0.0 }
            matched++
            if (abs(n - p) > COUNT_TOLERANCE * max(n, p)) apart++
        }
    }
    if (matched >= COUNT_MIN_MATCHED && apart > COUNT_DISAGREE_SHARE * matched) {
        warnings += "$season: NGS counts differ from play-by-play by more than 10% in $apart of $matched player-week counts"
    }
}
