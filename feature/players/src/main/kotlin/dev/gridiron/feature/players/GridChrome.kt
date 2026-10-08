package dev.gridiron.feature.players

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.PositionFilter
import dev.gridiron.core.data.describeFilter
import dev.gridiron.core.data.weeksLabel
import dev.gridiron.core.designsystem.GridironIcons
import dev.gridiron.core.ui.ProfileChip
import kotlin.math.roundToInt

/** Positions the "More" segment holds; the rest sit in the segmented control. */
private val MorePositions = listOf(PositionFilter.FLEX, PositionFilter.K, PositionFilter.DST)
private val SegmentPositions = PositionFilter.entries - MorePositions.toSet()

/**
 * The Grid's top bar: a title row, a pack-and-position row and a one-line
 * summary. It collapses out of the layout while [scroll] says hidden, so the
 * table gets the room, and a hidden bar is out of the accessibility tree.
 */
@Composable
internal fun GridChrome(
    state: GridUiState.Ready,
    onEvent: (GridEvent) -> Unit,
    onOpenWeeks: () -> Unit,
    onOpenFilters: () -> Unit,
    onEditProfiles: () -> Unit,
    menu: List<Pair<String, (season: Int) -> Unit>>,
    scroll: ChromeScrollState,
    modifier: Modifier = Modifier,
) {
    val offset by animateFloatAsState(scroll.offsetPx.value, label = "chromeOffset")
    var searching by remember { mutableStateOf(false) }
    Column(
        modifier
            .testTag("chrome")
            .semantics { if (scroll.hidden) hideFromAccessibility() }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                scroll.heightPx = placeable.height.toFloat()
                val shift = offset.coerceIn(-placeable.height.toFloat(), 0f)
                layout(placeable.width, (placeable.height + shift).roundToInt().coerceAtLeast(0)) {
                    placeable.place(0, shift.roundToInt())
                }
            }
            .clipToBounds(),
    ) {
        TitleRow(state, onEvent, onOpenWeeks, onEditProfiles, menu, searchActive = state.request.name.isNotEmpty(), onSearch = { searching = !searching })
        ControlRow(state, onEvent, onOpenFilters, searching, onCloseSearch = { searching = false })
        SummaryLine(state)
    }
}

@Composable
private fun TitleRow(
    state: GridUiState.Ready,
    onEvent: (GridEvent) -> Unit,
    onOpenWeeks: () -> Unit,
    onEditProfiles: () -> Unit,
    menu: List<Pair<String, (season: Int) -> Unit>>,
    searchActive: Boolean,
    onSearch: () -> Unit,
) {
    var seasons by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val tight = PaddingValues(horizontal = 8.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("Gridiron", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            ProfileChip(
                active = state.request.scoring,
                profiles = state.profiles,
                onSelect = { onEvent(GridEvent.ProfileSelected(it)) },
                onEditProfiles = onEditProfiles,
            )
            TextButton(onClick = onOpenWeeks, contentPadding = tight) {
                Text(weeksLabel(state.request.season, state.request.weeks) + " ▾", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            Box {
                TextButton(onClick = { seasons = true }, contentPadding = tight) {
                    Text("${state.request.season.season} ▾", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                DropdownMenu(expanded = seasons, onDismissRequest = { seasons = false }) {
                    state.catalog.seasons.asReversed().forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.season.toString()) },
                            onClick = {
                                seasons = false
                                onEvent(GridEvent.SeasonSelected(s.season))
                            },
                        )
                    }
                }
            }
        }
        val describe = Modifier.testTag("searchIcon").semantics { contentDescription = "Search players" }
        if (searchActive) {
            FilledTonalIconButton(onClick = onSearch, modifier = describe) { Icon(GridironIcons.Search, contentDescription = null) }
        } else {
            IconButton(onClick = onSearch, modifier = describe) { Icon(GridironIcons.Search, contentDescription = null) }
        }
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu")) { Text("☰") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action(state.request.season.season) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlRow(
    state: GridUiState.Ready,
    onEvent: (GridEvent) -> Unit,
    onOpenFilters: () -> Unit,
    searching: Boolean,
    onCloseSearch: () -> Unit,
) {
    val r = state.request
    if (searching) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = r.name,
                onValueChange = { onEvent(GridEvent.NameChanged(it)) },
                placeholder = { Text("Search players") },
                singleLine = true,
                trailingIcon = if (r.name.isNotEmpty()) {
                    { TextButton(onClick = { onEvent(GridEvent.NameChanged("")) }, modifier = Modifier.testTag("searchClear")) { Text("✕") } }
                } else {
                    null
                },
                modifier = Modifier.width(300.dp).focusRequester(focus).testTag("search"),
            )
            TextButton(onClick = onCloseSearch, modifier = Modifier.testTag("searchClose")) { Text("Done") }
        }
    } else {
        // Filters stays pinned at the end; the pack and positions scroll sideways when they don't fit beside it.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PackChip(state, onEvent)
                PositionSegments(r.positions) { onEvent(GridEvent.PositionsSelected(it)) }
            }
            val changes = state.viewChanges
            FilterChip(
                selected = changes > 0,
                onClick = onOpenFilters,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (changes == 0) "Filters" else "Filters ($changes)", maxLines = 1)
                        if (r.name.isNotEmpty()) {
                            Box(Modifier.padding(start = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("searchDot"))
                        }
                    }
                },
                modifier = Modifier.testTag("chip:filters"),
            )
        }
    }
}

@Composable
private fun PackChip(state: GridUiState.Ready, onEvent: (GridEvent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val r = state.request
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(r.pack.label + " ▾", maxLines = 1) },
            modifier = Modifier.testTag("chip:pack"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            r.positions.packs.forEach { pack ->
                DropdownMenuItem(
                    text = { Text(pack.label) },
                    onClick = {
                        open = false
                        onEvent(GridEvent.PackSelected(pack))
                    },
                    modifier = Modifier.testTag("pack:${pack.name}"),
                )
            }
        }
    }
}

/** All / QB / RB / WR / TE as one joined control, and a final "More ▾" segment for FLEX, K and D/ST. */
@Composable
private fun PositionSegments(selected: PositionFilter, onSelect: (PositionFilter) -> Unit) {
    var more by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        SegmentPositions.forEach { p ->
            Segment(p.label, selected == p, "chip:position:${p.name}") { onSelect(p) }
        }
        Box {
            Segment(if (selected in MorePositions) selected.label + " ▾" else "More ▾", selected in MorePositions, "chip:position:more") { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                MorePositions.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.label) },
                        onClick = {
                            more = false
                            onSelect(p)
                        },
                        modifier = Modifier.testTag("chip:position:${p.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** "212 players · threshold · filters · Data through week N", with the refresh bar beneath. */
@Composable
private fun SummaryLine(state: GridUiState.Ready) {
    val page = state.page
    val parts = buildList {
        state.error?.let { add("Error: $it") }
        if (page != null) add("${page.rows.size} players")
        page?.threshold?.let(::add)
        state.request.filters.forEach { add(describeFilter(it, state.catalog)) }
        add("Data through week ${state.request.season.lastWeek}")
    }
    Column {
        Text(
            parts.joinToString(" · "),
            Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 16.dp).testTag("summary"),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Reserve the bar's height so the table doesn't jump when it appears.
        Box(Modifier.fillMaxWidth().heightIn(min = 2.dp)) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
