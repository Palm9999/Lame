package dev.gridiron.feature.players

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.GridRequest
import dev.gridiron.core.data.MetricInfo
import dev.gridiron.core.model.WeekRange
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeeksSheet(request: GridRequest, onDismiss: () -> Unit, onChange: (WeekRange) -> Unit) {
    val season = request.season
    val max = season.lastWeek
    val regularEnd = WeekRange.lastRegularSeasonWeek(season.season)
    // Local while dragging; the query runs once the thumb is released.
    var range by remember(request.weeks) {
        mutableStateOf(request.weeks.first.toFloat()..minOf(request.weeks.last, max).toFloat())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            val first = range.start.roundToInt()
            val last = range.endInclusive.roundToInt()
            Text(
                if (first == last) "Week $first" else "Weeks $first–$last",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (max > 1) {
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 1f..max.toFloat(),
                    steps = max - 2,
                    onValueChangeFinished = { onChange(WeekRange(range.start.roundToInt(), range.endInclusive.roundToInt())) },
                )
            }
            val presets = buildList {
                add("Regular season" to season.defaultWeeks)
                if (max >= 4) add("Last 4" to WeekRange(maxOf(1, minOf(max, regularEnd) - 3), minOf(max, regularEnd)))
                if (max >= 17) add("Fantasy playoffs (15–17)" to WeekRange(15, 17))
                if (max > regularEnd) add("Postseason" to WeekRange(regularEnd + 1, max))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (label, weeks) ->
                    AssistChip(onClick = { onChange(weeks); onDismiss() }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetricSheet(info: MetricInfo, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(info.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(info.abbr, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(info.definition, style = MaterialTheme.typography.bodyLarge)
            info.formula?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            info.predicts?.let {
                Spacer(Modifier.height(12.dp))
                Text("Predicts: $it", style = MaterialTheme.typography.bodyMedium)
            }
            info.stability?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Stability %.2f: how well this carries over from one season to the next (1 = perfectly).".format(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
