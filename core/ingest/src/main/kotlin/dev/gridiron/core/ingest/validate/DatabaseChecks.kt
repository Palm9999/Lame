package dev.gridiron.core.ingest.validate

import androidx.sqlite.SQLiteConnection
import java.util.Locale

internal data class RangeCheck(val metricId: String, val lo: Double, val hi: Double, val note: String = "")

/** Expected ranges encode real football. Air yards share deliberately permits values outside [0, 1]. */
internal val RANGE_CHECKS: List<RangeCheck> = listOf(
    RangeCheck("target_share", 0.0, 1.0),
    RangeCheck("carry_share", 0.0, 1.0),
    RangeCheck("catch_rate", 0.0, 1.0),
    RangeCheck("snap_share", 0.0, 1.0),
    RangeCheck("rush_success_rate", 0.0, 1.0),
    RangeCheck("wopr", 0.0, 2.2, "clamped shares, so bounded by 1.5 + 0.7"),
    RangeCheck("air_yards_share", -3.0, 3.0, "screens produce negative air yards"),
    RangeCheck("adot", -20.0, 65.0, "one-target weeks can equal a single screen"),
    RangeCheck("cpoe", -100.0, 100.0),
    RangeCheck("receptions", 0.0, 30.0),
    RangeCheck("targets", 0.0, 35.0),
    RangeCheck("carries", 0.0, 50.0),
    RangeCheck("carries_eff", 0.0, 50.0),
    RangeCheck("passing_yards", -50.0, 800.0),
    RangeCheck("receiving_yards", -50.0, 400.0),
    RangeCheck("rushing_yards", -50.0, 400.0),
    RangeCheck("fumbles_lost", 0.0, 6.0),
    RangeCheck("passing_first_downs", 0.0, 40.0),
    RangeCheck("rushing_first_downs", 0.0, 30.0),
    RangeCheck("receiving_first_downs", 0.0, 20.0),
    RangeCheck("passing_2pt", 0.0, 4.0),
    RangeCheck("rushing_2pt", 0.0, 3.0),
    RangeCheck("receiving_2pt", 0.0, 3.0),
)

/** A per player-week relationship between two stored metrics; [predicate] is SQL over `a` and `b`. */
internal data class CoherenceCheck(val name: String, val a: String, val b: String, val predicate: String)

internal val COHERENCE_CHECKS: List<CoherenceCheck> = listOf(
    CoherenceCheck("targets within team targets", "targets", "team_targets", "a <= b"),
    CoherenceCheck("carries within team carries", "carries", "team_carries", "a <= b"),
    CoherenceCheck("efficiency carries within carries", "carries_eff", "carries", "a <= b"),
    CoherenceCheck("efficiency carries within team carries", "carries_eff", "team_carries", "a <= b"),
    CoherenceCheck("snaps within team snaps", "offense_snaps", "team_offense_snaps", "a <= b"),
    CoherenceCheck("cpoe attempts within attempts", "cpoe_n", "attempts", "a <= b"),
    // Solved team snaps agree with the published percentages to within one 0.01 rounding step.
    CoherenceCheck(
        "snap share matches its components", "offense_snaps", "team_offense_snaps",
        "b = 0 OR ABS(a / b - (SELECT value FROM player_week_stat x " +
            "WHERE x.player_id = pw.player_id AND x.season = pw.season " +
            "AND x.week = pw.week AND x.metric_id = 'snap_share')) <= 0.011",
    ),
    CoherenceCheck("50+ passing TDs within 40+", "passing_tds_50", "passing_tds_40", "a <= b"),
    CoherenceCheck("40+ passing TDs within passing TDs", "passing_tds_40", "passing_tds", "a <= b"),
    CoherenceCheck("50+ rushing TDs within 40+", "rushing_tds_50", "rushing_tds_40", "a <= b"),
    CoherenceCheck("40+ rushing TDs within rushing TDs", "rushing_tds_40", "rushing_tds", "a <= b"),
    CoherenceCheck("50+ receiving TDs within 40+", "receiving_tds_50", "receiving_tds_40", "a <= b"),
    CoherenceCheck("40+ receiving TDs within receiving TDs", "receiving_tds_40", "receiving_tds", "a <= b"),
    CoherenceCheck("receiving first downs within receptions", "receiving_first_downs", "receptions", "a <= b"),
    CoherenceCheck("rushing first downs within carries", "rushing_first_downs", "carries", "a <= b"),
)

/**
 * `validate.validate`: wrong numbers still insert cleanly and still render,
 * so these fail the build loudly. Returns every failure.
 */
internal fun validateDatabase(conn: SQLiteConnection): List<String> {
    val problems = mutableListOf<String>()

    for (chk in RANGE_CHECKS) {
        conn.prepare("SELECT MIN(value), MAX(value), COUNT(*) FROM player_week_stat WHERE metric_id = ?").use { st ->
            st.bindText(1, chk.metricId)
            st.step()
            if (st.getLong(2) == 0L) return@use
            val lo = st.getDouble(0)
            val hi = st.getDouble(1)
            if (lo < chk.lo || hi > chk.hi) {
                problems += "${chk.metricId}: observed [${"%.3f".format(Locale.ROOT, lo)}, ${"%.3f".format(Locale.ROOT, hi)}] " +
                    "outside expected [${chk.lo}, ${chk.hi}]" + if (chk.note.isEmpty()) "" else " (${chk.note})"
            }
        }
    }

    for (chk in COHERENCE_CHECKS) {
        val bad = conn.count(
            """SELECT COUNT(*) FROM (
                 SELECT player_id, season, week,
                        MAX(CASE WHEN metric_id = ? THEN value END) AS a,
                        MAX(CASE WHEN metric_id = ? THEN value END) AS b
                 FROM player_week_stat
                 WHERE metric_id IN (?, ?)
                 GROUP BY player_id, season, week
               ) pw
               WHERE a IS NOT NULL AND b IS NOT NULL AND NOT (${chk.predicate})""",
            chk.a, chk.b, chk.a, chk.b,
        )
        if (bad > 0) problems += "coherence: ${chk.name} violated in $bad player-weeks"
    }

    val orphanPlayers = conn.count("SELECT COUNT(*) FROM player_week_stat s LEFT JOIN player p USING(player_id) WHERE p.player_id IS NULL")
    if (orphanPlayers > 0) problems += "$orphanPlayers facts reference unknown player ids"

    val orphanMetrics = conn.count("SELECT COUNT(*) FROM player_week_stat s LEFT JOIN metric m ON m.id = s.metric_id WHERE m.id IS NULL")
    if (orphanMetrics > 0) problems += "$orphanMetrics facts reference unregistered metrics"

    val nulls = conn.count("SELECT COUNT(*) FROM player_week_stat WHERE value IS NULL")
    if (nulls > 0) problems += "$nulls facts have a null value (should be filtered out)"

    // A week with no target share at all means the share computation silently failed.
    val emptyWeeks = conn.prepare(
        "SELECT season, week FROM player_week_stat GROUP BY season, week " +
            "HAVING SUM(CASE WHEN metric_id='target_share' THEN 1 ELSE 0 END) = 0",
    ).use { st -> buildList { while (st.step()) add("(${st.getLong(0)}, ${st.getLong(1)})") } }
    if (emptyWeeks.isNotEmpty()) problems += "weeks with no target_share rows: ${emptyWeeks.take(5)}"

    // A 50+ count with no 40+ row is invisible to the pairwise check (sparse metrics drop zeros).
    for (kind in listOf("passing", "rushing", "receiving")) {
        val orphan = conn.count(
            """SELECT COUNT(*) FROM player_week_stat a
               WHERE a.metric_id = '${kind}_tds_50' AND NOT EXISTS (
                 SELECT 1 FROM player_week_stat b
                 WHERE b.player_id = a.player_id AND b.season = a.season
                   AND b.week = a.week AND b.metric_id = '${kind}_tds_40')""",
        )
        if (orphan > 0) problems += "coherence: 50+ $kind TDs without a 40+ count in $orphan player-weeks"
    }

    val computed = conn.prepare(
        "SELECT m.id FROM metric m JOIN player_week_stat s ON s.metric_id = m.id WHERE m.computed = 1 GROUP BY m.id",
    ).use { st -> buildList { while (st.step()) add(st.getText(0)) } }
    if (computed.isNotEmpty()) problems += "computed metrics have stored facts: $computed"

    return problems
}

private fun SQLiteConnection.count(sql: String, vararg binds: String): Long = prepare(sql).use { st ->
    binds.forEachIndexed { i, b -> st.bindText(i + 1, b) }
    st.step()
    st.getLong(0)
}
