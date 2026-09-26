package dev.gridiron.app

import kotlinx.coroutines.flow.MutableStateFlow

/** A [Refresher] the test drives by hand. */
class FakeRefresher(hasStats: Boolean = true, legacy: Boolean = false) : Refresher {
    override val state = MutableStateFlow<RefreshState>(RefreshState.Idle)
    override val hasStats = MutableStateFlow(hasStats)
    override val legacyData = MutableStateFlow(legacy)
    var refreshes = 0

    override fun refresh(): Boolean {
        refreshes++
        return true
    }

    override fun acknowledge() {
        if (state.value is RefreshState.Finished) state.value = RefreshState.Idle
    }
}
