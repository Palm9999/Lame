package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** One player's Rising roles signal entering the week; see `Breakout` in `:core:forecast` for how it is built. */
public data class BreakoutRow(
    val playerId: String,
    val name: String,
    val position: String,
    val team: String?,
    /** 0..100: how fast his role is growing. */
    val score: Double,
    val usageRecent: Double,
    val usageBase: Double,
    val expectedRecent: Double?,
    val expectedBase: Double?,
    val vacated: Double,
    /** Names of teammates out this week who share his room, or null. */
    val outNote: String?,
) {
    /** Why he is listed, in the words the screen shows: "targets 5.0 → 8.0 a game · expected pts 6.1 → 8.0 · Smith out". */
    public val reason: String
        get() = reasonText(this)
}

/** [rows] best score first for [week]; [message] says why there are none, if that is the case. */
public data class BreakoutResult(val rows: List<BreakoutRow>, val season: Int, val week: Int, val message: String?)

/**
 * Reads the Rising roles table the forecast wrote into `stats.db`. A database from before it, or one whose forecast
 * failed, has none: that reads as a message, never an error.
 */
public class BreakoutRepository(private val executor: QueryExecutor) {
    public suspend fun find(season: Int): BreakoutResult {
        val week = try {
            executor.query(latest(season)) { it.long(0).toInt() }.singleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BreakoutResult(emptyList(), season, 0, "Refresh stats to build Rising roles.")
        } ?: return BreakoutResult(emptyList(), season, 0, "Rising roles aren't built for $season yet.")
        val rows = try {
            executor.query(rows(season, week)) { r ->
                BreakoutRow(
                    playerId = r.text(0), name = r.text(1), position = r.text(2), team = r.textOrNull(3),
                    score = r.double(4), usageRecent = r.double(5), usageBase = r.double(6),
                    expectedRecent = r.doubleOrNull(7), expectedBase = r.doubleOrNull(8),
                    vacated = r.double(9), outNote = r.textOrNull(10),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BreakoutResult(emptyList(), season, week, "Couldn't read Rising roles.")
        }
        return BreakoutResult(rows, season, week, if (rows.isEmpty()) "No role is growing right now." else null)
    }

    private companion object {
        /** The newest week this season has; no row, not a null, when it has none. */
        fun latest(season: Int) = SqlQuery(
            "SELECT week FROM player_week_signal WHERE season = ? GROUP BY week ORDER BY week DESC LIMIT 1",
            listOf(Bind.Integer(season.toLong())),
        )

        fun rows(season: Int, week: Int) = SqlQuery(
            """SELECT s.player_id, p.full_name, p.position, p.team, s.score, s.usage_recent, s.usage_base,
                      s.xp_recent, s.xp_base, s.vacated, s.out_note
               FROM player_week_signal s JOIN player p ON p.player_id = s.player_id
               WHERE s.season = ? AND s.week = ? AND s.score > 0
               ORDER BY s.score DESC, p.full_name, s.player_id""",
            listOf(Bind.Integer(season.toLong()), Bind.Integer(week.toLong())),
        )
    }
}

private fun reasonText(r: BreakoutRow): String = buildList {
    val noun = if (r.position == "RB") "touches" else "targets"
    if (r.usageRecent > r.usageBase) add("$noun ${one(r.usageBase)} → ${one(r.usageRecent)} a game")
    val xr = r.expectedRecent
    val xb = r.expectedBase
    if (xr != null && xb != null && xr > xb) add("expected pts ${one(xb)} → ${one(xr)}")
    r.outNote?.let { add("$it out") }
}.joinToString(" · ").ifEmpty { "role holding steady" }

private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
