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

    private suspend fun edit(id: String, change: (Roster) -> Roster) {
        prefs.update { p -> p.copy(rosters = p.rosters.map { if (it.id == id) change(it) else it }) }
    }
}
