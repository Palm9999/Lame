package dev.gridiron.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gridiron.core.charts.ordinal
import dev.gridiron.core.data.GameLogRow
import dev.gridiron.core.data.PlayerStats
import dev.gridiron.core.data.SeasonLineRow
import dev.gridiron.core.designsystem.NumberStyle

/**
 * The Player page's "Season stats": season chips, the season line with each
 * stat's place at his position (1st = best), and a week-by-week game log. Emits its
 * own list items, so it lays out inside the page's `LazyColumn`.
 */
internal fun LazyListScope.playerStatsItems(stats: PlayerStats, onSeason: (Int) -> Unit) {
    item { SectionTitle("Season stats") }
    if (stats.seasons.isEmpty()) {
        item { Text("No games in the built seasons.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    item { SeasonChips(stats, onSeason) }
    if (stats.line.isEmpty() && stats.log.isEmpty()) return
    item {
        val summary = listOfNotNull("${stats.games} games", stats.bar).joinToString(" · ")
        Text(
            summary,
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!stats.ranked) {
            Text(
                "Below the ranking bar",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    item { SeasonLineHeader() }
    items(stats.line.size, key = { "line:${stats.line[it].label}" }) { SeasonLine(stats.line[it]) }
    if (stats.log.isNotEmpty()) {
        item { GameLogHeader(stats.logHeaders) }
        items(stats.log.size, key = { "log:${stats.log[it].week}" }) { GameLog(stats.log[it]) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonChips(stats: PlayerStats, onSeason: (Int) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (season in stats.seasons.asReversed()) {
            FilterChip(
                selected = season == stats.season,
                onClick = { onSeason(season) },
                label = { Text(season.toString()) },
                modifier = Modifier.testTag("season:$season"),
            )
        }
    }
}

@Composable
private fun SeasonLineHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Stat", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        HeaderCell("Total", 60)
        HeaderCell("Per game", 60)
        HeaderCell("Rank", 56)
    }
}

@Composable
private fun HeaderCell(text: String, width: Int) {
    Text(
        text,
        Modifier.width(width.dp),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.End,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SeasonLine(row: SeasonLineRow) {
    val spoken = buildString {
        append("${row.label}: ${row.total}")
        if (row.perGame.isNotEmpty()) append(", ${row.perGame} per game")
        append(row.place?.let { ", ${ordinal(it.rank)} of ${it.of}" } ?: ", not ranked")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .testTag("seasonLine:${row.label}")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.total, Modifier.width(60.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
        Text(row.perGame, Modifier.width(60.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
        // Nothing where he isn't ranked; the spoken description says "not ranked" and gives the field's size.
        Text(row.place?.let { ordinal(it.rank) }.orEmpty(), Modifier.width(56.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun GameLogHeader(headers: List<String>) {
    Text(
        "Game log",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Wk", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Game", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        headers.forEach { HeaderCell(it, 40) }
    }
}

@Composable
private fun GameLog(row: GameLogRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("gameLog:${row.week}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.week.toString(), Modifier.width(28.dp), style = NumberStyle)
        Text(
            listOfNotNull(row.opponent, row.result).joinToString(" "),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.cells.forEach { Text(it, Modifier.width(40.dp), style = NumberStyle, textAlign = TextAlign.End, maxLines = 1) }
    }
}
