package dev.gridiron.feature.compare

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.CompareRepository
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.SlotHeader
import dev.gridiron.core.data.SlotStatus
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.designsystem.SlotColors
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.ui.ProfileChip
import kotlinx.collections.immutable.ImmutableList

@Composable
fun CompareRoute(
    stats: StatsRepository,
    compare: CompareRepository,
    scoring: ScoringRepository,
    tray: CompareTrayRepository,
    onBack: () -> Unit,
    onEditProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: CompareViewModel = viewModel(factory = CompareViewModel.factory(stats, compare, scoring, tray))
    val state by vm.state.collectAsStateWithLifecycle()
    CompareScreen(state, vm::onEvent, onBack, onEditProfiles, modifier)
}

@Composable
internal fun CompareScreen(
    state: CompareUiState,
    onEvent: (CompareEvent) -> Unit,
    onBack: () -> Unit = {},
    onEditProfiles: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when (state) {
                CompareUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                CompareUiState.NeedsPlayers -> NeedsPlayers(onBack)
                is CompareUiState.Failed -> Text(
                    state.message,
                    Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                is CompareUiState.Ready -> CompareContent(state, onEvent, onBack, onEditProfiles)
            }
        }
    }
}

@Composable
private fun NeedsPlayers(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("← Back") }
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(
                "Hold players in the Grid to add them here. Compare needs at least two.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompareContent(
    state: CompareUiState.Ready,
    onEvent: (CompareEvent) -> Unit,
    onBack: () -> Unit,
    onEditProfiles: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onEvent(CompareEvent.MessageShown)
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tabs = if (landscape) listOf(CompareTab.BARS, CompareTab.RADAR, CompareTab.SCATTER) else CompareTab.entries.toList()
    val combinedLandscapeBars = landscape && (state.tab == CompareTab.BARS || state.tab == CompareTab.TABLE)
    val selectedIndex = tabs.indexOf(if (combinedLandscapeBars) CompareTab.BARS else state.tab).coerceAtLeast(0)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state, onEvent, onBack, onEditProfiles)
            SlotHeaderRow(state.page.slots, onRemove = { onEvent(CompareEvent.RemoveSlot(it)) })

            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                tabs.forEach { t ->
                    Tab(
                        selected = t == (if (combinedLandscapeBars) CompareTab.BARS else state.tab),
                        onClick = { onEvent(CompareEvent.TabSelected(t)) },
                        text = { Text(if (landscape && t == CompareTab.BARS) "Bars + Table" else t.label) },
                    )
                }
            }
            // Reserves the bar's height so tab content doesn't jump when a
            // re-query (per-game, profile switch, add/remove a slot) starts;
            // the old page stays on screen underneath while it runs.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("compareRefreshing"))
            }

            Box(Modifier.weight(1f)) {
                when {
                    combinedLandscapeBars -> Row(Modifier.fillMaxSize()) {
                        BarsTab(state.page.groups, Modifier.weight(1f))
                        TablePane(state, onEvent, Modifier.weight(1f))
                    }
                    state.tab == CompareTab.BARS -> BarsTab(state.page.groups, Modifier.fillMaxSize())
                    state.tab == CompareTab.TABLE -> TablePane(state, onEvent, Modifier.fillMaxSize())
                    state.tab == CompareTab.RADAR -> RadarTab(
                        state.page,
                        state.radarPair,
                        onRadarPairChanged = { a, b -> onEvent(CompareEvent.RadarPairChanged(a, b)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.tab == CompareTab.SCATTER -> ScatterTab(
                        state.page,
                        state.catalog,
                        state.selectedPoint,
                        onSelect = { onEvent(CompareEvent.PointSelected(it)) },
                        onAddToCompare = { id, name -> onEvent(CompareEvent.AddPointToCompare(id, name)) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun TablePane(state: CompareUiState.Ready, onEvent: (CompareEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        FilterChip(
            selected = state.onlyDifferences,
            onClick = { onEvent(CompareEvent.OnlyDifferencesToggled) },
            label = { Text("Only real differences") },
            modifier = Modifier.padding(8.dp),
        )
        TableTab(state.page, state.onlyDifferences, Modifier.weight(1f))
    }
}

@Composable
private fun TopBar(state: CompareUiState.Ready, onEvent: (CompareEvent) -> Unit, onBack: () -> Unit, onEditProfiles: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Compare",
            Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        ProfileChip(
            active = state.page.request.scoring,
            profiles = state.profiles,
            onSelect = { onEvent(CompareEvent.ProfileSelected(it)) },
            onEditProfiles = onEditProfiles,
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = state.page.request.perGame, onClick = { onEvent(CompareEvent.PerGameToggled) }, label = { Text("Per game") })
    }
}

private val ERROR_STATUSES = setOf(SlotStatus.NO_SEASON, SlotStatus.MISSING, SlotStatus.NO_GAMES)

/**
 * One column per slot, keyed by the slot itself (never the player id): the
 * same player can occupy two slots with different seasons or ranges.
 */
@Composable
private fun SlotHeaderRow(slots: ImmutableList<SlotHeader>, onRemove: (CompareSlot) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        slots.forEachIndexed { i, s ->
            key(s.slot) {
                Column(Modifier.weight(1f).padding(4.dp).testTag("compareSlotHeader")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(SlotColors.color(i), CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            s.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "✕",
                            Modifier.clickable { onRemove(s.slot) }
                                .padding(4.dp)
                                .semantics { contentDescription = "Remove ${s.name}" },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        s.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.status in ERROR_STATUSES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
