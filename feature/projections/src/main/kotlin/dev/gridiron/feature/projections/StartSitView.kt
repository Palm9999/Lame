package dev.gridiron.feature.projections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.model.Position
import java.util.Locale
import kotlin.random.Random

/** The most players Start/sit compares at once. */
internal const val START_SIT_MAX = 4

/**
 * Each player's chance to score the most of [rows] this week: every projection drawn as a normal with its [spread]
 * (floored at zero), [draws] times, from a fixed seed so the numbers don't flicker. Ties split evenly. An Out player
 * (no spread, no points) never wins unless all are out.
 */
internal fun chanceToLead(rows: List<ProjectionRow>, draws: Int = 20_000, seed: Int = 7): List<Double> {
    if (rows.isEmpty()) return emptyList()
    val rng = Random(seed)
    val wins = DoubleArray(rows.size)
    val sds = rows.map(::spread)
    val scores = DoubleArray(rows.size)
    repeat(draws) {
        var best = Double.NEGATIVE_INFINITY
        for (i in rows.indices) {
            scores[i] = maxOf(0.0, rows[i].points + sds[i] * gaussian(rng))
            if (scores[i] > best) best = scores[i]
        }
        val leaders = rows.indices.count { scores[it] == best }
        for (i in rows.indices) if (scores[i] == best) wins[i] += 1.0 / leaders
    }
    return wins.map { it / draws }
}

/** A standard normal draw (Box-Muller). */
private fun gaussian(rng: Random): Double {
    val u = 1.0 - rng.nextDouble()
    val v = rng.nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
}

/**
 * Start/sit: tick two to [START_SIT_MAX] players from this week's projections and see each one's projection, range
 * and chance to score the most; the pick is the best chance.
 */
@Composable
internal fun StartSitView(weekRows: List<ProjectionRow>, badges: Map<String, String>) {
    var tab by rememberSaveable { mutableStateOf(PositionTab.FLEX) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val byId = remember(weekRows, badges) { weekRows.associate { it.playerId to outAdjusted(it, badges) } }
    val chosen = picked.mapNotNull(byId::get)
    val chances = remember(chosen) { if (chosen.size >= 2) chanceToLead(chosen) else emptyList() }
    val rows = remember(weekRows, tab, badges) { visibleRows(weekRows, tab, badges, week = true) }

    LazyColumn(Modifier.fillMaxSize().testTag("startsit")) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (chosen.size < 2) {
                    Text(
                        "Tick two to $START_SIT_MAX players to see who to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val pick = chances.indices.maxBy { chances[it] }
                    Text("Start ${chosen[pick].name}", Modifier.testTag("startsit:pick"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    chosen.forEachIndexed { i, row ->
                        Text(
                            "${row.name}: ${pct(chances[i])} to score most · ${if (row.out) "Out" else "${pts(row.points)} (${pts(row.floor)}–${pts(row.ceiling)})"}",
                            Modifier.testTag("startsit:line:${row.playerId}"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (picked.isNotEmpty()) TextButton(onClick = { picked = emptyList() }) { Text("Clear") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in PositionTab.entries) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, modifier = Modifier.testTag("sstab:${t.name}"))
            }
        }
        itemsIndexed(rows, key = { _, r -> "ss:${r.playerId}" }) { _, row ->
            val on = row.playerId in picked
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(on, enabled = on || picked.size < START_SIT_MAX, role = Role.Checkbox) { now ->
                        picked = if (now) picked + row.playerId else picked - row.playerId
                    }
                    .padding(start = 4.dp, end = 16.dp).testTag("ss:${row.playerId}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null, enabled = on || picked.size < START_SIT_MAX)
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(Position.label(row.position), row.team).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (row.out) "Out" else pts(row.points), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun pts(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun pct(p: Double): String = "${(p * 100).toInt().coerceIn(0, 100)}%".let { if (p > 0.0 && p < 0.01) "<1%" else it }
