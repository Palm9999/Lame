package dev.gridiron.core.ingest

import dev.gridiron.core.ingest.csv.readCsv
import dev.gridiron.core.ingest.pbp.PlayerWeek
import java.io.InputStream
import kotlin.math.abs

internal data class SnapRow(
    val gameId: String,
    val season: Int,
    val week: Int,
    val team: String,
    val pfrPlayerId: String?,
    val offenseSnaps: Double?,
    val offensePct: Double?,
)

private val SNAP_COLUMNS = listOf("game_id", "season", "week", "team", "pfr_player_id", "offense_snaps", "offense_pct")

internal fun readSnaps(input: InputStream, source: String): List<SnapRow> = buildList {
    readCsv(input, source, SNAP_COLUMNS) { row ->
        val gameId = row.text("game_id") ?: return@readCsv
        val season = row.int("season") ?: return@readCsv
        val week = row.int("week") ?: return@readCsv
        val team = row.text("team") ?: return@readCsv
        add(SnapRow(gameId, season, week, team, row.text("pfr_player_id"), row.double("offense_snaps"), row.double("offense_pct")))
    }
}

/** Candidate offsets above the observed max when solving for team snaps. */
private const val SNAP_SEARCH = 25

/** Half of the published 0.01 rounding step, plus float slack. */
private const val PCT_TOLERANCE = 0.0051

/**
 * Each team's offensive snaps in each game. Not simply the max any player
 * logged (sometimes nobody plays every snap), nor one back-solve of
 * snaps / pct (the two-decimal rounding makes that ambiguous): the integer D,
 * at or above the observed max, most consistent with every published
 * percentage. Ties go to the least squared error, then the smallest D.
 */
internal fun teamOffenseSnaps(snaps: List<SnapRow>): Map<Pair<String, String>, Int> =
    snaps.filter { (it.offenseSnaps ?: 0.0) > 0 }
        .groupBy { it.gameId to it.team }
        .mapValues { (_, played) ->
            val max = played.maxOf { it.offenseSnaps!! }.toInt()
            (max..max + SNAP_SEARCH).minWith(
                compareBy<Int>(
                    { d -> played.count { (pctError(it, d) ?: 0.0) > PCT_TOLERANCE } },
                    { d -> played.sumOf { row -> pctError(row, d)?.let { it * it } ?: 0.0 } },
                    { it },
                ),
            )
        }

private fun pctError(row: SnapRow, d: Int): Double? = row.offensePct?.let { abs(row.offenseSnaps!! / d - it) }

/**
 * Attaches snap counts to [rows] in place. Snap counts key on PFR ids, so they
 * route through [crosswalk] (PFR id to gsis ids). A row nothing maps to keeps
 * no snap data rather than being dropped.
 */
internal fun attachSnapShare(rows: List<PlayerWeek>, snaps: List<SnapRow>, crosswalk: Map<String, List<String>>) {
    val teamSnaps = teamOffenseSnaps(snaps)
    val byPlayerWeek = HashMap<Triple<Int, Int, String>, SnapRow>()
    for (s in snaps) {
        val pfr = s.pfrPlayerId ?: continue
        for (id in crosswalk[pfr].orEmpty()) byPlayerWeek[Triple(s.season, s.week, id)] = s
    }
    for (row in rows) {
        val s = byPlayerWeek[Triple(row.season, row.week, row.playerId)] ?: continue
        row.values["offense_snaps"] = s.offenseSnaps
        row.values["team_offense_snaps"] = teamSnaps[s.gameId to s.team]?.toDouble()
        row.values["snap_share"] = s.offensePct
    }
}
