package dev.gridiron.core.data

import dev.gridiron.core.datastore.PrefsSource
import dev.gridiron.core.datastore.RowDensity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The Grid's display settings, kept in the prefs file. Row height is not part of a view or a preset. */
public class GridDisplayRepository(private val prefs: PrefsSource) {

    public val density: Flow<RowDensity> = prefs.prefs.map { it.gridDensity }.distinctUntilChanged()

    /** Saves [density]; a failed write throws, and the caller says so. */
    public suspend fun setDensity(density: RowDensity) {
        prefs.update { it.copy(gridDensity = density) }
    }
}
