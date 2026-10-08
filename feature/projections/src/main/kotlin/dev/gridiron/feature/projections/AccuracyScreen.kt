package dev.gridiron.feature.projections

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gridiron.core.designsystem.EmptyState
import dev.gridiron.core.designsystem.ScreenBar
import dev.gridiron.core.model.Position
import dev.gridiron.core.projections.ACCURACY_MIN_POINTS
import dev.gridiron.core.projections.ACCURACY_POSITIONS
import dev.gridiron.core.projections.ErrorStats
import dev.gridiron.core.projections.PositionAccuracy

/**
 * ☰ → Projection accuracy: how past weeks' projections did against what
 * players scored, next to two simple baselines (spec §4).
 */
@Composable
public fun AccuracyScreen(state: AccuracyState, onSeason: (Int) -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenBar("Projection accuracy", onBack, titleModifier = Modifier.testTag("accuracyTitle"))
            when (state) {
                AccuracyState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("Scoring every projected week…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is AccuracyState.Unavailable -> EmptyState(state.message)
                is AccuracyState.Loaded -> Measured(state, onSeason)
            }
        }
    }
}

@Composable
private fun Measured(state: AccuracyState.Loaded, onSeason: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "Scored with ${state.profile}" + (state.millis?.let { " in " + String.format(java.util.Locale.US, "%.1f", it / 1000.0) + " s" }.orEmpty()),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (s in state.seasons) FilterChip(selected = s == state.season, onClick = { onSeason(s) }, label = { Text("$s") })
            }
        }
        if (state.season == state.seasons.minOrNull()) {
            item {
                Text(
                    "${state.season} is the oldest season built, so its projections start without last season's history " +
                        "and run less accurate. Add the season before it in Settings to measure it fairly.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.positions.isEmpty()) {
            item {
                Text("No player-weeks to measure in ${state.season}.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(state.positions, key = { it.position }) { PositionTable(it) }
        val missing = ACCURACY_POSITIONS - state.positions.map { it.position }.toSet()
        if (state.positions.isNotEmpty() && missing.isNotEmpty()) {
            item {
                Text("Nothing to measure at ${missing.joinToString(transform = Position::label)}.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                "Counts weeks where the model projected at least ${ACCURACY_MIN_POINTS.toInt()} points (any projection for a kicker or D/ST) " +
                    "and the player played, " +
                    "from the player's second game of the season. Bias is projected minus actual. " +
                    "Past weeks are projected without betting props.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A position as the page names it: a team defense is "D/ST". */

@Composable
private fun PositionTable(p: PositionAccuracy) {
    val best = minOf(p.model.mae, p.seasonAverage.mae, p.lastFour.mae)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${Position.label(p.position)} · ${p.playerWeeks} player-weeks", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Floor to ceiling held ${percent(p.calibration)} of scores (target about 80%)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Cells("", "MAE", "Bias", "R²", header = true)
        Predictor("Model", p.model, best)
        Predictor("Season avg", p.seasonAverage, best)
        Predictor("Last 4", p.lastFour, best)
    }
}

/** One predictor's row; the lowest MAE of the three is bold. */
@Composable
private fun Predictor(label: String, stats: ErrorStats, best: Double) {
    Cells(label, fixed(stats.mae, 1), signed(stats.bias), r2Text(stats.r2), header = false, boldMae = stats.mae == best)
}

@Composable
private fun Cells(label: String, mae: String, bias: String, r2: String, header: Boolean, boldMae: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = style, color = color)
        Text(
            mae,
            Modifier.width(56.dp),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            fontWeight = if (boldMae) FontWeight.Bold else FontWeight.Normal,
        )
        Text(bias, Modifier.width(56.dp), style = style, color = color, textAlign = TextAlign.End)
        Text(r2, Modifier.width(56.dp), style = style, color = color, textAlign = TextAlign.End)
    }
}
