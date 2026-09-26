package dev.gridiron.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.gridiron.core.data.AccuracyRepository
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.PlayerDirectory
import dev.gridiron.core.data.ProjectionsRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SettingsRepository
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.TeamsRepository
import dev.gridiron.core.data.live.LiveRepository
import dev.gridiron.core.ingest.currentSeason
import dev.gridiron.feature.compare.CompareRoute
import dev.gridiron.feature.players.GridRoute
import dev.gridiron.feature.projections.AccuracyRoute
import dev.gridiron.feature.projections.ProjectionsRoute
import dev.gridiron.feature.scoring.ScoringEditRoute
import dev.gridiron.feature.scoring.ScoringListRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** What the screens need, built by [GridironApplication] or by a test. */
data class Deps(
    val stats: StatsRepository,
    val compare: CompareRepository,
    val scoring: ScoringRepository,
    val tray: CompareTrayRepository,
    val projections: ProjectionsRepository,
    val accuracy: AccuracyRepository,
    val teams: TeamsRepository,
    /** Player names by id, including players with no stats; null where a test doesn't need them. */
    val players: PlayerDirectory? = null,
    /** ESPN injuries and news. Null in tests: its SQLite driver can't load under Robolectric. */
    val live: LiveRepository? = null,
    val settings: SettingsRepository? = null,
    /** Builds stats on the phone. Null in tests, which read a prebuilt database. */
    val refresher: Refresher? = null,
)

/**
 * Pushes [key] unless it is already on top, so a double tap (Compare, Edit
 * profiles, a profile row) opens one screen, not two stacked copies that Back
 * would then have to peel off one by one.
 */
internal fun <T> MutableList<T>.push(key: T) {
    if (lastOrNull() != key) add(key)
}

// Stand-ins when there is no refresher (tests): stats exist, nothing is running.
private val STATS_PRESENT: StateFlow<Boolean> = MutableStateFlow(true)
private val NOT_LEGACY: StateFlow<Boolean> = MutableStateFlow(false)
private val IDLE: StateFlow<RefreshState> = MutableStateFlow(RefreshState.Idle)

@Composable
fun GridironNavHost(deps: Deps) {
    val hasStats by (deps.refresher?.hasStats ?: STATS_PRESENT).collectAsState()
    val refreshState by (deps.refresher?.state ?: IDLE).collectAsState()
    if (hasStats) StatsApp(deps, refreshState) else FirstLoad(deps, refreshState)
}

/** A fresh install: the Load stats screen, and the seasons checklist behind it. */
@Composable
private fun FirstLoad(deps: Deps, state: RefreshState) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val settings = deps.settings
    if (settingsOpen && settings != null) {
        SettingsScreen(settings, onBack = { settingsOpen = false })
    } else {
        LoadStatsScreen(
            state,
            onLoad = { deps.refresher?.refresh() },
            onSettings = if (settings != null) ({ settingsOpen = true }) else null,
        )
    }
}

@Composable
private fun StatsApp(deps: Deps, refreshState: RefreshState) {
    val refresher = deps.refresher
    val context = LocalContext.current
    // Each finished refresh is announced once, then cleared.
    LaunchedEffect(refreshState) {
        val finished = refreshState as? RefreshState.Finished ?: return@LaunchedEffect
        Toast.makeText(context, finished.message, Toast.LENGTH_LONG).show()
        refresher?.acknowledge()
    }
    val refresh: () -> Unit = {
        if (refresher?.refresh() == false) Toast.makeText(context, "A refresh is already running", Toast.LENGTH_SHORT).show()
    }
    LegacyPrompt(refresher, refreshState, refresh)

    val backStack = rememberNavBackStack(GridKey)
    val back: () -> Unit = { backStack.removeLastOrNull() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
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
                            onPlayer = { id, _, _ -> backStack.push(PlayerKey(id)) },
                            menu = buildList<Pair<String, (Int) -> Unit>> {
                                if (deps.live != null) add("News" to { _: Int -> backStack.push(NewsKey) })
                                add("Injury report" to { s: Int -> backStack.push(InjuriesKey(s)) })
                                add("Team defense" to { s: Int -> backStack.push(DefenseKey(s)) })
                                if (deps.settings != null) add("Settings" to { _: Int -> backStack.push(SettingsKey) })
                                if (refresher != null) add("Refresh stats" to { _: Int -> refresh() })
                            },
                            badges = deps.live?.badges ?: flowOf(emptyMap()),
                            recovery = buildList {
                                if (refresher != null) add("Refresh stats" to { refresh() })
                                if (deps.settings != null) add("Settings" to { backStack.push(SettingsKey) })
                            },
                        )
                    }
                    entry<CompareKey> {
                        CompareRoute(deps.stats, deps.compare, deps.scoring, deps.tray, onBack = back, onEditProfiles = { backStack.push(ScoringListKey) })
                    }
                    entry<ScoringListKey> {
                        ScoringListRoute(deps.scoring, onEdit = { backStack.push(ScoringEditKey(it)) }, onBack = back)
                    }
                    entry<ScoringEditKey> { key -> ScoringEditRoute(key.profileId, deps.scoring, onDone = back) }
                    // Unreachable until the on-device projections follow-up: no menu item or row tap leads here.
                    entry<ProjectionsKey> { key -> ProjectionsRoute(key.playerId, key.season, key.week, deps.projections, onBack = back) }
                    entry<AccuracyKey> { key -> AccuracyRoute(key.season, deps.accuracy, onBack = back) }
                    entry<InjuriesKey> { key ->
                        InjuriesRoute(key.season, currentSeason(), deps.teams, deps.live, onBack = back, onPlayer = { backStack.push(PlayerKey(it)) })
                    }
                    entry<NewsKey> {
                        deps.live?.let { NewsRoute(it, onBack = back, onPlayer = { id -> backStack.push(PlayerKey(id)) }) }
                    }
                    entry<PlayerKey> { key -> PlayerRoute(key.playerId, deps.players, deps.live, onBack = back) }
                    entry<DefenseKey> { key -> DefenseScreen(key.season, deps.teams, onBack = back) }
                    entry<SettingsKey> { deps.settings?.let { SettingsScreen(it, onBack = back) } }
                },
            )
        }
        (refreshState as? RefreshState.Running)?.let { RefreshBar(it.text) }
    }
}

/** Stats that came with an older app version: offer, once per launch, to build fresh ones here. */
@Composable
private fun LegacyPrompt(refresher: Refresher?, state: RefreshState, refresh: () -> Unit) {
    val legacy by (refresher?.legacyData ?: NOT_LEGACY).collectAsState()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!legacy || dismissed || state is RefreshState.Running) return
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Stats now build on your phone") },
        text = {
            Text(
                "These stats came with an older version of the app. Refresh to build them from nflverse " +
                    "on this phone, about a minute per season. The current stats stay until then.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                refresh()
            }) { Text("Refresh now") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Later") } },
    )
}

/** A running refresh's progress, under whatever screen is open. */
@Composable
private fun RefreshBar(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
