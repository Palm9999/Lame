package dev.gridiron.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val shown by animateFloatAsState(fraction.coerceIn(0.0, 1.0).toFloat(), tween(450), label = "meter")
    Canvas(modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = description }) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * shown
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
 * Tapping a bar selects it: its value is labelled, the others fade, and [detail] (the bar's label and value by
 * default) reads under the chart; tapping it again clears.
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
    detail: (Int) -> String = { i -> "${labels[i]}: ${values[i]?.let(valueText) ?: "none"}" },
) {
    val color = ChartColors.series()
    val top = max ?: values.filterNotNull().maxOrNull() ?: 0.0
    var picked by remember(labels.size) { mutableStateOf<Int?>(null) }
    // A selection survives new values (a profile switch); a bar that no longer has one drops it.
    val selected = picked?.takeIf { values.getOrNull(it) != null }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(450)) }
    val shown = selected?.let { (labelled ?: values.indices.toSet()) + it } ?: labelled
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            values.forEachIndexed { i, v ->
                Text(
                    if (v != null && (shown == null || i in shown)) valueText(v) else "",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (i == selected) FontWeight.Bold else null,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                Box(
                    Modifier.weight(1f).height(height).testTag("bar:$i")
                        .clickable(enabled = v != null) { picked = if (selected == i) null else i },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (v != null && top > 0.0 && v > 0.0) {
                        val share = (v / top).coerceIn(0.0, 1.0).toFloat() * grow.value
                        val faded = selected != null && selected != i
                        Box(
                            Modifier.fillMaxWidth(0.7f).fillMaxHeight(share)
                                .background(if (faded) color.copy(alpha = 0.35f) else color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
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
        if (selected != null) {
            Text(
                detail(selected),
                Modifier.padding(top = 4.dp).testTag("bar:detail"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (values.any { it != null }) {
            Text(
                "Tap a bar for detail",
                Modifier.padding(top = 4.dp).testTag("bar:hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A screen's top bar: a back arrow when there is somewhere to go back to ([onBack] null hides it, as on a bottom-bar
 * tab), the [title], and [actions] at the end.
 */
@Composable
public fun ScreenBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) { Icon(GridironIcons.Back, contentDescription = "Back") }
        }
        if (title != null) {
            Text(
                title,
                titleModifier.weight(1f).padding(start = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** Grey placeholder rows that pulse while a list loads, shaped like the rows to come. */
@Composable
public fun LoadingRows(modifier: Modifier = Modifier, rows: Int = 8) {
    val pulse by rememberInfiniteTransition(label = "loading").animateFloat(
        0.4f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse",
    )
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier.fillMaxWidth().padding(16.dp).testTag("loading").semantics { contentDescription = "Loading" }.alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).background(color, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(LINE_WIDTHS[i % LINE_WIDTHS.size]).height(12.dp).background(color, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth(0.3f).height(10.dp).background(color, RoundedCornerShape(4.dp)))
                }
                Box(Modifier.width(36.dp).height(14.dp).background(color, RoundedCornerShape(4.dp)))
            }
        }
    }
}

private val LINE_WIDTHS = floatArrayOf(0.62f, 0.48f, 0.7f, 0.55f)

/**
 * A screen with nothing to show, or that couldn't load: an icon, [message] and, with [onRetry], a Try again button.
 * Centered in what room it has.
 */
@Composable
public fun EmptyState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp).testTag("emptyState"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(GridironIcons.Alert, contentDescription = null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRetry != null) FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("Try again") }
    }
}

/**
 * A section heading that folds what's under it: tapping toggles [open], and a chevron says which way. The caller shows
 * the section's rows only while open, so it works as one item of a lazy list.
 */
@Composable
public fun FoldHeader(title: String, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Fold" else "Unfold", onClick = onToggle).testTag("fold:$title")
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [content] that reloads when pulled down from its top; [refreshing] shows the spinner until the reload ends. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun PullToRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(refreshing, onRefresh, modifier.fillMaxWidth().testTag("pullRefresh"), content = content)
}
