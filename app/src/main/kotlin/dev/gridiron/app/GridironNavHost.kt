package dev.gridiron.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.feature.compare.CompareRoute
import dev.gridiron.feature.players.GridRoute
import dev.gridiron.feature.scoring.ScoringEditRoute
import dev.gridiron.feature.scoring.ScoringListRoute

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
)

/**
 * Pushes [key] unless it is already on top, so a double tap (Compare, Edit
 * profiles, a profile row) opens one screen, not two stacked copies that Back
 * would then have to peel off one by one.
 */
internal fun <T> MutableList<T>.push(key: T) {
    if (lastOrNull() != key) add(key)
}

@Composable
fun GridironNavHost(deps: Deps) {
    val backStack = rememberNavBackStack(GridKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        // Each destination gets its own saved state and ViewModelStore, so
        // leaving Compare clears its view model and returning rebuilds it.
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<GridKey> {
                GridRoute(
                    deps.stats, deps.scoring, deps.tray,
                    onCompare = { backStack.push(CompareKey) },
                    onEditProfiles = { backStack.push(ScoringListKey) },
                )
            }
            entry<CompareKey> {
                CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.push(ScoringListKey) })
            }
            entry<ScoringListKey> {
                ScoringListRoute(deps.scoring, onEdit = { backStack.push(ScoringEditKey(it)) }, onBack = back)
            }
            entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
        },
    )
}
