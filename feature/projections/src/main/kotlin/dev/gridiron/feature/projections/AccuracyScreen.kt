package dev.gridiron.feature.projections

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.gridiron.core.data.AccuracyRow
import java.util.Locale
import kotlin.math.abs

/** The trust page: weekly MAE by position, model vs. the two naive baselines,
 * live from week 1 (design spec §4, research doc §5.4). No client-side
 * computation — every number here is read directly from `accuracy_summary`. */
@Composable
public fun AccuracyScreen(rows: List<AccuracyRow>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(rows) { row ->
            Text(
                text = "${row.position} ${row.metricId} (${row.baseline}): " +
                    "MAE ${oneDecimal(row.mae)}, n=${row.sampleN}",
            )
        }
    }
}

/**
 * Mirrors `StatFormat.fixed(value, decimals = 1)`: rounds first so a value
 * like -0.04 never renders as "-0.0", and formats with an explicit Locale so
 * output doesn't vary with the device's comma-decimal locale settings.
 */
private fun oneDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    val clean = if (abs(rounded) < 0.05) 0.0 else rounded
    return String.format(Locale.getDefault(), "%.1f", clean)
}
