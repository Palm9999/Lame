package dev.gridiron.feature.projections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gridiron.core.projections.AttributedFactor
import dev.gridiron.core.projections.SimulationResult
import kotlin.math.round

/**
 * The one-screen projection card from design spec §3 / research doc §6.2:
 * baseline, each signed factor contribution, the final total, and
 * floor/ceiling. Values are never shown with false precision — one decimal,
 * matching the app-wide `CellUi` display convention.
 */
@Composable
public fun WaterfallCard(
    baseline: Double,
    factors: List<AttributedFactor>,
    final: Double,
    floorCeiling: SimulationResult,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Baseline (role + recent form)  ${oneDecimal(baseline)}")
        factors.forEach { f ->
            val sign = if (f.points >= 0) "+" else ""
            Text(text = "$sign${oneDecimal(f.points)}  ${f.factor}${f.note?.let { " — $it" } ?: ""}")
        }
        Text(text = "Projection  ${oneDecimal(final)}")
        Row {
            Text(text = "Floor ${oneDecimal(floorCeiling.p10)}")
            Text(text = "  ·  ")
            Text(text = "Ceiling ${oneDecimal(floorCeiling.p90)}")
        }
    }
}

private fun oneDecimal(value: Double): String = "%.1f".format(round(value * 10) / 10)
