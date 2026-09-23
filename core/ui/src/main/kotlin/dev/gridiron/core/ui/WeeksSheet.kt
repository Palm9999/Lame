package dev.gridiron.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gridiron.core.data.Catalog
import dev.gridiron.core.data.SeasonInfo
import dev.gridiron.core.model.CompareSlot
import dev.gridiron.core.model.WeekRange
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun WeeksSheet(
    season: SeasonInfo,
    weeks: WeekRange,
    onDismiss: () -> Unit,
    header: @Composable ColumnScope.() -> Unit = {},
    onChange: (WeekRange) -> Unit,
) {
    val max = season.lastWeek
    val regularEnd = WeekRange.lastRegularSeasonWeek(season.season)
    // Local while dragging; the query runs once the thumb is released.
    var range by remember(weeks) {
        mutableStateOf(weeks.first.toFloat()..minOf(weeks.last, max).toFloat())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            header()
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
                presets.forEach { (label, presetWeeks) ->
                    AssistChip(onClick = { onChange(presetWeeks); onDismiss() }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Season chips above the week slider; for changing a compare slot's season and range. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
public fun SeasonWeeksSheet(catalog: Catalog, slot: CompareSlot, onDismiss: () -> Unit, onChange: (CompareSlot) -> Unit) {
    var current by remember(slot) { mutableStateOf(slot) }
    val season = catalog.seasons.firstOrNull { it.season == current.season } ?: catalog.latest
    WeeksSheet(
        season,
        current.weeks,
        onDismiss,
        header = {
            FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                catalog.seasons.asReversed().forEach { s ->
                    FilterChip(
                        selected = s.season == current.season,
                        onClick = {
                            current = current.copy(season = s.season, weeks = s.defaultWeeks)
                            onChange(current)
                        },
                        label = { Text(s.season.toString()) },
                    )
                }
            }
        },
    ) { weeks ->
        current = current.copy(weeks = weeks)
        onChange(current)
    }
}
