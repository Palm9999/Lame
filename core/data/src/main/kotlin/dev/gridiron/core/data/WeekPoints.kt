package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.doubleOrNull
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.model.WeekRange
import dev.gridiron.core.statquery.StatColumn
import dev.gridiron.core.statquery.StatQueryBuilder
import dev.gridiron.core.statquery.StatQuerySpec

/**
 * [playerIds]' fantasy points for one [week] under [scoring], best first. A player with no row that week is absent;
 * one with a row but no points has null points. An empty id set reads nothing.
 */
internal suspend fun QueryExecutor.weekPoints(
    season: Int,
    week: Int,
    playerIds: Set<String>,
    scoring: ScoringProfile,
): List<GamePlayer> {
    if (playerIds.isEmpty()) return emptyList()
    val q = StatQueryBuilder.grid(
        StatQuerySpec(
            season = season,
            weeks = WeekRange.single(week),
            columns = listOf(StatColumn.FANTASY_POINTS),
            playerIds = playerIds,
            includeUnqualified = true,
            limit = StatQuerySpec.MAX_LIMIT,
            scoring = scoring,
        ),
    )
    return query(q.query) {
        GamePlayer(it.text(0), it.text(1), it.textOrNull(2), it.doubleOrNull(q.layout.valueIndex(StatColumn.FANTASY_POINTS)))
    }
}
