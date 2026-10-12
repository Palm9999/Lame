package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.model.Roster
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The user's fantasy teams. A player may be on several rosters (different
 * leagues) but on each at most once.
 */
public class RosterRepository(
    private val prefs: PrefsSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    public val rosters: Flow<ImmutableList<Roster>> =
        prefs.prefs.map { it.rosters.toImmutableList() }.distinctUntilChanged()

    /** The watchlist's player ids, starred most recently last; empty before the first star. */
    public val watchlist: Flow<List<String>> =
        rosters.map { list -> list.firstOrNull { it.id == WATCHLIST_ID }?.playerIds.orEmpty() }.distinctUntilChanged()

    /**
     * Stars or unstars [playerId]. The watchlist is a roster like any other (id [WATCHLIST_ID]), so injury and news
     * alerts, the Grid's roster chip and the Rosters screen cover it; the first star creates it.
     */
    public suspend fun setWatched(playerId: String, watched: Boolean) {
        prefs.update { p ->
            val list = p.rosters.firstOrNull { it.id == WATCHLIST_ID }
            when {
                watched && list == null -> p.copy(rosters = p.rosters + Roster(WATCHLIST_ID, WATCHLIST_NAME, listOf(playerId)))
                list == null -> p
                else -> p.copy(
                    rosters = p.rosters.map { r ->
                        if (r.id != WATCHLIST_ID) r else r.copy(playerIds = if (watched) (r.playerIds - playerId) + playerId else r.playerIds - playerId)
                    },
                )
            }
        }
    }

    /** Creates an empty roster named [name] (trimmed) and returns it. */
    public suspend fun create(name: String): Roster {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a roster needs a name" }
        val roster = Roster(newId(), trimmed, emptyList())
        prefs.update { it.copy(rosters = it.rosters + roster) }
        return roster
    }

    public suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a roster needs a name" }
        edit(id) { it.copy(name = trimmed) }
    }

    public suspend fun delete(id: String) {
        prefs.update { p -> p.copy(rosters = p.rosters.filterNot { it.id == id }) }
    }

    /** Adds [playerId] to roster [id]; a no-op if he is already on it or the roster is gone. */
    public suspend fun add(id: String, playerId: String) {
        edit(id) { if (playerId in it.playerIds) it else it.copy(playerIds = it.playerIds + playerId) }
    }

    public suspend fun remove(id: String, playerId: String) {
        edit(id) { it.copy(playerIds = it.playerIds - playerId) }
    }

    public companion object {
        public const val WATCHLIST_ID: String = "watchlist"
        public const val WATCHLIST_NAME: String = "Watchlist"
    }

    private suspend fun edit(id: String, change: (Roster) -> Roster) {
        prefs.update { p -> p.copy(rosters = p.rosters.map { if (it.id == id) change(it) else it }) }
    }
}

/** A player on the user's teams in two or more leagues, and those leagues' names. */
public data class Exposure(val playerId: String, val leagues: List<String>)

/**
 * The players on two or more of the user's league teams: [leagueNames] names each league's roster by roster id (the
 * league rosters the sync keeps); every other roster is left out. Most leagues first, in the order the rosters are kept.
 */
public fun exposure(rosters: List<Roster>, leagueNames: Map<String, String>): List<Exposure> =
    rosters.filter { it.id in leagueNames }
        .flatMap { r -> r.playerIds.distinct().map { it to leagueNames.getValue(r.id) } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size >= 2 }
        .map { (id, leagues) -> Exposure(id, leagues) }
        .sortedByDescending { it.leagues.size }
