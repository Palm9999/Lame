package dev.gridiron.core.data

import dev.gridiron.core.database.QueryExecutor
import dev.gridiron.core.database.textOrNull
import dev.gridiron.core.statquery.Bind
import dev.gridiron.core.statquery.SqlQuery
import kotlinx.coroutines.CancellationException

/**
 * Players by id across both lists `stats.db` carries: `player` (players with
 * stats) and `player_xref` (everyone nflverse lists with an ESPN id).
 *
 * Every lookup is best effort. With no stats database yet, or an older one
 * without `player_xref`, it finds nothing rather than failing: live news and
 * injuries still show, just unlinked.
 */
public class PlayerDirectory(private val executor: QueryExecutor) {

    /** App player ids for [espnIds], keyed by ESPN id; ids with no match are left out. */
    public suspend fun playerIds(espnIds: Collection<String>): Map<String, String> {
        val ids = espnIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return bestEffort(emptyMap()) {
            ids.chunked(CHUNK).flatMap { chunk ->
                executor.query(
                    SqlQuery(
                        "SELECT espn_id, player_id FROM player_xref WHERE espn_id IN (${chunk.joinToString(", ") { "?" }})",
                        chunk.map { Bind.Text(it) },
                    ),
                ) { it.text(0) to it.text(1) }
            }.toMap()
        }
    }

    /** Name, position and team for [playerId]: from `player` if they have stats, else from `player_xref`. */
    public suspend fun header(playerId: String): PlayerHeader? =
        bestEffort(null) { executor.playerHeaders(listOf(playerId))[playerId] }
            ?: bestEffort(null) {
                executor.query(
                    SqlQuery(
                        "SELECT player_id, full_name, position, team FROM player_xref WHERE player_id = ? LIMIT 1",
                        listOf(Bind.Text(playerId)),
                    ),
                ) { PlayerHeader(it.text(0), it.text(1), it.textOrNull(2), it.textOrNull(3)) }.firstOrNull()
            }

    private suspend fun <T> bestEffort(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback
        }

    private companion object {
        /** Under SQLite's bind-variable limit with room to spare. */
        const val CHUNK = 500
    }
}
