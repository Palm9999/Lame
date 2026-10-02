package dev.gridiron.core.data

import dev.gridiron.core.data.live.LiveInjury
import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.Position
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.projections.Beneficiary
import dev.gridiron.core.projections.InjuryFlag
import dev.gridiron.core.projections.Opportunities
import dev.gridiron.core.projections.ProjectionsRequest
import dev.gridiron.core.projections.UsageRow
import dev.gridiron.core.projections.projectPoints
import dev.gridiron.core.statquery.GridLayout
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** A healthy player moving up the depth chart, with this week's projection against his last games' average. */
public data class OpportunityRow(val beneficiary: Beneficiary, val projected: Double?, val recent: Double?) {
    /** Projected points above his recent average; null when either is unknown. */
    public val uptick: Double? get() = if (projected != null && recent != null) projected - recent else null
}

/** [rows] best uptick first for [week]; [message] says why there are none to show, if that is the case. */
public data class OpportunitiesResult(val rows: List<OpportunityRow>, val week: Int, val message: String?)

/**
 * Where an injury opens a role: ESPN's live injury list against each team's depth, ranked by the last four played
 * weeks' usage in `stats.db`, with the upcoming week's projections scored under the active profile.
 */
public class OpportunitiesRepository(
    private val executor: QueryExecutor,
    private val projections: ProjectionsRepository,
    private val injuries: suspend () -> List<LiveInjury>,
    private val clock: () -> Instant = Instant::now,
) {
    public suspend fun find(season: Int, scoring: ScoringProfile): OpportunitiesResult {
        val status = projections.status()
        val week = status.upcoming[season] ?: return OpportunitiesResult(emptyList(), 0, "No upcoming games in $season.")
        if (status.status != "ok") return OpportunitiesResult(emptyList(), week, "Projections unavailable: ${status.status ?: "not built yet"}.")
        if (week < 2) return OpportunitiesResult(emptyList(), week, "No games have been played yet in $season.")
        val window = WeekRange(maxOf(1, week - WINDOW), week - 1)

        val flags = try {
            injuries().mapNotNull { i -> i.playerId?.let { it to InjuryFlag(i.abbr, i.updatedAt) } }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return OpportunitiesResult(emptyList(), week, "Couldn't read ESPN's injury list.")
        }
        if (flags.isEmpty()) return OpportunitiesResult(emptyList(), week, "No injury list from ESPN yet. Refresh stats.")

        val moved = Opportunities.find(usage(season, window, scoring), flags, clock())
        val projected = projections.projections(ProjectionsRequest(moved.map { it.player.playerId }.toSet(), season, week))
            .associate { p ->
                p.playerId to moved.firstOrNull { it.player.playerId == p.playerId }?.let { b ->
                    projectPoints(p.final, scoring, Position.fromCode(b.player.position), draws = DRAWS).points
                }
            }
        val rows = moved.map { OpportunityRow(it, projected[it.player.playerId], it.player.pointsPerGame) }
            .sortedWith(compareByDescending<OpportunityRow> { it.uptick ?: Double.NEGATIVE_INFINITY }.thenBy { it.beneficiary.toRank }.thenBy { it.beneficiary.player.playerId })
        return OpportunitiesResult(rows, week, if (rows.isEmpty()) "No starter at the top of a depth chart is hurt right now." else null)
    }

    /** Every QB, RB, WR and TE with a game in [weeks], by recent usage: dropbacks, carries plus targets, or targets. */
    internal suspend fun usage(season: Int, weeks: WeekRange, scoring: ScoringProfile): List<UsageRow> =
        USAGE.flatMap { (position, columns) ->
            val q = StatQueryBuilder.grid(
                StatQuerySpec(
                    season = season,
                    weeks = weeks,
                    columns = columns + StatColumn.FANTASY_POINTS,
                    positions = setOf(position),
                    includeUnqualified = true,
                    limit = StatQuerySpec.MAX_LIMIT,
                    scoring = scoring,
                ),
            )
            executor.query(q.query) { r ->
                val games = r.long(GridLayout.GAMES).toInt()
                val points = r.doubleOrNull(q.layout.valueIndex(StatColumn.FANTASY_POINTS))
                UsageRow(
                    playerId = r.text(GridLayout.PLAYER_ID),
                    name = r.text(GridLayout.FULL_NAME),
                    position = position.code,
                    team = r.textOrNull(GridLayout.TEAM) ?: return@query null,
                    usage = columns.sumOf { r.doubleOrNull(q.layout.valueIndex(it)) ?: 0.0 },
                    games = games,
                    pointsPerGame = if (games > 0) points?.div(games) else null,
                )
            }.filterNotNull()
        }

    private companion object {
        const val WINDOW = 4
        const val DRAWS = 2_000
        val USAGE = listOf(
            Position.QB to listOf(StatColumn.DROPBACKS),
            Position.RB to listOf(StatColumn.CARRIES, StatColumn.TARGETS),
            Position.WR to listOf(StatColumn.TARGETS),
            Position.TE to listOf(StatColumn.TARGETS),
        )
    }
}
