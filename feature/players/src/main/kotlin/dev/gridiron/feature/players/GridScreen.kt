package dev.gridiron.feature.players

import dev.gridiron.core.ui.SharePreview
import dev.gridiron.core.ui.StatTableCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.gridiron.core.data.ColumnUi
import dev.gridiron.core.data.CompareTrayRepository
import dev.gridiron.core.data.CsvExport
import dev.gridiron.core.data.GridDisplayRepository
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridPresetRepository
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.GridRowUi
import dev.gridiron.core.data.MetricInfo
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.describeFilter
import dev.gridiron.core.data.weeksLabel
import dev.gridiron.core.data.live.LeagueRostered
import dev.gridiron.core.model.ScoringProfile
import dev.gridiron.core.datastore.RowDensity
import dev.gridiron.core.designsystem.HeaderStyle
import dev.gridiron.core.designsystem.NumberStyle
import dev.gridiron.core.designsystem.heatColor
import dev.gridiron.core.model.Roster
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.table.StatTable
import dev.gridiron.core.table.TableColumn
import dev.gridiron.core.ui.MetricSheet
import dev.gridiron.core.ui.ProfileChip
import dev.gridiron.core.ui.SeasonWeeksSheet
import dev.gridiron.core.ui.WeeksSheet
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GridRoute(
    repository: StatsRepository,
    scoring: ScoringRepository,
    tray: CompareTrayRepository,
    onCompare: () -> Unit,
    onEditProfiles: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayer: (playerId: String, season: Int, week: Int) -> Unit = { _, _, _ -> },
    menu: List<Pair<String, (season: Int) -> Unit>> = emptyList(),
    badges: Flow<Map<String, String>> = flowOf(emptyMap()),
    recovery: List<Pair<String, () -> Unit>> = emptyList(),
    rosters: Flow<List<Roster>> = flowOf(emptyList()),
    presets: GridPresetRepository? = null,
    display: GridDisplayRepository? = null,
    leagueRostered: Flow<LeagueRostered?> = flowOf(null),
    dynasty: (suspend (ScoringProfile) -> Map<String, Double>)? = null,
) {
    val vm: GridViewModel = viewModel(factory = GridViewModel.factory(repository, scoring, tray, badges, rosters, presets, display, leagueRostered, dynasty))
    val state by vm.state.collectAsStateWithLifecycle()
    GridScreen(state, vm::onEvent, modifier, onCompare, onEditProfiles, onPlayer, menu, recovery)
}

@Composable
fun GridScreen(
    state: GridUiState,
    onEvent: (GridEvent) -> Unit,
    modifier: Modifier = Modifier,
    onCompare: () -> Unit = {},
    onEditProfiles: () -> Unit = {},
    onPlayer: (playerId: String, season: Int, week: Int) -> Unit = { _, _, _ -> },
    menu: List<Pair<String, (season: Int) -> Unit>> = emptyList(),
    /** Offered when the database won't open (the ☰ menu needs a season, so it can't show): a way out. */
    recovery: List<Pair<String, () -> Unit>> = emptyList(),
    /** Hands the exported CSV to the share sheet; a seam so tests needn't declare a FileProvider. */
    share: suspend (Context, fileName: String, csv: String) -> Boolean = CsvShare::share,
) {
    // A Surface, not a Box with a background: it also sets the content color
    // that every Text inherits. Without it, text defaults to black, which is
    // unreadable in dark mode.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (state) {
            GridUiState.Loading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Opening stats…", style = MaterialTheme.typography.bodyMedium)
            }
            is GridUiState.Failed -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                recovery.forEach { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
            is GridUiState.Ready -> GridContent(state, onEvent, onCompare, onEditProfiles, onPlayer, menu, share)
        }
      }
    }
}

@Composable
private fun GridContent(
    state: GridUiState.Ready,
    onEvent: (GridEvent) -> Unit,
    onCompare: () -> Unit,
    onEditProfiles: () -> Unit,
    onPlayer: (playerId: String, season: Int, week: Int) -> Unit,
    menu: List<Pair<String, (season: Int) -> Unit>>,
    share: suspend (Context, fileName: String, csv: String) -> Boolean,
) {
    val r = state.request
    var showWeeks by remember { mutableStateOf(false) }
    var showTeams by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MetricInfo?>(null) }
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    val chromeHide = with(LocalDensity.current) { 24.dp.toPx() }
    val chrome = remember(chromeHide) { ChromeScrollState(chromeHide) }
    // A new sort, filter or pack must never leave the user unable to see the controls that changed it.
    LaunchedEffect(r) { chrome.show() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val sharedPage = state.page
    if (sharing && sharedPage != null) {
        val card = remember(sharedPage) { gridCard(sharedPage, state.catalog) }
        SharePreview("gridiron-grid.png", onDismiss = { sharing = false }) {
            StatTableCard(card.title, sharedPage.threshold, card.headers, card.rows)
        }
    }
    fun export() {
        if (exporting) return
        val page = state.page ?: return
        exporting = true
        scope.launch {
            try {
                val csv = withContext(Dispatchers.Default) { CsvExport.build(page, state.catalog) }
                val ok = share(context, CsvExport.fileName(page.request), csv)
                exporting = false
                if (!ok) snackbar.showSnackbar("Couldn't export")
            } finally {
                exporting = false
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onEvent(GridEvent.MessageShown)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GridChrome(
                state = state,
                onEvent = onEvent,
                onOpenWeeks = { showWeeks = true },
                onOpenFilters = { showFilters = true },
                onEditProfiles = onEditProfiles,
                menu = menu,
                scroll = chrome,
            )

            Box(Modifier.weight(1f)) {
                val page = state.page
                when {
                    page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    page.rows.isEmpty() -> Text(
                        if (page.request.onlyPlayers?.isEmpty() == true) "This roster is empty. Add players from their player page." else "No players match.",
                        Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> PlayerTable(
                        page,
                        state.heat,
                        state.density,
                        state.badges,
                        state.rostered,
                        chrome,
                        onSort = { onEvent(GridEvent.SortBy(it.column)) },
                        onInfo = { info = it.info },
                        onRowLongClick = { row ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEvent(GridEvent.AddToCompare(row.playerId, row.name))
                        },
                        // Tap opens the player's page (status, news and this week's projection).
                        onRowClick = { row -> onPlayer(row.playerId, r.season.season, r.season.lastWeek + 1) },
                    )
                }
            }

            if (state.tray.isNotEmpty()) {
                TrayBar(
                    tray = state.tray,
                    onEdit = { onEvent(GridEvent.EditTraySlot(it.slot)) },
                    onRemove = { onEvent(GridEvent.RemoveFromTray(it)) },
                    onCompare = onCompare,
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showWeeks) WeeksSheet(r.season, r.weeks, onDismiss = { showWeeks = false }, onChange = { onEvent(GridEvent.WeeksChanged(it)) })
    info?.let { MetricSheet(it, onDismiss = { info = null }) }
    state.editingSlot?.let { original ->
        SeasonWeeksSheet(
            state.catalog,
            original,
            onDismiss = { onEvent(GridEvent.TraySlotEditClosed) },
            onChange = { updated -> onEvent(GridEvent.ReplaceTraySlot(original, updated)) },
        )
    }
    state.presetSheet?.let { PresetsSheet(state, it, onEvent) }
    if (showTeams) {
        TeamSheet(state.catalog.teams, r.teams, onChange = { onEvent(GridEvent.TeamsSelected(it)) }, onDismiss = { showTeams = false })
    }
    if (showFilters) {
        FilterSheet(
            state = state,
            onEvent = onEvent,
            onOpenTeams = {
                onEvent(GridEvent.FilterSheetClosed)
                showFilters = false
                showTeams = true
            },
            onExport = ::export,
            exporting = exporting,
            onShareImage = {
                onEvent(GridEvent.FilterSheetClosed)
                showFilters = false
                sharing = true
            },
            onDraftChanged = { onEvent(GridEvent.FilterDraftChanged(it)) },
            onApply = {
                onEvent(GridEvent.FiltersApplied(it))
                showFilters = false
            },
            onDismiss = {
                onEvent(GridEvent.FilterSheetClosed)
                showFilters = false
            },
        )
    }
}

@Composable
internal fun RosterChip(
    rosters: ImmutableList<Roster>,
    selected: String?,
    freeAgents: FreeAgentsOption? = null,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = {
                Text(
                    if (selected == GridViewModel.FREE_AGENTS_ID) "Free agents" else rosters.firstOrNull { it.id == selected }?.name ?: "All players",
                )
            },
            modifier = Modifier.testTag("chip:roster"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All players") }, onClick = { open = false; onSelect(null) })
            if (freeAgents != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Free agents")
                            Text(
                                "League synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(freeAgents.asOfMillis))}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    onClick = { open = false; onSelect(GridViewModel.FREE_AGENTS_ID) },
                    modifier = Modifier.testTag("roster:free-agents"),
                )
            }
            for (roster in rosters) {
                DropdownMenuItem(text = { Text(roster.name) }, onClick = { open = false; onSelect(roster.id) })
            }
        }
    }
}

@Composable
internal fun SnapChip(share: Double?, onSelect: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(s: Double?) = if (s == null) "Any snaps" else "${(s * 100).toInt()}%+ snaps"
    Box {
        FilterChip(selected = share != null, onClick = { open = true }, label = { Text(label(share)) }, modifier = Modifier.testTag("chip:snaps"))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<Double?>(null) + GridRequest.SNAP_SHARE_CHOICES).forEach { s ->
                DropdownMenuItem(text = { Text(label(s)) }, onClick = { open = false; onSelect(s) })
            }
        }
    }
}

private val FrozenWidth = 148.sp
private val ColumnWidth = 72.sp
private val ComfortableRowHeight = 48.sp
private val CompactRowHeight = 40.sp
private val HeaderHeight = 44.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTable(
    page: GridPage,
    heat: Boolean,
    density: RowDensity,
    badges: ImmutableMap<String, String>,
    rostered: ImmutableSet<String>,
    chrome: ChromeScrollState,
    onSort: (ColumnUi) -> Unit,
    onInfo: (ColumnUi) -> Unit,
    onRowLongClick: (GridRowUi) -> Unit,
    onRowClick: (GridRowUi) -> Unit = {},
) {
    val columns = remember(page.columns) { page.columns.map { TableColumn(it.column, ColumnWidth) }.toImmutableList() }
    val sortIndex = page.columns.indexOfFirst { it.column == page.request.sort }
    val listState = rememberLazyListState()
    // A new sort or filter starts at the top; the same request never re-scrolls.
    LaunchedEffect(page.request) { listState.scrollToItem(0) }
    // Back at the very top the bar always returns, however the list got there.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { atTop -> if (atTop) chrome.show() }
    }

    StatTable(
        columns = columns,
        rows = page.rows,
        rowKey = GridRowUi::playerId,
        frozenWidth = FrozenWidth,
        rowHeight = if (density == RowDensity.COMPACT) CompactRowHeight else ComfortableRowHeight,
        zebra = false,
        rowDivider = true,
        sortedColumnIndex = sortIndex.takeIf { it >= 0 },
        sortedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        headerHeight = HeaderHeight,
        listState = listState,
        modifier = Modifier.nestedScroll(chrome.connection).testTag("grid"),
        frozenHeader = {
            Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Text("PLAYER", style = HeaderStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "hold a player to compare",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        header = { i ->
            val column = page.columns[i]
            val sorted = i == sortIndex
            val arrow = if (!sorted) "" else if (page.request.direction == Direction.DESCENDING) " ▼" else " ▲"
            Box(
                Modifier.fillMaxSize()
                    .combinedClickable(onClick = { onSort(column) }, onLongClick = { onInfo(column) })
                    .testTag("header:${column.column.name}"),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    column.header + arrow,
                    Modifier.padding(end = 10.dp),
                    style = HeaderStyle,
                    color = if (sorted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (sorted) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("sortedUnderline:${column.column.name}"),
                    )
                }
            }
        },
        frozenCell = { index, row ->
            Row(
                Modifier.fillMaxWidth().align(Alignment.CenterStart).padding(start = 8.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    Modifier.width(20.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (row.playerId in rostered) {
                            Text("★ ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            row.name,
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        row.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                badges[row.playerId]?.let { InjuryBadge(it, Modifier.padding(start = 4.dp).testTag("injuryPill:${row.playerId}")) }
            }
        },
        cell = { row, i ->
            val c = row.cells[i]
            Box(
                Modifier.fillMaxSize().background(if (heat) heatColor(c.heat) else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    c.display,
                    Modifier.padding(end = 10.dp),
                    style = NumberStyle,
                    fontWeight = if (i == sortIndex) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        },
        rowDescription = { row ->
            buildString {
                append(row.name)
                if (row.playerId in rostered) append(" (on your roster)")
                badges[row.playerId]?.let { append(" (injury status ").append(it).append(')') }
                append(", ").append(row.detail).append(". ")
                page.columns.forEachIndexed { i, col ->
                    append(col.info?.name ?: col.header).append(' ').append(row.cells[i].text)
                    if (i < page.columns.lastIndex) append(", ")
                }
            }
        },
        onRowLongClick = onRowLongClick,
        onRowClick = onRowClick,
        rowLongClickLabel = "Add to compare",
    )
}

/** ESPN's injury letter as an outlined pill: red for O, IR and D, the accent color for Q and anything else. */
@Composable
private fun InjuryBadge(abbr: String, modifier: Modifier = Modifier) {
    val color = when (abbr) {
        "O", "IR", "D" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        abbr,
        modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}
