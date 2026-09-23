package dev.gridiron.feature.players

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import dev.gridiron.core.data.GridPage
import dev.gridiron.core.data.GridRowUi
import dev.gridiron.core.data.MetricInfo
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.ScoringRepository
import dev.gridiron.core.data.StatPack
import dev.gridiron.core.data.StatsRepository
import dev.gridiron.core.data.weeksLabel
import dev.gridiron.core.designsystem.HeaderStyle
import dev.gridiron.core.designsystem.NumberStyle
import dev.gridiron.core.designsystem.heatColor
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.statquery.Direction
import dev.gridiron.core.table.StatTable
import dev.gridiron.core.table.TableColumn
import dev.gridiron.core.ui.MetricSheet
import dev.gridiron.core.ui.ProfileChip
import dev.gridiron.core.ui.SeasonWeeksSheet
import dev.gridiron.core.ui.WeeksSheet
import kotlinx.collections.immutable.toImmutableList

@Composable
fun GridRoute(
    repository: StatsRepository,
    scoring: ScoringRepository,
    tray: CompareTrayRepository,
    onCompare: () -> Unit,
    onEditProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: GridViewModel = viewModel(factory = GridViewModel.factory(repository, scoring, tray))
    val state by vm.state.collectAsStateWithLifecycle()
    GridScreen(state, vm::onEvent, modifier, onCompare, onEditProfiles)
}

@Composable
fun GridScreen(
    state: GridUiState,
    onEvent: (GridEvent) -> Unit,
    modifier: Modifier = Modifier,
    onCompare: () -> Unit = {},
    onEditProfiles: () -> Unit = {},
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
            is GridUiState.Failed -> Text(
                state.message,
                Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.error,
            )
            is GridUiState.Ready -> GridContent(state, onEvent, onCompare, onEditProfiles)
        }
      }
    }
}

@Composable
private fun GridContent(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit, onCompare: () -> Unit, onEditProfiles: () -> Unit) {
    val r = state.request
    var showWeeks by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<MetricInfo?>(null) }
    var editingSlot by remember { mutableStateOf<CompareSlot?>(null) }
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onEvent(GridEvent.MessageShown)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TitleBar(state, onEvent, onWeeks = { showWeeks = true }, onEditProfiles = onEditProfiles)

            OutlinedTextField(
                value = r.name,
                onValueChange = { onEvent(GridEvent.NameChanged(it)) },
                placeholder = { Text("Search players") },
                singleLine = true,
                trailingIcon = if (r.name.isNotEmpty()) {
                    { TextButton(onClick = { onEvent(GridEvent.NameChanged("")) }) { Text("✕") } }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("search"),
            )

            ChipRow {
                StatPack.entries.forEach { pack ->
                    FilterChip(
                        selected = r.pack == pack,
                        onClick = { onEvent(GridEvent.PackSelected(pack)) },
                        label = { Text(pack.label) },
                    )
                }
            }
            ChipRow {
                PositionFilter.entries.forEach { p ->
                    FilterChip(
                        selected = r.positions == p,
                        onClick = { onEvent(GridEvent.PositionsSelected(p)) },
                        label = { Text(p.label) },
                    )
                }
            }

            Summary(state, onEvent)

            Box(Modifier.weight(1f)) {
                val page = state.page
                when {
                    page == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    page.rows.isEmpty() -> Text(
                        "No players match.",
                        Modifier.fillMaxWidth().padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> PlayerTable(
                        page,
                        state.heat,
                        onSort = { onEvent(GridEvent.SortBy(it.column)) },
                        onInfo = { info = it.info },
                        onRowLongClick = { row ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEvent(GridEvent.AddToCompare(row.playerId, row.name))
                        },
                    )
                }
            }

            if (state.tray.isNotEmpty()) {
                TrayBar(
                    tray = state.tray,
                    onEdit = { editingSlot = it.slot },
                    onRemove = { onEvent(GridEvent.RemoveFromTray(it)) },
                    onCompare = onCompare,
                )
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showWeeks) WeeksSheet(r.season, r.weeks, onDismiss = { showWeeks = false }, onChange = { onEvent(GridEvent.WeeksChanged(it)) })
    info?.let { MetricSheet(it, onDismiss = { info = null }) }
    editingSlot?.let { original ->
        SeasonWeeksSheet(
            state.catalog,
            original,
            onDismiss = { editingSlot = null },
            onChange = { updated ->
                onEvent(GridEvent.ReplaceTraySlot(original, updated))
                editingSlot = updated
            },
        )
    }
}

@Composable
private fun TitleBar(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit, onWeeks: () -> Unit, onEditProfiles: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Gridiron", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Data through week ${state.request.season.lastWeek}, ${state.request.season.season}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProfileChip(
            active = state.request.scoring,
            profiles = state.profiles,
            onSelect = { onEvent(GridEvent.ProfileSelected(it)) },
            onEditProfiles = onEditProfiles,
        )
        TextButton(onClick = onWeeks) {
            Text(weeksLabel(state.request.season, state.request.weeks) + " ▾", style = MaterialTheme.typography.titleSmall)
        }
        Box {
            TextButton(onClick = { open = true }) { Text("${state.request.season.season} ▾", style = MaterialTheme.typography.titleSmall) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.catalog.seasons.asReversed().forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.season.toString()) },
                        onClick = {
                            open = false
                            onEvent(GridEvent.SeasonSelected(s.season))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Summary(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit) {
    val page = state.page
    val parts = buildList {
        if (page != null) add("${page.rows.size} players")
        page?.threshold?.let(::add)
        state.error?.let { add("Error: $it") }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                parts.joinToString(" · "),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            FilterChip(selected = state.request.perGame, onClick = { onEvent(GridEvent.PerGameToggled) }, label = { Text("Per game") })
            FilterChip(selected = state.heat, onClick = { onEvent(GridEvent.HeatToggled) }, label = { Text("Heat") })
        }
        // Reserve the bar's height so the table doesn't jump when it appears.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

private val FrozenWidth = 172.sp
private val ColumnWidth = 78.sp
private val RowHeight = 48.sp
private val HeaderHeight = 44.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTable(
    page: GridPage,
    heat: Boolean,
    onSort: (ColumnUi) -> Unit,
    onInfo: (ColumnUi) -> Unit,
    onRowLongClick: (GridRowUi) -> Unit,
) {
    val columns = remember(page.columns) { page.columns.map { TableColumn(it.column, ColumnWidth) }.toImmutableList() }
    val sortIndex = page.columns.indexOfFirst { it.column == page.request.sort }
    val listState = rememberLazyListState()
    // A new sort or filter starts at the top; the same request never re-scrolls.
    LaunchedEffect(page.request) { listState.scrollToItem(0) }

    StatTable(
        columns = columns,
        rows = page.rows,
        rowKey = GridRowUi::playerId,
        frozenWidth = FrozenWidth,
        rowHeight = RowHeight,
        headerHeight = HeaderHeight,
        listState = listState,
        modifier = Modifier.testTag("grid"),
        frozenHeader = {
            Column(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Text("PLAYER", style = HeaderStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("hold a player to compare", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }
        },
        frozenCell = { index, row ->
            Row(Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    Modifier.width(26.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Column {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        cell = { row, i ->
            val c = row.cells[i]
            Box(
                Modifier.fillMaxSize().background(if (heat) heatColor(c.heat) else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    c.text,
                    Modifier.padding(end = 10.dp),
                    style = NumberStyle,
                    fontWeight = if (i == sortIndex) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        },
        rowDescription = { row ->
            buildString {
                append(row.name).append(", ").append(row.detail).append(". ")
                page.columns.forEachIndexed { i, col ->
                    append(col.info?.name ?: col.header).append(' ').append(row.cells[i].text)
                    if (i < page.columns.lastIndex) append(", ")
                }
            }
        },
        onRowLongClick = onRowLongClick,
        rowLongClickLabel = "Add to compare",
    )
}
