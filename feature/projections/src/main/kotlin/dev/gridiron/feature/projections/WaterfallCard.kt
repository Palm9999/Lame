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
import java.util.Locale
import kotlin.math.abs

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
    tdDependenceValue: Double = 0.0,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = "Baseline (role + recent form)  ${oneDecimal(baseline)}")
        factors.forEach { f ->
            val sign = if (Math.round(f.points * 10) / 10.0 >= 0.0) "+" else ""
            Text(text = "$sign${oneDecimal(f.points)}  ${f.factor}${f.note?.let { " — $it" } ?: ""}")
        }
        Text(text = "Projection  ${oneDecimal(final)}")
        Row {
            Text(text = "Floor ${oneDecimal(floorCeiling.p10)}")
            Text(text = "  ·  ")
            Text(text = "Ceiling ${oneDecimal(floorCeiling.p90)}")
        }
        Text(text = "TD dependence: ${(tdDependenceValue * 100).toInt()}%")
    }
}

/**
 * Mirrors `StatFormat.fixed(value, decimals = 1)`: rounds first so a value
 * like -0.04 never renders as "-0.0". Uses `Locale.getDefault()`, same as
 * `StatFormat.fixed()` -- output still varies with the device's own
 * decimal-separator convention (e.g. a comma-decimal locale), it just stays
 * consistent with the rest of the app's number formatting.
 */
private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    val clean = if (abs(rounded) < 0.05) 0.0 else rounded
    return String.format(Locale.getDefault(), "%.1f", clean)
}
