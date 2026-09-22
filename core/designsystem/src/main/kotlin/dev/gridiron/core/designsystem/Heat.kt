package dev.gridiron.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

// Blue for better, orange for worse: a diverging scale that survives red-green
// color deficiency, which affects roughly 8% of men. Never red and green.
private val Better = Color(0xFF2F7FD1)
private val Worse = Color(0xFFE0823A)

private const val MAX_ALPHA = 0.55f

/**
 * Cell background for a positional percentile mapped to -1..1 (+1 best).
 * Transparent near the median so only real separation stands out, and never
 * dark enough to hurt the legibility of the number on top.
 */
@Composable
@ReadOnlyComposable
public fun heatColor(heat: Float?): Color {
    if (heat == null) return Color.Transparent
    val strength = abs(heat).coerceIn(0f, 1f).pow(1.4f) * MAX_ALPHA
    if (strength < 0.04f) return Color.Transparent
    return (if (heat > 0) Better else Worse).copy(alpha = strength)
}
