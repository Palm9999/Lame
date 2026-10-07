package dev.gridiron.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Chart colors from the validated chart palette (blue for one series or "ahead", red for "behind"), each mode its own
 * step checked against that mode's surface. Text never wears them: values stay in the ink colors.
 */
public object ChartColors {
    @Composable
    private fun dark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** One series, or the "ahead" side of a diverging bar. */
    @Composable
    public fun series(): Color = if (dark()) Color(0xFF3987E5) else Color(0xFF2A78D6)

    /** The "behind" side of a diverging bar. */
    @Composable
    public fun negative(): Color = if (dark()) Color(0xFFE66767) else Color(0xFFE34948)

    /** The empty part of a meter, and a diverging bar's zero line. */
    @Composable
    public fun track(): Color = if (dark()) Color(0xFF383835) else Color(0xFFE4E4E0)
}

/** An ESPN designation as a small pill: red for Out, IR, Doubtful and suspended, the accent for the rest. */
@Composable
public fun StatusBadge(abbr: String, modifier: Modifier = Modifier) {
    val serious = abbr in setOf("O", "IR", "D", "SUSP")
    Surface(
        modifier,
        shape = RoundedCornerShape(4.dp),
        color = if (serious) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (serious) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(abbr, Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

/** A lineup slot or position ("FLEX", "RB") as a neutral pill of fixed [width], so a column of them lines up. */
@Composable
public fun SlotTag(text: String, modifier: Modifier = Modifier, width: Dp = 48.dp) {
    Box(modifier.width(width), contentAlignment = Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                text,
                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A section's heading: bold ink with room above, the same on every screen. */
@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A rounded card for a screen's headline numbers. */
@Composable
public fun SummaryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

/** A share (0..1) as a filled track, for one headline number such as a win chance; [description] is read aloud. */
@Composable
public fun Meter(fraction: Double, description: String, modifier: Modifier = Modifier) {
    val fill = ChartColors.series()
    val track = ChartColors.track()
    Canvas(modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = description }) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0.0, 1.0).toFloat()
        if (w > 0f) drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
    }
}

/**
 * A player's likely range on a shared scale: a light span from [low] to [high] and a tick at [mid], all over
 * [max] (the list's highest ceiling), so rows compare at a glance.
 */
@Composable
public fun RangeBar(low: Double, mid: Double, high: Double, max: Double, modifier: Modifier = Modifier) {
    val color = ChartColors.series()
    val track = ChartColors.track()
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        if (max <= 0.0) return@Canvas
        fun x(v: Double) = (size.width * (v / max).coerceIn(0.0, 1.0)).toFloat()
        val h = size.height
        drawRoundRect(track, topLeft = Offset(0f, h / 3), size = Size(size.width, h / 3), cornerRadius = CornerRadius(h / 6, h / 6))
        drawRoundRect(
            color.copy(alpha = 0.35f), topLeft = Offset(x(low), 0f), size = Size((x(high) - x(low)).coerceAtLeast(2f), h),
            cornerRadius = CornerRadius(h / 2, h / 2),
        )
        drawRect(color, topLeft = Offset(x(mid) - 1.5.dp.toPx(), 0f), size = Size(3.dp.toPx(), h))
    }
}

/**
 * A signed [value] as a bar from a center line: right in blue when ahead, left in red when behind, both over [maxAbs].
 * The number itself is shown beside it in ink.
 */
@Composable
public fun DivergingBar(value: Double, maxAbs: Double, modifier: Modifier = Modifier) {
    val ahead = ChartColors.series()
    val behind = ChartColors.negative()
    val zero = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.height(10.dp)) {
        val mid = size.width / 2
        drawRect(zero, topLeft = Offset(mid - 0.5.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
        if (maxAbs <= 0.0 || value == 0.0) return@Canvas
        val w = (mid * (abs(value) / maxAbs).coerceIn(0.0, 1.0)).toFloat()
        val left = if (value > 0) mid + 1.dp.toPx() else mid - 1.dp.toPx() - w
        drawRoundRect(
            if (value > 0) ahead else behind, topLeft = Offset(left, size.height * 0.15f), size = Size(w, size.height * 0.7f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
    }
}

/**
 * A small column chart: one bar per [labels] entry with its [values] (null: nothing to draw, e.g. a missed game) over
 * the highest value or [max]; [valueText] labels the bars named in [labelled] (all when null), above them in ink.
 */
@Composable
public fun ColumnChart(
    labels: List<String>,
    values: List<Double?>,
    valueText: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    max: Double? = null,
    labelled: Set<Int>? = null,
    height: Dp = 64.dp,
) {
    val color = ChartColors.series()
    val top = max ?: values.filterNotNull().maxOrNull() ?: 0.0
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            values.forEachIndexed { i, v ->
                Text(
                    if (v != null && (labelled == null || i in labelled)) valueText(v) else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            values.forEach { v ->
                Box(Modifier.weight(1f).height(height), contentAlignment = Alignment.BottomCenter) {
                    if (v != null && top > 0.0 && v > 0.0) {
                        val share = (v / top).coerceIn(0.0, 1.0).toFloat()
                        Box(
                            Modifier.fillMaxWidth(0.7f).fillMaxHeight(share)
                                .background(color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            labels.forEach {
                Text(
                    it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}
