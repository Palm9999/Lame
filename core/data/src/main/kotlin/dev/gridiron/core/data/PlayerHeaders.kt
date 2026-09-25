package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.StatQueryBuilder

/** Name, position and team for [ids], keyed by player id. Shared by every repository that shows player headers. */
internal suspend fun QueryExecutor.playerHeaders(ids: Collection<String>): Map<String, PlayerHeader> {
    val q = StatQueryBuilder.players(ids) ?: return emptyMap()
    return query(q) { r ->
        PlayerHeader(r.text(0), r.text(1), r.textOrNull(2), r.textOrNull(3))
    }.associateBy { it.playerId }
}
