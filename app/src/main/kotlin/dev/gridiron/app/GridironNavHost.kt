package dev.gridiron.app

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.feature.compare.CompareRoute
import dev.gridiron.feature.players.GridRoute
import dev.gridiron.feature.projections.AccuracyRoute
import dev.gridiron.feature.projections.ProjectionsRoute
import dev.gridiron.feature.scoring.ScoringEditRoute
import dev.gridiron.feature.scoring.ScoringListRoute

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
    val projections: ProjectionsRepository,
    val accuracy: AccuracyRepository,
    val teams: TeamsRepository,
    /** Downloads the latest stats; null in tests. */
    val refresh: (suspend () -> Result<String>)? = null,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = {
        deps.refresh?.let { download ->
            Toast.makeText(context, "Downloading latest stats…", Toast.LENGTH_SHORT).show()
            scope.launch {
                download()
                    .onSuccess { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        // Restart so every screen reopens on the new database.
                        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        context.startActivity(Intent.makeRestartActivityTask(intent!!.component))
                        Runtime.getRuntime().exit(0)
                    }
                    .onFailure { Toast.makeText(context, "Refresh failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
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
                    onPlayer = { id, season, week -> backStack.push(ProjectionsKey(id, season, week)) },
                    menu = listOf(
                        "Projection accuracy" to { s: Int -> backStack.push(AccuracyKey(s)) },
                        "Injury report" to { s: Int -> backStack.push(InjuriesKey(s)) },
                        "Team defense" to { s: Int -> backStack.push(DefenseKey(s)) },
                        "Refresh stats" to { _: Int -> refresh() },
                    ),
                )
            }
            entry<CompareKey> {
                CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.push(ScoringListKey) })
            }
            entry<ScoringListKey> {
                ScoringListRoute(deps.scoring, onEdit = { backStack.push(ScoringEditKey(it)) }, onBack = back)
            }
            entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
            entry<ProjectionsKey> { key -> ProjectionsRoute(key.playerId, key.season, key.week, deps.projections, onBack = back) }
            entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, onBack = back) }
            entry<InjuriesKey> { key -> InjuriesScreen(key.season, deps.teams, onBack = back) }
            entry<DefenseKey> { key -> DefenseScreen(key.season, deps.teams, onBack = back) }
        },
    )
}
