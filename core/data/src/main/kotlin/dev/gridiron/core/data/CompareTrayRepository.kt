package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.model.CompareSlot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Players waiting to be compared. Slots are unique: the same player may appear
 * again with another season or range (comparing him with himself), but never
 * twice with the same one.
 */
public class CompareTrayRepository(private val prefs: PrefsSource) {

    public enum class AddResult { ADDED, ALREADY_THERE, FULL }

    public val slots: Flow<ImmutableList<CompareSlot>> =
        prefs.prefs.map { it.tray.toImmutableList() }.distinctUntilChanged()

    public suspend fun add(slot: CompareSlot): AddResult {
        var result = AddResult.ADDED
        prefs.update { p ->
            when {
                slot in p.tray -> p.also { result = AddResult.ALREADY_THERE }
                p.tray.size >= CAPACITY -> p.also { result = AddResult.FULL }
                else -> p.copy(tray = p.tray + slot)
            }
        }
        return result
    }

    public suspend fun remove(slot: CompareSlot) {
        prefs.update { it.copy(tray = it.tray - slot) }
    }

    /** Swaps [old] for [new] in place. False, and no change, if [new] is already in the tray. */
    public suspend fun replace(old: CompareSlot, new: CompareSlot): Boolean {
        var replaced = false
        prefs.update { p ->
            if (new != old && new in p.tray) {
                p
            } else {
                replaced = old in p.tray
                p.copy(tray = p.tray.map { if (it == old) new else it })
            }
        }
        return replaced
    }

    public suspend fun clear() {
        prefs.update { it.copy(tray = emptyList()) }
    }

    public companion object {
        public const val CAPACITY: Int = 4
    }
}
